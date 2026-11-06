package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.data.account.PersonalFeed
import ru.seasonvar.tv.data.smart.SmartContinueRepository

object SmartContinueLiveProbe {
    fun run(probe: Instrumentation, arguments: Bundle) = with(probe) {
        val result = Bundle()
        try {
            check(android.os.Build.MODEL.contains("sdk", true))
            runBlocking { withContext(Dispatchers.IO) {
                val app = targetContext.applicationContext as SeasonvarApplication
                app.account.restore()
                checkNotNull(app.account.session.value.user)
                result.putBoolean("authenticated", true)
                val historyPage = app.account.personalList(PersonalFeed.HISTORY)
                val history = historyPage.entries
                result.putBoolean("history_has_more", historyPage.nextPage != null)
                result.putString("history_count", history.size.toString())
                result.putBoolean("history_has_dates", history.any { Regex("[0-9]{1,2}:[0-9]{2}").containsMatchIn(it.historyVisitedAt.orEmpty()) })
                val page = SmartContinueRepository(app.account) { app.interfaceSettings.preferredTranslations.value }.load()
                result.putString("smart_count", page.entries.size.toString())
                result.putBoolean("smart_incomplete", page.incomplete)
                result.putString("smart_failures", page.failedSeasons.joinToString(",") { it.first + ":" + it.second })
                val target = arguments.getString("target-season").orEmpty()
                if (target.isNotBlank()) {
                    val entry = page.entries.firstOrNull { it.id == target }
                    result.putBoolean("smart_target_found", entry != null)
                    entry?.let {
                        result.putString("smart_target_episode", it.initialSelection.episodeId)
                        result.putString("smart_target_seek_ms", it.initialSelection.positionMillis.toString())
                    }
                    result.putBoolean("history_target_found", history.any { it.id == target })
                }
                check(!page.incomplete)
            } }
            result.putBoolean("probe_success", true)
        } catch (error: Exception) {
            result.putBoolean("probe_success", false)
            result.putString("failure_type", error.javaClass.simpleName)
        }
        finish(if (result.getBoolean("probe_success")) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
