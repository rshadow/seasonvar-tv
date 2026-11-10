package ru.seasonvar.tv.data

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.account.AccountParser
import ru.seasonvar.tv.data.account.PersonalFeed
import java.time.LocalDate

class ListSourceParsingTest {
    private val day = LocalDate.of(2026, 11, 10)
    private suspend fun verify(entries: List<SeriesEntry>) {
        assertEquals(250, entries.size)
        val window = (entries as ParsedSeriesEntries).window!!
        window.advance(199)
        assertEquals(500, window.state.value.entries.size)
        window.advance(449)
        assertEquals(600, window.state.value.entries.size)
        assertFalse(window.state.value.hasMore)
        assertEquals(600, window.state.value.entries.map { it.key }.distinct().size)
    }
    @Test fun public_sidebar_uses_the_shared_parsing_window() = runBlocking {
        val html = (1..600).joinToString("") { """<a class="pst rside-p" href="/serial-$it-Test.html">
            <span class="rside-t">Сериал $it</span><span class="rside-ss">Сезон 1 <span>1 серия</span></span></a>""" }
        verify(SeasonvarParser.sidebar(html, day, windowed = true).entries)
        assertEquals(600, SeasonvarParser.sidebar(html, day).entries.size)
    }
    @Test fun personal_lists_use_the_same_window_and_keep_source_context() = runBlocking {
        val rows = (1..600).joinToString("") { """<div class="pgs-marks-el" data-marksel="$it">
            <a href="/serial-$it-Test.html"><span class="pgs-marks-name">Сериал $it (1 сезон)</span></a>
            <span class="pgs-marks-current">1 из 12</span></div>""" }
        val html = """<div class="headblock-menu"><a href="/profile/12">Тест</a></div>
            <div class="pgs-marks"><div data-tabr="marks-want">$rows</div></div>"""
        val page = AccountParser.personalList(html, PersonalFeed.WANT, windowed = true)
        assertNotNull(page.entries[0].accountProgress)
        verify(page.entries)
        assertEquals(600, AccountParser.personalList(html, PersonalFeed.WANT).entries.size)
    }
    @Test fun search_maps_native_records_in_batches_after_validating_parallel_arrays() = runBlocking {
        val html = JSONObject().put("query", "Тест").put("suggestions", JSONObject().put("valu", JSONArray((1..600).map { "Сериал $it" })))
            .put("data", JSONArray((1..600).map { "/serial-$it-Test.html" }))
            .put("id", JSONArray((1..600).map { it.toString() })).toString()
        verify(SeasonvarParser.search(html, "Тест", windowed = true))
        assertEquals(600, SeasonvarParser.search(html, "Тест").size)
    }
}
