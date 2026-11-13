package ru.seasonvar.tv.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate

class ActorPagesTest {
    private val html = javaClass.getResource("/actor-series.html")!!.readText()
    private val url = ActorPages.url("/actor/Тестовый актёр")!!
    private val day = LocalDate.of(2026, 11, 13)

    @Test fun preserves_server_order_exact_season_links_names_and_descriptions() {
        val page = ActorPages.parse(html, url, day)
        assertEquals(listOf("123", "456"), page.entries.map { it.id })
        val first = page.entries.first()
        assertEquals("https://seasonvar.ru/serial-123-test-3-season.html", first.url)
        assertEquals("Первый сериал", first.title)
        assertEquals("First Show", first.originalTitle)
        assertEquals("Описание первого сериала.", first.previewDescription)
        assertEquals("https://cdn.svstat.net/oblojka/123.jpg", first.posterUrl)
        assertNull(first.season)
        assertNull(first.seasonCountLabel)
    }

    @Test fun actor_identity_distinguishes_empty_results_from_an_unrelated_page() {
        val empty = "<span class='pgs-search-actor' href='/actor/Тестовый актёр'>Тест</span>"
        assertTrue(ActorPages.parse(empty, url, day).entries.isEmpty())
        for (invalid in listOf("<html>blocked</html>", empty.replace("Тестовый актёр", "Другой"),
            html.replace("/serial-123-test-3-season.html", "https://example.com/serial-123-test.html"))) {
            try { ActorPages.parse(invalid, url, day); fail("Expected markup rejection") }
            catch (_: MarkupChangedException) { }
        }
    }

    @Test fun accepts_only_actor_pages_and_keeps_names_as_path_segments() {
        assertEquals(url, ActorPages.url(url))
        assertEquals(url, ActorPages.url("http://www.seasonvar.ru/actor/Тестовый актёр#top"))
        for (invalid in listOf("", "/actor/", "/actor/a/b", "/serial-123-test.html",
            "https://example.com/actor/test", "https://user@seasonvar.ru/actor/test", "https://seasonvar.ru:8443/actor/test")) {
            assertNull(ActorPages.url(invalid))
        }
    }

    @Test fun parsing_uses_the_shared_window_instead_of_materializing_every_entry() = runBlocking {
        val item = html.substringAfter("<div class=\"pgs-search-wrap\">").substringBefore("<div class=\"pgs-search-wrap\">")
        val entries = (1..600).joinToString("") { "<div class=\"pgs-search-wrap\">" + item.replace("123", it.toString()) }
        val page = ActorPages.parse(html.substringBefore("<div class=\"pgs-search-wrap\">") + entries, url, day, true)
        val window = checkNotNull((page.entries as ParsedSeriesEntries).window)
        try {
            assertEquals(250, page.entries.size)
            window.advance(199)
            assertEquals(500, window.state.value.entries.size)
        } finally { window.close() }
    }

    @Test fun repository_reuses_the_shared_html_cache_without_another_request() = runBlocking {
        val directory = Files.createTempDirectory("actor-list-test").toFile()
        try {
            var calls = 0
            val source = object : HtmlSource {
                override suspend fun get(url: String): String { calls++; return html }
                override suspend fun sidebar(feed: PublicFeed): String = error("Unexpected request")
            }
            val first = CatalogRepository(directory, source)
            assertNull(first.cachedActorPage(url))
            assertEquals(2, first.actorPage(url).entries.size)
            val reopened = CatalogRepository(directory, source)
            assertEquals(listOf("123", "456"), reopened.cachedActorPage(url)!!.entries.map { it.id })
            assertEquals(1, calls)
        } finally { directory.deleteRecursively() }
    }
}
