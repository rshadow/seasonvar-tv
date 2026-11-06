package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.SeriesDetailsParser
import ru.seasonvar.tv.data.account.PersonalFeed
import ru.seasonvar.tv.data.account.PlaybackMarkParser
import ru.seasonvar.tv.data.smart.MarkedSeason
import ru.seasonvar.tv.data.smart.SmartContinueRepository
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

object SmartContinueLiveProbe {
    fun run(probe: Instrumentation, arguments: Bundle) = with(probe) {
        val result = Bundle()
        val marksRequests = AtomicInteger(); val historyRequests = AtomicInteger(); val seasonRequests = AtomicInteger()
        val active = AtomicInteger(); val peak = AtomicInteger()
        suspend fun <T> request(counter: AtomicInteger, block: suspend () -> T): T {
            counter.incrementAndGet()
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            return try { block() } finally { active.decrementAndGet() }
        }
        try {
            check(android.os.Build.MODEL.contains("sdk", true))
            runBlocking { withContext(Dispatchers.IO) {
                val app = targetContext.applicationContext as SeasonvarApplication
                app.account.restore()
                checkNotNull(app.account.session.value.user)
                result.putBoolean("authenticated", true)
                val target = arguments.getString("target-season").orEmpty()
                val markCount = AtomicInteger()
                val historyRows = ConcurrentLinkedQueue<SeriesEntry>()
                val historyHasMore = AtomicBoolean()
                val repository = SmartContinueRepository(
                    loadMarks = { request(marksRequests) {
                        app.account.resume().entries.also { markCount.set(it.distinctBy { entry -> entry.id }.size) }
                    } },
                    loadSeason = { entry -> request(seasonRequests) {
                        val html = app.account.seasonPage(entry)
                        MarkedSeason(SeriesDetailsParser.parse(html, entry), PlaybackMarkParser.snapshot(html, entry.id, entry.url))
                    } },
                    preferences = { app.interfaceSettings.preferredTranslations.value },
                    loadHistoryPage = { next -> request(historyRequests) {
                        app.account.personalList(PersonalFeed.HISTORY, next).also {
                            historyRows.addAll(it.entries)
                            if (next == null) historyHasMore.set(it.nextPage != null)
                        }
                    } },
                )
                val started = SystemClock.elapsedRealtime()
                val page = repository.load()
                result.putString("smart_duration_ms", (SystemClock.elapsedRealtime() - started).toString())
                val history = historyRows.toList()
                result.putString("smart_mark_count", markCount.get().toString())
                result.putString("history_count", history.size.toString())
                result.putString("history_seasons", history.distinctBy { it.id }.size.toString())
                result.putBoolean("history_has_more", historyHasMore.get())
                result.putBoolean("history_has_dates", history.any { Regex("[0-9]{1,2}:[0-9]{2}").containsMatchIn(it.historyVisitedAt.orEmpty()) })
                if (target.isNotBlank()) result.putBoolean("history_target_found", history.any { it.id == target })
                result.putString("smart_count", page.entries.size.toString())
                result.putBoolean("smart_incomplete", page.incomplete)
                result.putString("smart_failures", page.failedSeasons.joinToString(",") { it.first + ":" + it.second })
                if (target.isNotBlank()) {
                    val entry = page.entries.firstOrNull { it.id == target }
                    result.putBoolean("smart_target_found", entry != null)
                    entry?.let {
                        result.putString("smart_target_episode", it.initialSelection.episodeId)
                        result.putString("smart_target_seek_ms", it.initialSelection.positionMillis.toString())
                    }
                }
                check(!page.incomplete)
                check(peak.get() <= 4)
            } }
            result.putBoolean("probe_success", true)
        } catch (error: Exception) {
            result.putBoolean("probe_success", false)
            result.putString("failure_type", error.javaClass.simpleName)
        }
        result.putString("smart_mark_requests", marksRequests.get().toString())
        result.putString("smart_history_requests", historyRequests.get().toString())
        result.putString("smart_season_requests", seasonRequests.get().toString())
        result.putString("smart_total_requests", (marksRequests.get() + historyRequests.get() + seasonRequests.get()).toString())
        result.putString("smart_request_peak", peak.get().toString())
        finish(if (result.getBoolean("probe_success")) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
