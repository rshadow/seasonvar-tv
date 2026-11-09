package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.data.SeasonvarParser
import ru.seasonvar.tv.data.account.AccountParser
import ru.seasonvar.tv.data.account.AccountProgressParser
import ru.seasonvar.tv.data.account.PlaybackMarkParser

object SeasonMessagesLiveProbe {
    fun run(probe: Instrumentation) = with(probe) {
        val result = Bundle()
        var phase = "session"
        try {
            check(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.FINGERPRINT.contains("generic"))
            runBlocking { withContext(Dispatchers.IO) {
                val app = targetContext.applicationContext as SeasonvarApplication
                app.account.restore()
                val owner = checkNotNull(app.account.session.value.user).id
                suspend fun read(url: String): String = app.siteClient.accountGet(url, app.sessions.current()).also {
                    check(AccountParser.user(it)?.id == owner)
                }
                phase = "resume"
                val resume = Jsoup.parse(read("https://seasonvar.ru/resume"), "https://seasonvar.ru/")
                val message = Regex("""Сезон\s+([0-9]+)\s+из\s+([0-9]+)""", RegexOption.IGNORE_CASE)
                for (tab in listOf("new", "nonew", "want", "watched")) {
                    val rows = resume.select("[data-tabr=marks-$tab] .pgs-marks-el")
                    val captions = rows.filter { message.matches(it.select(".pgs-marks-mess").text()) }
                    result.putString(tab + "_rows", rows.size.toString())
                    result.putString(tab + "_season_messages", captions.size.toString())
                    result.putString(tab + "_messages_without_counter", captions.count {
                        AccountProgressParser.episodes(it.select(".pgs-marks-current").text()) == null
                    }.toString())
                    result.putString(tab + "_messages_zero_counter", captions.count {
                        AccountProgressParser.episodes(it.select(".pgs-marks-current").text())?.marked == 0
                    }.toString())
                    result.putString(tab + "_messages_older_season", captions.count {
                        val values = checkNotNull(message.matchEntire(it.select(".pgs-marks-mess").text())).groupValues
                        values[1].toInt() < values[2].toInt()
                    }.toString())
                }
                val wanted = resume.select("[data-tabr=marks-want] .pgs-marks-el").firstOrNull {
                    message.matches(it.select(".pgs-marks-mess").text())
                }
                if (wanted != null) {
                    phase = "wanted_example"
                    val url = checkNotNull(SeasonvarParser.pageUrl(wanted.selectFirst("a[href*=serial-]")!!.absUrl("href").substringBefore('#')))
                    val id = checkNotNull(Regex("/serial-([0-9]+)").find(url)?.groupValues?.get(1))
                    val mark = PlaybackMarkParser.snapshot(read(url), id, url)
                    result.putBoolean("want_example_playback_mark", mark.mark != null)
                    result.putString("want_example_status", mark.status?.toString() ?: "none")
                }
                val target = resume.select(".pgs-marks-el").filter {
                    it.select(".pgs-marks-name").text().contains("Черное зеркало", ignoreCase = true)
                }
                check(target.isNotEmpty())
                fun rowSeason(row: org.jsoup.nodes.Element) = Regex("[0-9]+").find(row.select(".pgs-marks-seas").text())?.value
                val seed = target.firstOrNull { rowSeason(it) == "6" } ?: target.first()
                val seedUrl = checkNotNull(SeasonvarParser.pageUrl(seed.selectFirst("a[href*=serial-]")!!.absUrl("href").substringBefore('#')))
                phase = "season_pages"
                val seedHtml = read(seedUrl)
                val seasons = Jsoup.parse(seedHtml, seedUrl).select(".pgs-seaslist a[href]").associate {
                    Regex("([0-9]+)\\s*сезон", RegexOption.IGNORE_CASE).find(it.text())?.groupValues?.get(1) to
                        checkNotNull(SeasonvarParser.pageUrl(it.attr("href")))
                }
                for (number in listOf("5", "6")) {
                    val row = target.firstOrNull { rowSeason(it) == number }
                    result.putBoolean("season_" + number + "_listed", row != null)
                    val caption = row?.select(".pgs-marks-mess")?.text().orEmpty()
                    result.putString("season_" + number + "_message", message.matchEntire(caption)?.groupValues
                        ?.drop(1)?.joinToString(":") ?: if (caption.isBlank()) "absent" else "other")
                    val url = checkNotNull(seasons[number])
                    val html = if (url == seedUrl) seedHtml else read(url)
                    val id = checkNotNull(Regex("/serial-([0-9]+)").find(url)?.groupValues?.get(1))
                    val mark = PlaybackMarkParser.snapshot(html, id, url)
                    result.putBoolean("season_" + number + "_playback_mark", mark.mark != null)
                    result.putString("season_" + number + "_status", mark.status?.toString() ?: "none")
                }
                result.putBoolean("read_only", true)
                result.putBoolean("probe_success", true)
            } }
        } catch (error: Throwable) {
            result.putBoolean("probe_success", false)
            result.putString("phase", phase)
            result.putString("failure_type", error.javaClass.simpleName)
        }
        finish(Activity.RESULT_OK, result)
    }
}
