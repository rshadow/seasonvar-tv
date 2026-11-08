package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.data.account.AccountParser
import ru.seasonvar.tv.data.account.PersonalFeed

object PersonalFiltersLiveProbe {
    fun run(probe: Instrumentation) = with(probe) {
        val result = Bundle()
        val report = JSONObject()
        var phase = "session"
        try {
            check(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.FINGERPRINT.contains("generic"))
            val app = targetContext.applicationContext as SeasonvarApplication
            runBlocking { withContext(Dispatchers.IO) {
                if (app.account.session.value.user == null) app.account.restore()
                val owner = checkNotNull(app.account.session.value.user).id
                val jar = app.sessions.current()
                suspend fun resume(): String = app.siteClient.accountGet("https://seasonvar.ru/resume", jar).also {
                    check(AccountParser.user(it)?.id == owner)
                }
                phase = "baseline"
                val before = resume()
                val doc = Jsoup.parse(before, "https://seasonvar.ru/")
                fun options(name: String): JSONArray = JSONArray().apply {
                    doc.select("select[name=$name] option[value]").forEach { put(JSONObject().put("value", it.attr("value")).put("label", it.text())) }
                }
                val genres = options("marks-genre")
                val countries = options("marks-country")
                check(genres.length() > 1 && countries.length() > 1)
                report.put("genres", genres).put("countries", countries)
                val genre = (0 until genres.length()).map(genres::getJSONObject).first { it.getString("value") != "all" }.getString("value")
                val country = (0 until countries.length()).map(countries::getJSONObject).firstOrNull { it.getString("value") == "Япония" }
                    ?.getString("value") ?: countries.getJSONObject(1).getString("value")
                fun entries(html: String) = PersonalFeed.entries.filter { it != PersonalFeed.HISTORY }.associateWith {
                    AccountParser.personalList(html, it).entries
                }
                val baseline = entries(before)
                phase = "filtered_post"
                for ((name, values) in listOf("all" to ("all" to "all"), "genre" to (genre to "all"), "country" to ("all" to country))) {
                    val request = Request.Builder().url("https://seasonvar.ru/ajax.php")
                        .header("Referer", "https://seasonvar.ru/resume").header("Origin", "https://seasonvar.ru")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .post(FormBody.Builder().add("marks_filter", "true").add("country", values.second).add("genre", values.first).build()).build()
                    val fragment = app.siteClient.newCall(request).execute().use { check(it.code == 200); it.body.string() }
                    val response = Jsoup.parse(fragment, "https://seasonvar.ru/")
                    val shape = JSONObject().put("header", response.selectFirst(".headblock-menu") != null)
                        .put("marks", response.selectFirst(".pgs-marks") != null)
                        .put("tabs", JSONArray(response.select("[data-tabr]").map { it.attr("data-tabr") }))
                    val combined = doc.clone()
                    val target = combined.selectFirst("[data-result=marksf]") ?: error("Missing result container")
                    target.html(fragment)
                    val filtered = entries(combined.outerHtml())
                    val counts = JSONObject()
                    filtered.forEach { (feed, rows) ->
                        counts.put(feed.name, rows.size)
                        check(rows.map { it.key }.all { key -> baseline.getValue(feed).any { it.key == key } })
                    }
                    if (name == "all") check(filtered == baseline)
                    shape.put("counts", counts)
                    report.put(name, shape)
                }
                phase = "unchanged"
                report.put("unfiltered_get_unchanged", entries(resume()) == baseline)
                check(report.getBoolean("unfiltered_get_unchanged"))
                report.put("probe_success", true)
            } }
        } catch (error: Throwable) {
            report.put("probe_success", false).put("phase", phase).put("failure_type", error.javaClass.simpleName)
        }
        result.putString("result_json", report.toString())
        finish(Activity.RESULT_OK, result)
    }
}
