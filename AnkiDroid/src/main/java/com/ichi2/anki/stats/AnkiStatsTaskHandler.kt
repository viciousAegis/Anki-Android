/****************************************************************************************
 * Copyright (c) 2014 Michael Goldbach <michael@m-goldbach.net>                         *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General Public License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/
package com.ichi2.anki.stats

import android.R
import android.util.Pair
import android.view.View
import android.webkit.WebView
import android.widget.ProgressBar
import android.widget.TextView
import com.ichi2.libanki.Collection
import com.ichi2.libanki.stats.Stats
import com.ichi2.libanki.stats.Stats.AxisType
import com.ichi2.libanki.stats.Stats.ChartType
import com.ichi2.themes.Themes.getColorFromAttr
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.lang.ref.WeakReference
import java.net.URLEncoder
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.roundToInt

class AnkiStatsTaskHandler private constructor(
    private val collectionData: Collection,
    private val mainDispatcher: CoroutineDispatcher,
    private val defaultDispatcher: CoroutineDispatcher
) {
    var standardTextSize = 10f
    var statType = AxisType.TYPE_MONTH
    private var mDeckId: Long = 0
    fun setDeckId(deckId: Long) {
        mDeckId = deckId
    }

    suspend fun createChart(
        chartType: ChartType,
        progressBar: ProgressBar,
        chartView: ChartView,
    ) = withContext(defaultDispatcher) {
        mutex.withLock {
            val plotSheet = if (!this.isActive) {
                Timber.d("Quitting CreateChartTask (%s) before execution", chartType.name)
                null
            } else {
                Timber.d("Starting CreateChartTask, type: %s", chartType.name)
                val chartBuilder = ChartBuilder(
                    chartView, collectionData,
                    mDeckId, chartType
                )
                chartBuilder.renderChart(statType)
            }
            plotSheet?.let {
                withContext(mainDispatcher) {
                    chartView.setData(plotSheet)
                    progressBar.visibility = View.GONE
                    chartView.visibility = View.VISIBLE
                    chartView.invalidate()
                }
            }
        }
    }

    suspend fun createStatisticsOverview(webView: WebView, progressBar: ProgressBar) =
        withContext(defaultDispatcher) {
            mutex.withLock {
                val html = if (!isActive) {
                    Timber.d("Quitting CreateStatisticsOverview before execution")
                    null
                } else {
                    Timber.d("Starting CreateStatisticsOverview")
                    val overviewStatsBuilder =
                        OverviewStatsBuilder(webView, collectionData, mDeckId, statType)
                    overviewStatsBuilder.createInfoHtmlString()
                }
                html?.let {
                    withContext(mainDispatcher) {
                        runCatching {
                            webView.loadData(
                                URLEncoder.encode(html, "UTF-8").replace("\\+".toRegex(), " "),
                                "text/html; charset=utf-8",
                                "utf-8"
                            )
                        }.getOrElse {
                            Timber.w(it)
                        }
                        progressBar.visibility = View.GONE
                        val backgroundColor = getColorFromAttr(webView.context, R.attr.colorBackground)
                        webView.setBackgroundColor(backgroundColor)
                        webView.visibility = View.VISIBLE
                        webView.invalidate()
                    }
                }
            }
        }

    @Suppress("deprecation") // #7108: AsyncTask
    class DeckPreviewStatistics : android.os.AsyncTask<Pair<Collection, TextView?>?, Void?, String?>() {
        private var mTextView: WeakReference<TextView>? = null
        private var mIsRunning = true
        override fun doInBackground(vararg params: Pair<Collection, TextView?>?): String? {
            // make sure only one task of CreateChartTask is running, first to run should get sLock
            // only necessary on lower APIs because after honeycomb only one thread is used for all asynctasks
            sLock.lock()
            return try {
                val collection = params[0]!!.first
                val textView = params[0]!!.second
                mTextView = WeakReference(textView)
                if (!mIsRunning || collection == null || collection.dbClosed) {
                    Timber.d("Quitting DeckPreviewStatistics before execution")
                    return null
                } else {
                    Timber.d("Starting DeckPreviewStatistics")
                }

                // eventually put this in Stats (in desktop it is not though)
                var cards: Int
                var minutes: Int
                val query = "select sum(case when ease > 0 then 1 else 0 end), " + /* cards, excludes rescheduled cards https://github.com/ankidroid/Anki-Android/issues/8592 */
                    "sum(time)/1000 from revlog where id > " + (collection.sched.dayCutoff - Stats.SECONDS_PER_DAY) * 1000
                Timber.d("DeckPreviewStatistics query: %s", query)
                collection.db
                    .query(query).use { cur ->
                        cur.moveToFirst()
                        cards = cur.getInt(0)
                        minutes = (cur.getInt(1) / 60.0).roundToInt()
                    }
                val res = textView!!.resources
                val span = res.getQuantityString(com.ichi2.anki.R.plurals.in_minutes, minutes, minutes)
                res.getQuantityString(com.ichi2.anki.R.plurals.studied_cards_today, cards, cards, span)
            } finally {
                sLock.unlock()
            }
        }

        override fun onCancelled() {
            mIsRunning = false
        }

        override fun onPostExecute(todayStatString: String?) {
            val textView = mTextView!!.get()
            if (todayStatString != null && mIsRunning && textView != null) {
                textView.text = todayStatString
                textView.visibility = View.VISIBLE
                textView.invalidate()
            }
        }
    }

    companion object {
        @JvmStatic
        var instance: AnkiStatsTaskHandler? = null
            private set
        private val sLock: Lock = ReentrantLock()
        private val mutex = Mutex()
        @JvmStatic
        @Synchronized
        fun getInstance(
            collection: Collection,
            mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
            defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
        ): AnkiStatsTaskHandler? {
            if (instance == null || instance!!.collectionData !== collection) {
                instance = AnkiStatsTaskHandler(collection, mainDispatcher, defaultDispatcher)
            }
            return instance
        }

        @Suppress("deprecation") // #7108: AsyncTask
        @JvmStatic
        fun createReviewSummaryStatistics(col: Collection, view: TextView): DeckPreviewStatistics {
            val deckPreviewStatistics = DeckPreviewStatistics()
            deckPreviewStatistics.execute(Pair(col, view))
            return deckPreviewStatistics
        }
    }
}
