package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.data.*
import java.time.LocalDate

object BrowseCatalogLiveProbe {
    fun run(probe: Instrumentation) = with(probe) {
        val report = JSONObject()
        var phase = "start"
        fun heap(): Long {
            repeat(3) { Runtime.getRuntime().gc(); System.runFinalization() }
            val runtime = Runtime.getRuntime()
            return runtime.totalMemory() - runtime.freeMemory()
        }
        try {
            check(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.FINGERPRINT.contains("generic"))
            val app = targetContext.applicationContext as SeasonvarApplication
            runBlocking { withContext(Dispatchers.IO) {
                phase = "guest"
                val client = SiteClient(app.networkSettings)
                phase = "guest_options"
                val options = BrowseCatalogParser.options(client.get("https://seasonvar.ru/"))
                val defaults = options.normalize(CatalogFilters(), false)
                phase = "guest_request"
                val html = client.catalog(defaults)
                // Первый проход прогревает классы парсера перед сравнением живых объектов.
                BrowseCatalogParser.page("<div class='pgs-msg'>Под заданный фильтр<br>ничего не подходит</div>", LocalDate.now())
                val partialStart = SystemClock.elapsedRealtime()
                val partial = BrowseCatalogParser.entries(html.reader())
                report.put("first_batch_ms", SystemClock.elapsedRealtime() - partialStart)
                    .put("first_batch_entries", partial.size)
                val window = checkNotNull(partial.window)
                window.advance(198); check(window.state.value.entries.size == 250)
                window.advance(199); check(window.state.value.entries.size == 500)
                window.advance(449); check(window.state.value.entries.size == 750)
                report.put("window_thresholds_verified", true)
                window.close()
                val nativeBefore = app.cardData.size
                val before = heap()
                phase = "guest_parse"
                val started = SystemClock.elapsedRealtime()
                val page = BrowseCatalogParser.page(html, LocalDate.now())
                val duration = SystemClock.elapsedRealtime() - started
                val after = heap()
                check(page.entries.size > 200)
                report.put("guest_entries", page.entries.size).put("html_bytes", html.toByteArray().size)
                    .put("parse_ms", duration).put("list_heap_delta_bytes", after - before)
                    .put("native_cache_before", nativeBefore).put("native_cache_after", app.cardData.size)
                check(nativeBefore == app.cardData.size)
                val samplesBefore = heap()
                val samples = List(20) { BrowseCatalogParser.entries(html.reader()).also { it.window?.close() } }
                val samplesAfter = heap()
                check(samples.sumOf { it.size } == 5000)
                if (samplesAfter > samplesBefore) report.put("first_batch_retained_average_bytes", (samplesAfter - samplesBefore) / samples.size)
                val genre = options.choices.getValue("genres").first { it.value != "all" }.value
                phase = "guest_genre"
                val filtered = BrowseCatalogParser.page(client.catalog(defaults.with("genres", genre)), LocalDate.now())
                report.put("guest_genre_entries", filtered.entries.size)
                check(filtered.entries.size < page.entries.size)
                val empty = BrowseCatalogParser.page(client.catalog(defaults.with("ratingSource", "imdb").with("rating", "8").with("ratingOp", ">")), LocalDate.now())
                report.put("rating_filter_entries", empty.entries.size)
                phase = "account"
                if (app.account.session.value.user == null) app.account.restore()
                if (app.account.session.value.user != null) {
                    val beforeMarks = app.account.resume().entries
                    val accountOptions = BrowseCatalogParser.options(app.account.catalogMetadata())
                    val all = accountOptions.normalize(CatalogFilters(), true)
                    val base = BrowseCatalogParser.page(app.siteClient.catalog(all), LocalDate.now())
                    val counts = JSONObject()
                    for (key in CatalogFilters.personal.sorted()) {
                        val result = BrowseCatalogParser.page(app.siteClient.catalog(all.with(key, "true")), LocalDate.now())
                        check(result.entries.size <= base.entries.size)
                        counts.put(key, result.entries.size)
                    }
                    report.put("account_entries", base.entries.size).put("personal_filter_counts", counts)
                        .put("marks_unchanged", beforeMarks == app.account.resume().entries)
                    check(report.getBoolean("marks_unchanged"))
                }
                report.put("read_only", true).put("probe_success", true)
            } }
        } catch (error: Throwable) {
            report.put("probe_success", false).put("phase", phase).put("failure_type", error.javaClass.simpleName)
                .put("failure_line", error.stackTrace.firstOrNull { it.fileName == "BrowseCatalog.kt" }?.lineNumber)
        }
        finish(Activity.RESULT_OK, Bundle().apply { putString("result_json", report.toString()) })
    }
}
