package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.data.SiteClient
import ru.seasonvar.tv.data.account.PersonalFeed

object BestFiltersLiveProbe {
    fun run(probe: Instrumentation) = with(probe) {
        val report = JSONObject()
        var phase = "start"
        try {
            check(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.FINGERPRINT.contains("generic"))
            val app = targetContext.applicationContext as SeasonvarApplication
            runBlocking { withContext(Dispatchers.IO) {
                if (app.account.session.value.user == null) app.account.restore()
                checkNotNull(app.account.session.value.user)
                val before = app.account.resume().entries
                val history = app.account.personalList(PersonalFeed.HISTORY).entries
                fun title(value: String) = value.substringBefore(" (").trim().lowercase()
                val watched = before.map { title(it.title) }.toSet()
                val played = history.map { title(it.title) }.toSet()
                val url = "https://seasonvar.ru/?mode=top&period=1"
                val contexts = JSONObject()
                for ((context, client) in listOf("guest" to SiteClient(app.networkSettings), "account" to app.siteClient)) {
                    phase = context
                    fun request(flag: String? = null): String = runBlocking {
                        if (flag == null) client.get(url) else client.bestFilters(ru.seasonvar.tv.data.BestListFilters(
                            switches = ru.seasonvar.tv.data.BestSwitch.entries.filter { it.id == flag }.toSet()))
                    }
                    val baseline = request()
                    val results = JSONArray()
                    for (flag in listOf("default", "fhd", "hd", "sub", "eng", "block", "mark", "history", "nw")) {
                        phase = "$context:$flag"
                        val html = request(flag)
                        val rows = Jsoup.parse(html).select(".pgs-search-wrap")
                        check(rows.isNotEmpty() || html.isBlank())
                        val names = rows.map { title(it.selectFirst(".pgs-search-info > a")?.text().orEmpty()) }.toSet()
                        results.put(JSONObject().put("flag", flag).put("rows", rows.size)
                            .put("watching_icons", rows.count { it.selectFirst(".svico-mwatch") != null })
                            .put("history_icons", rows.count { it.selectFirst(".svico-mwatched") != null })
                            .put("blacklist_icons", rows.count { it.selectFirst(".svico-mnotwatch") != null })
                            .put("resume_title_matches", names.intersect(watched).size)
                            .put("history_title_matches", names.intersect(played).size))
                    }
                    val empty = client.bestFilters(ru.seasonvar.tv.data.BestListFilters("10", "1",
                        setOf(ru.seasonvar.tv.data.BestSwitch.FHD, ru.seasonvar.tv.data.BestSwitch.SUBTITLES, ru.seasonvar.tv.data.BestSwitch.FOREIGN_ONLY)))
                    check(ru.seasonvar.tv.data.SeasonvarParser.bestFragment(empty, java.time.LocalDate.now()).entries.isEmpty())
                    contexts.put(context, JSONObject().put("explicit_empty_result", true).put("results", results)
                        .put("plain_get_unchanged", Jsoup.parse(baseline).select("#search-res").html() == Jsoup.parse(request()).select("#search-res").html()))
                }
                report.put("contexts", contexts).put("marks_unchanged", before == app.account.resume().entries)
                check(report.getBoolean("marks_unchanged"))
                report.put("probe_success", true)
            } }
        } catch (error: Throwable) {
            report.put("probe_success", false).put("phase", phase).put("failure_type", error.javaClass.simpleName)
        }
        finish(Activity.RESULT_OK, Bundle().apply { putString("result_json", report.toString()) })
    }
}
