package ru.seasonvar.tv.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset

class SeasonvarParserTest {
    private val day = LocalDate.of(2026, 11, 1)
    private val page = """
        <div class="news"><div class="news-head">01.11.2026</div>
        <a href="/serial-100-Test.html" data-id="100">
          <div class="news-w"><div class="news_n">Сериал &amp; история <span>/ Story</span></div>
          (2 сезон)<span class="news_s">4-5 серия (Студия А)</span></div>
        </a></div>
        <div class="news"><div class="news-head">31.10.2026</div>
        <a href="/serial-200-Old.html" data-id="200">
          <div class="news-w"><div class="news_n">Вчерашний сериал</div>
          <span class="news_s">1 серия</span></div>
        </a></div>
    """.trimIndent()

    @get:Rule val folder = TemporaryFolder()

    @Test fun reads_only_requested_day_and_preserves_metadata() {
        val result = SeasonvarParser.today(page, day)
        assertEquals(1, result.entries.size)
        val item = result.entries.single()
        assertEquals("Сериал & история", item.title)
        assertEquals("Story", item.originalTitle)
        assertEquals("Сериал & история / Story", item.fullTitle)
        assertEquals(2, item.season)
        assertEquals("4-5 серия (Студия А)", item.update)
        assertEquals("https://seasonvar.ru/serial-100-Test.html", item.url)
        assertEquals("https://cdn.svstat.net/oblojka/100.jpg", item.posterUrl)
    }

    @Test fun original_title_is_used_when_today_has_no_russian_name() {
        val entry = SeasonvarParser.today(page.replace("Сериал &amp; история ", ""), day).entries.single()
        assertEquals("Story", entry.title)
        assertEquals("Story", entry.fullTitle)
    }

    @Test fun no_updates_for_requested_day_is_empty() {
        assertTrue(SeasonvarParser.today(page, day.plusDays(1)).entries.isEmpty())
    }

    @Test fun season_is_not_invented_when_it_is_missing() {
        assertNull(SeasonvarParser.today(page.replace("(2 сезон)", ""), day).entries.single().season)
    }

    @Test fun repeated_cards_have_one_stable_entry() {
        val duplicated = page + page
        assertEquals(1, SeasonvarParser.today(duplicated, day).entries.size)
    }

    @Test fun block_page_is_not_an_empty_result() {
        assertThrows(MarkupChangedException::class.java) {
            SeasonvarParser.today("<h1>Доступ ограничен</h1>", day)
        }
    }

    @Test fun invalid_date_is_a_contract_error() {
        assertThrows(MarkupChangedException::class.java) {
            SeasonvarParser.today(page.replace("01.11.2026", "31.02.2026"), day)
        }
    }

    @Test fun external_link_is_rejected() {
        assertThrows(MarkupChangedException::class.java) {
            SeasonvarParser.today(page.replace("/serial-100-Test.html", "https://example.org/serial-100-Test.html"), day)
        }
    }

    @Test fun missing_title_or_update_is_a_contract_error() {
        for (invalid in listOf(page.replace("class=\"news_n\"", "class=\"other\""),
            page.replace("class=\"news_s\"", "class=\"other\""))) {
            assertThrows(MarkupChangedException::class.java) { SeasonvarParser.today(invalid, day) }
        }
    }

    @Test fun description_belongs_to_requested_season_and_decodes_html() {
        val entry = SeasonvarParser.today(page, day).entries.single()
        val html = """
            <div class="pgs-sinfo" data-id-season="100"></div>
            <div class="pgs-sinfo-info"><p>Первая &amp; вторая.</p><p>Продолжение истории.</p></div>
        """
        assertEquals(listOf("Первая & вторая.", "Продолжение истории."),
            SeasonvarParser.description(html, entry).paragraphs)
        assertThrows(MarkupChangedException::class.java) {
            SeasonvarParser.description(html.replace("season=\"100\"", "season=\"200\""), entry)
        }
    }

    @Test fun cached_day_is_not_shown_as_another_day() = runBlocking {
        val dir = folder.newFolder()
        val file = File(dir, "today.html").apply { writeText(page) }
        file.setLastModified(day.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli())
        val repo = CatalogRepository(dir, object : HtmlSource {
            override suspend fun get(url: String): String = throw IOException("offline")
            override suspend fun sidebar(feed: PublicFeed): String = error("Unexpected AJAX request")
        }, ZoneOffset.UTC)
        assertEquals(1, repo.cached(day)?.entries?.size)
        assertNull(repo.cached(day.plusDays(1)))
    }

    @Test fun broken_network_response_does_not_replace_valid_cache() = runBlocking {
        val dir = folder.newFolder()
        val file = File(dir, "today.html").apply { writeText(page) }
        val repo = CatalogRepository(dir, object : HtmlSource {
            override suspend fun get(url: String) = "<h1>Blocked</h1>"
            override suspend fun sidebar(feed: PublicFeed): String = error("Unexpected AJAX request")
        })
        try { repo.load(day); fail("Expected a contract error") }
        catch (_: MarkupChangedException) { }
        assertEquals(page, file.readText())
    }
}
