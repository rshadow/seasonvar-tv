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
import ru.seasonvar.tv.data.account.AccountParser
import ru.seasonvar.tv.data.account.HistoryParser
import ru.seasonvar.tv.data.account.PersonalFeed

object HistoryFiltersLiveProbe {
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
                val url = PersonalFeed.HISTORY.url
                suspend fun load(target: String): String = app.siteClient.get(target).also { check(AccountParser.user(it)?.id == owner) }
                phase = "page"
                val html = load(url)
                val baseline = HistoryParser.parse(html)
                fun entries(body: String) = HistoryParser.parse(body).entries.map { it.url to it.initialSelection }
                val expected = entries(html)
                val doc = Jsoup.parse(html, "https://seasonvar.ru/")
                val main = checkNotNull(doc.selectFirst(".content .content-wrap"))
                val controls = main.select("input, select, button, [data-click], [data-change], form")
                fun identifier(value: String) = value.takeIf { it.matches(Regex("[A-Za-z_][A-Za-z_0-9-]{0,60}")) }.orEmpty()
                report.put("rows", baseline.entries.size).put("next_page_present", baseline.nextPage != null)
                    .put("own_controls", JSONArray().apply { controls.forEach { node -> put(JSONObject()
                        .put("tag", node.tagName()).put("type", identifier(node.attr("type"))).put("name", identifier(node.attr("name")))
                        .put("data_input", identifier(node.attr("data-input"))).put("data_filter", identifier(node.attr("data-filter"))).put("data_click", identifier(node.attr("data-click")))
                        .put("data_change", identifier(node.attr("data-change")))) } })
                    .put("pagination_links", main.select(".pgs-history-p a[href]").size)
                    .put("global_catalog_filter_present", doc.selectFirst("[data-click=fShowResult]") != null)
                    .put("public_script_paths", JSONArray().apply { doc.select("script[src]").mapNotNull {
                        runCatching { java.net.URI(it.absUrl("src")).path }.getOrNull()?.takeIf { path -> path.startsWith("/asset/js/") }
                    }.distinct().forEach(::put) })
                val searchScript = doc.select("script:not([src])").filter { it.data().contains("history-search") }.joinToString("\n") { it.data() }
                report.put("search_script_count", doc.select("script:not([src])").count { it.data().contains("history-search") })
                    .put("search_parameter_names", JSONArray().apply {
                        Regex("(?:\\{|,)\\s*['\"]?([A-Za-z_][A-Za-z_0-9]*)['\"]?\\s*:")
                            .findAll(searchScript).map { it.groupValues[1] }.distinct().forEach(::put)
                    }).put("search_post_paths", JSONArray().apply {
                        Regex("\\.post\\(\\s*['\"]([^'\"]+)['\"]").findAll(searchScript)
                            .map { it.groupValues[1] }.filter { it in setOf("/ajax.php", "ajax.php", "/index.php", "index.php") }.distinct().forEach(::put)
                    }).put("search_method_names", JSONArray().apply {
                        Regex("\\.([A-Za-z_][A-Za-z_0-9]*)\\s*\\(").findAll(searchScript).map { it.groupValues[1] }.distinct().forEach(::put)
                    })
                val deletionScript = doc.select("script:not([src])").filter { it.data().contains("historydel") }.joinToString("\n") { it.data() }
                val deletionFlag = Regex("""["']?dellAllHistory["']?\s*:\s*(true|false|!0|!1|[01]|["'](?:yes|true|1)["'])""")
                    .find(deletionScript)?.groupValues?.get(1)
                report.put("clear_flag_encoding", deletionFlag ?: "unrecognized")
                    .put("clear_uses_ajax", deletionScript.contains("ajax.php")).put("clear_reloads_page", deletionScript.contains("reload"))
                phase = "queries"
                val results = JSONArray()
                for ((name, suffix) in listOf("genre_country" to "genre=18&country=Япония", "legacy_keys" to "ganre=17&block=1",
                    "period" to "period=7", "search_q" to "q=__missing_history_filter_probe__", "search_query" to "query=__missing_history_filter_probe__")) {
                    val queried = load("$url&$suffix")
                    results.put(JSONObject().put("case", name).put("same_entries", entries(queried) == expected))
                }
                report.put("queries", results).put("history_unchanged", entries(load(url)) == expected)
                    .put("marks_unchanged", app.account.resume().entries == marks)
                check(report.getBoolean("history_unchanged") && report.getBoolean("marks_unchanged"))
                report.put("probe_success", true)
            } }
        } catch (error: Throwable) {
            report.put("probe_success", false).put("phase", phase).put("failure_type", error.javaClass.simpleName)
        }
        finish(Activity.RESULT_OK, Bundle().apply { putString("result_json", report.toString()) })
    }
}
