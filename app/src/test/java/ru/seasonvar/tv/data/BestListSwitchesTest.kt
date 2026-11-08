package ru.seasonvar.tv.data

import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.seasonvar.tv.ui.catalog.BestListFilterSource
import ru.seasonvar.tv.ui.filters.ListFilterKind
import ru.seasonvar.tv.ui.filters.ListFilterPreferences
import java.io.IOException
import java.io.File
import java.time.LocalDate

class BestListSwitchesTest {
    @get:Rule val folder = TemporaryFolder()
    private val day = LocalDate.of(2026, 11, 8)
    private val html = javaClass.getResource("/best-filter-options.html")!!.readText()
    private val fragment = Jsoup.parse(html).selectFirst("#search-res")!!.html()
    private class Source(var html: String, var fragment: String) : HtmlSource {
        var posts = 0
        var gets = 0
        var failure = false
        override suspend fun get(url: String): String { gets++; return html }
        override suspend fun sidebar(feed: PublicFeed): String = error("Wrong source")
        override suspend fun bestFilters(filters: BestListFilters): String { posts++; if (failure) throw IOException(); return fragment }
    }

    @Test fun all_switches_roundtrip_and_russian_checkbox_has_inverse_wire_value() {
        val all = BestListFilters(switches = BestSwitch.entries.toSet())
        assertEquals(all, BestListFilters.from(all.values()))
        assertEquals("false", all.values()["eng"])
        assertEquals("true", BestListFilters().values()["eng"])
        assertEquals("yes", all.form()["filterTop[eng]"])
        assertTrue(BestListFilters().form().values.all { it.isEmpty() })
        assertEquals(BestSwitch.entries.toSet(), BestFilterParser.options(html).switches)
        assertEquals(256, (0..255).map { mask -> BestListFilters(switches = BestSwitch.entries.filter { mask and (1 shl it.ordinal) != 0 }.toSet()).cacheName }.distinct().size)
    }

    @Test fun request_uses_current_genre_period_and_all_eight_form_fields() {
        val selected = BestListFilters("17", "7", setOf(BestSwitch.FHD, BestSwitch.HIDE_HISTORY))
        val request = SiteClient.bestFiltersRequest(selected)
        assertEquals("POST", request.method)
        assertEquals("https://seasonvar.ru/?mode=top&genre=17&period=7", request.url.toString())
        assertEquals(request.url.toString(), request.header("Referer"))
        assertEquals("https://seasonvar.ru", request.header("Origin"))
        assertEquals("XMLHttpRequest", request.header("X-Requested-With"))
        val body = request.body as FormBody
        assertEquals(selected.form(), (0 until body.size).associate { body.name(it) to body.value(it) })
    }

    @Test fun fragment_is_parsed_without_inventing_page_or_account_header_and_bad_markup_is_rejected() {
        assertEquals(SeasonvarParser.best(html, day), SeasonvarParser.bestFragment(fragment, day))
        assertTrue(SeasonvarParser.bestFragment(javaClass.getResource("/best-filter-empty.html")!!.readText(), day).entries.isEmpty())
        for (bad in listOf("", "<div class=\"pgs-msg\">Войдите</div>", "<h1>Sign in</h1>", html, "<script>alert(1)</script>", fragment.replace("/serial-123-Test.html", "https://example.org/serial-123-Test.html")))
            assertThrows(MarkupChangedException::class.java) { SeasonvarParser.bestFragment(bad, day) }
    }

    @Test fun filtered_fragment_has_separate_cache_and_cache_conditions_are_validated() = runBlocking {
        val source = Source(html, fragment.replace("Тест", "Отфильтровано"))
        val directory = folder.newFolder()
        val repository = CatalogRepository(directory, source)
        val selected = BestListFilters(switches = setOf(BestSwitch.FHD))
        val filtered = repository.best(day, selected)
        assertEquals("Отфильтровано", filtered.page.entries.single().title)
        assertEquals(selected, filtered.filters)
        assertEquals(filtered, repository.cachedBest(day, selected))
        assertEquals(1, source.posts)
        assertNull(repository.cachedBest(day, BestListFilters(switches = setOf(BestSwitch.HD))))
        assertEquals("Тест", repository.best(day, BestListFilters()).page.entries.single().title)
        assertEquals(1, source.posts)
        val file = File(directory, selected.cacheName)
        val saved = JSONObject(file.readText())
        saved.getJSONObject("filters").put("fhd", "false")
        file.writeText(saved.toString())
        assertNull(repository.cachedBest(day, selected))
        assertEquals("Тест", repository.cached(day, PublicFeed.BEST)!!.entries.single().title)
    }

