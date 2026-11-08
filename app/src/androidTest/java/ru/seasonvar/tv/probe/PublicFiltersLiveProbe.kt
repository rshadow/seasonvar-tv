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
import ru.seasonvar.tv.data.SiteClient
import ru.seasonvar.tv.data.SeasonvarParser
import ru.seasonvar.tv.data.account.AccountParser
import java.security.MessageDigest
import java.time.LocalDate

object PublicFiltersLiveProbe {
    fun run(probe: Instrumentation) = with(probe) {
        val report = JSONObject()
        var phase = "session"
        try {
            check(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.FINGERPRINT.contains("generic"))
            val app = targetContext.applicationContext as SeasonvarApplication
            runBlocking { withContext(Dispatchers.IO) {
                if (app.account.session.value.user == null) app.account.restore()
                val owner = checkNotNull(app.account.session.value.user).id
                val marks = app.account.resume().entries
                val cases = linkedMapOf(
                    "default" to emptyMap(), "comedy" to mapOf("ganre" to "17"), "anime" to mapOf("ganre" to "18"),
                    "genre_key" to mapOf("genre" to "17"), "japan" to mapOf("country" to "Япония"), "russia" to mapOf("country" to "Россия"),
                    "unknown_country" to mapOf("country" to "__unknown__"), "unknown_genre" to mapOf("ganre" to "999999"),
                    "available" to mapOf("block" to "1"), "secondary" to mapOf("main" to "0"),
                    "secondary_anime" to mapOf("main" to "0", "ganre" to "18"), "secondary_japan" to mapOf("main" to "0", "country" to "Япония"),
                    "repeat" to emptyMap())
                val contexts = JSONObject()
                for ((name, client) in listOf("guest" to SiteClient(app.networkSettings), "account" to app.siteClient)) {
                    phase = name
                    val home = client.get("https://seasonvar.ru/")
                    if (name == "account") check(AccountParser.user(home)?.id == owner)
                    fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
                    val feeds = JSONObject()
                    for (mode in listOf("new", "pop", "newest")) {
                        var baseline: String? = null
                        val results = JSONArray()
                        for ((case, changes) in cases) {
                            phase = "$name:$mode:$case"
                            val values = linkedMapOf("ganre" to "", "country" to "", "block" to "0", "main" to "1") + changes
                            val request = Request.Builder().url("https://seasonvar.ru/ajax.php?mode=$mode")
                                .header("Referer", "https://seasonvar.ru/").header("X-Requested-With", "XMLHttpRequest")
                                .post(FormBody.Builder().apply { values.forEach { (key, value) -> add(key, value) } }.build()).build()
                            val html = client.newCall(request).execute().use { check(it.code == 200); checkNotNull(it.body).string() }
                            val entries = SeasonvarParser.sidebar(html, LocalDate.now()).entries
                            if (baseline == null) baseline = html
                            results.put(JSONObject().put("case", case).put("rows", entries.size)
                                .put("same_body_as_default", html == baseline).put("body_sha256", hash(html)))
                        }
                        feeds.put(mode, results)
                    }
                    val pages = JSONObject()
                    for ((page, url, selector) in listOf(
                        Triple("today", "https://seasonvar.ru/", ".news"),
                        Triple("announcements", "https://seasonvar.ru/?mode=soon", ".content .content-wrap .pgs-search > .tabs-result"))) {
                        phase = "$name:$page"
                        val plain = client.get(url)
                        val doc = Jsoup.parse(plain)
                        val options = JSONArray()
                        for ((suffix, description) in listOf("genre=18&country=Япония" to "genre_country", "ganre=17&block=1" to "legacy_keys")) {
                            val filtered = client.get(url + (if (url.contains('?')) "&" else "?") + suffix)
                            options.put(JSONObject().put("case", description)
                                .put("same_entries", doc.select(selector).outerHtml() == Jsoup.parse(filtered).select(selector).outerHtml()))
                        }
                        pages.put(page, JSONObject().put("own_filter_controls", doc.select(".content .content-wrap input[data-filter], .content .content-wrap select[data-filter]").size)
                            .put("global_filter_present", doc.selectFirst("[data-click=fShowResult]") != null).put("queries", options))
                    }
                    contexts.put(name, JSONObject().put("sidebar", feeds).put("pages", pages))
                }
                report.put("contexts", contexts).put("marks_unchanged", marks == app.account.resume().entries)
                check(report.getBoolean("marks_unchanged"))
                report.put("probe_success", true)
            } }
        } catch (error: Throwable) {
            report.put("probe_success", false).put("phase", phase).put("failure_type", error.javaClass.simpleName)
        }
        finish(Activity.RESULT_OK, Bundle().apply { putString("result_json", report.toString()) })
    }
}
