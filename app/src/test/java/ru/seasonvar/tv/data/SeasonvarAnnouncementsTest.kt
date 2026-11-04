package ru.seasonvar.tv.data

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

class SeasonvarAnnouncementsTest {
    private val day = LocalDate.of(2026, 11, 4)
    private val now = day.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val html = javaClass.getResource("/announcements.html")!!.readText()

    @get:Rule val folder = TemporaryFolder()

    @Test fun real_announcements_keep_all_rows_dates_and_exact_season_links() {
        val entries = SeasonvarParser.announcements(html, day).entries
        val expected = "50606 50532 50812 50813 49897 50810 50808 50212 50809 49804 50132 49830 " +
            "50542 50248 50678 46775 50946 50945 50134 49832 46774 38830 50944 50045 " +
            "44783 50942 42249 50213 50817 49833 50681 42636 45479 50941 50686 50687"
        assertEquals(expected.split(" "), entries.map { it.id })
        val first = entries.first()
        assertEquals("Черные голуби", first.title)
        assertEquals("Black Doves", first.originalTitle)
        assertEquals(2, first.season)
        assertEquals("https://seasonvar.ru/serial-50606-CHernye_golubi-2-season.html", first.url)
        assertEquals("https://cdn.svstat.net/oblojka/50606.jpg", first.posterUrl)
        assertEquals("Ориентировочная дата выхода: 05.11.2026", first.update)
        assertEquals("05.11.2026", first.expectedReleaseDate)
        assertTrue(first.previewDescription!!.startsWith("Хелен Уэбб"))
        assertFalse(first.initialSelection.hasEpisode)
        assertEquals("14.07.2027", entries.last().expectedReleaseDate)
    }

    @Test fun uncertain_dates_and_missing_russian_titles_are_preserved() {
        val document = Jsoup.parse(html)
        val info = document.selectFirst(".pgs-search-info")!!
        info.select("a").first()!!.text("")
        info.selectFirst("span")!!.text("Дата уточняется")
        val entry = SeasonvarParser.announcements(document.outerHtml(), day).entries.first()
        assertEquals("Black Doves", entry.title)
        assertEquals("Дата уточняется", entry.expectedReleaseDate)
        assertEquals("Дата уточняется", entry.update)
        assertEquals(SeasonvarParser.announcements(html, day).entries.first().key, entry.key)
    }

    @Test fun blocked_changed_and_incomplete_markup_is_not_a_valid_empty_list() {
        val missingDate = Jsoup.parse(html)
        missingDate.selectFirst(".pgs-search-info span")!!.remove()
        val unsafeLink = Jsoup.parse(html)
        unsafeLink.selectFirst("a.pst")!!.attr("href", "https://other.example/serial-50606.html")
        for (page in listOf("<h1>Blocked</h1>", html.replace("Скоро на сайте", "Топ сериалов"),
                missingDate.outerHtml(), unsafeLink.outerHtml())) {
            assertThrows(MarkupChangedException::class.java) {
                SeasonvarParser.announcements(page, day)
            }
        }
    }

    @Test fun a_recognized_page_without_rows_is_an_empty_list() {
        val document = Jsoup.parse(html)
        document.select(".pgs-search-wrap").remove()
        assertTrue(SeasonvarParser.announcements(document.outerHtml(), day).entries.isEmpty())
    }

    @Test fun page_request_and_cache_are_independent_and_bad_refresh_keeps_saved_announcements(): Unit = runBlocking {
        val dir = folder.newFolder()
        val calls = mutableListOf<String>()
        var broken = false
        val repository = CatalogRepository(dir, object : HtmlSource {
            override suspend fun get(url: String): String {
                calls += url
                return when (url) {
                    "https://seasonvar.ru/?mode=soon" -> if (broken) "<h1>Blocked</h1>" else html
                    "https://seasonvar.ru/?mode=top" -> javaClass.getResource("/best.html")!!.readText()
                    else -> error("Unexpected GET: $url")
                }
            }
            override suspend fun sidebar(feed: PublicFeed): String = error("Unexpected AJAX request")
        }, ZoneOffset.UTC, { now })
        assertEquals(36, repository.load(day, PublicFeed.ANNOUNCEMENTS).entries.size)
        assertEquals(100, repository.load(day, PublicFeed.BEST).entries.size)
        assertEquals(listOf("https://seasonvar.ru/?mode=soon", "https://seasonvar.ru/?mode=top"), calls)
        val saved = File(dir, "announcements.html")
        assertTrue(saved.setLastModified(now - 23 * 60 * 60 * 1000))
        assertEquals(36, repository.cached(day, PublicFeed.ANNOUNCEMENTS)!!.entries.size)
        broken = true
        try { repository.load(day, PublicFeed.ANNOUNCEMENTS); fail("Expected markup error") }
        catch (_: MarkupChangedException) { }
        assertEquals(html, saved.readText())
        assertTrue(saved.setLastModified(now - 24 * 60 * 60 * 1000))
        assertNull(repository.cached(day, PublicFeed.ANNOUNCEMENTS))
        assertTrue(File(dir, "best.html").isFile)
        assertThrows(IllegalArgumentException::class.java) {
            SiteClient.sidebarRequest(PublicFeed.ANNOUNCEMENTS)
        }
    }
}