    @Test fun failure_and_malformed_response_keep_good_cache_and_blank_success_is_a_real_empty_result() = runBlocking {
        val source = Source(html, fragment)
        val repository = CatalogRepository(folder.newFolder(), source)
        val selected = BestListFilters(switches = setOf(BestSwitch.SUBTITLES))
        repository.best(day, selected)
        source.failure = true
        assertTrue(runCatching { repository.best(day, selected) }.isFailure)
        assertEquals(1, repository.cachedBest(day, selected)!!.page.entries.size)
        source.failure = false; source.fragment = "<h1>Unexpected</h1>"
        assertTrue(runCatching { repository.best(day, selected) }.isFailure)
        assertEquals(1, repository.cachedBest(day, selected)!!.page.entries.size)
        source.fragment = javaClass.getResource("/best-filter-empty.html")!!.readText()
        assertTrue(repository.best(day, selected).page.entries.isEmpty())
        assertTrue(repository.cachedBest(day, selected)!!.page.entries.isEmpty())
    }

    @Test fun absent_switch_capability_is_removed_without_post_or_redundant_get() = runBlocking {
        val doc = Jsoup.parse(html); doc.select("[data-filter=fhdTop]").remove()
        val source = Source(doc.html(), fragment)
        val result = CatalogRepository(folder.newFolder(), source).best(day, BestListFilters(switches = setOf(BestSwitch.FHD)))
        assertTrue(result.filters.switches.isEmpty())
        assertEquals(0, source.posts); assertEquals(1, source.gets)
    }

    @Test fun personal_switches_are_scoped_to_account_and_guest_fields_omit_them() {
        val stored = mutableMapOf<String, String>()
        val preferences = ListFilterPreferences(stored::get) { key, value -> stored[key] = value }
        var owner: String? = "one"
        val source = BestListFilterSource(CatalogRepository(folder.newFolder(), Source(html, fragment)), preferences) { owner }
        source.save("public", BestListFilters("17", "7", setOf(BestSwitch.FHD, BestSwitch.HIDE_HISTORY)).values())
        owner = "two"
        assertEquals("true", source.restore("public")["fhd"])
        assertNull(source.restore("public")["history"])
        source.save("public", BestListFilters(switches = setOf(BestSwitch.HIDE_WATCHING)).values())
        owner = "one"
        assertEquals("true", source.restore("public")["history"])
        assertEquals("false", source.restore("public")["mark"])
        owner = null
        assertTrue(source.fields.none { field -> BestSwitch.entries.any { it.personal && it.id == field.id } })
        assertNull(source.restore("public")["history"])
        assertTrue(source.fields.filter { it.kind == ListFilterKind.TOGGLE }.single { it.id == "eng" }.defaultValue.toBoolean())
    }

    @Test fun guest_result_values_match_available_fields_and_private_session_is_not_written_to_disk() = runBlocking {
        val directory = folder.newFolder()
        val repository = CatalogRepository(directory, Source(html, fragment), cacheToken = { null })
        val source = BestListFilterSource(repository, ListFilterPreferences())
        val result = source.load(BestListFilters(switches = setOf(BestSwitch.FHD, BestSwitch.HIDE_HISTORY)).values())
        assertEquals(result.fields.map { it.id }.toSet(), result.values.keys)
        assertEquals("true", result.values["fhd"])
        assertNull(result.values["history"])
        assertTrue(directory.listFiles()!!.isEmpty())
    }
    @Test fun personal_filter_requires_authenticated_metadata_before_post() = runBlocking {
        val source = Source(html, fragment)
        val repository = CatalogRepository(folder.newFolder(), source)
        val selected = BestListFilters(switches = setOf(BestSwitch.HIDE_HISTORY))
        assertTrue(runCatching { repository.best(day, selected) }.exceptionOrNull() is ru.seasonvar.tv.data.account.SessionExpiredException)
        assertEquals(0, source.posts)
        var verified = false
        repository.best(day, selected) { verified = true; html }
        assertTrue(verified); assertEquals(1, source.posts)
        assertTrue(runCatching { repository.best(day, selected) { throw ru.seasonvar.tv.data.account.SessionExpiredException() } }.isFailure)
        assertEquals(1, source.posts)
    }

}
