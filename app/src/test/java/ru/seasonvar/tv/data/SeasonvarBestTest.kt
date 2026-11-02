package ru.seasonvar.tv.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

class SeasonvarBestTest {
    private val day = LocalDate.of(2026, 11, 2)
    private val now = day.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val page = javaClass.getResource("/best.html")!!.readText()

    @get:Rule val folder = TemporaryFolder()

    @Test fun best_page_preserves_all_100_positions_and_separates_season_count() {
        val entries = SeasonvarParser.best(page, day).entries
        val expected = "24676 34141 45609 51009 39185 13464 43504 50883 42133 50654 " +
            "28174 51050 43058 106 97 41723 41264 58 17423 50688 " +
            "51074 151 50884 51075 33148 39695 23825 51006 40539 50841 " +
            "33733 50604 51041 44566 16979 37608 51077 3243 44333 50680 " +
            "32427 50804 51073 39508 380 51080 50885 31845 415 51059 " +
            "50785 51066 27975 42902 50676 33095 50949 50670 46080 50050 " +
            "31208 1026 33101 50947 51020 40496 49803 51051 51065 34240 " +
            "50497 13610 51064 85 51008 22363 178 50740 51042 42255 " +
            "247 42587 394 11251 41353 12 50846 50921 50865 1000 " +
            "43227 35592 33632 2219 19663 46020 49834 45480 5097 49169"
        assertEquals(expected.split(" "), entries.map { it.id })
        val first = entries.first()
        assertEquals("Летние каникулы", first.title)
        assertEquals("Summer Vacation", first.originalTitle)
        assertEquals("https://seasonvar.ru/serial-24676-Letnie_kanikuly_psobnhq.html", first.url)
        assertEquals("https://cdn.svstat.net/oblojka/24676.jpg", first.posterUrl)
        assertTrue(first.previewDescription!!.startsWith("Лето"))
        assertNull(first.seasonCountLabel)
        assertEquals("3 сезона", entries[1].seasonCountLabel)
        assertEquals("3 сезона", entries[1].subtitle)
        assertNull(entries[1].season)
        assertEquals("", entries[1].update)
        assertTrue(entries.none { it.isNew })
    }

    @Test fun original_name_and_missing_preview_are_supported_without_guessing_a_season() {
        val html = page.replace("Летние каникулы", " ")
            .replace(Regex("<p>.*?</p>"), "")
        val first = SeasonvarParser.best(html, day).entries.first()
        assertEquals("Summer Vacation", first.title)
        assertEquals("Summer Vacation", first.fullTitle)
        assertNull(first.previewDescription)
        assertNull(first.season)
        assertEquals("", first.subtitle)
    }

    @Test fun blocked_or_changed_best_page_and_external_links_are_not_empty_results() {
        for (bad in listOf(
            "<h1>Blocked</h1>",
            page.replace("Топ сериалов", "Другая страница"),
            page.replace("pgs-search-info", "changed-info"),
            page.replace("/serial-24676-Letnie_kanikuly_psobnhq.html", "https://example.org/serial-24676-Test.html"),
            page.replaceFirst("/serial-24676-Letnie_kanikuly_psobnhq.html", "/serial-999-Other.html"),
        )) {
            assertThrows(MarkupChangedException::class.java) { SeasonvarParser.best(bad, day) }
        }
        val empty = """<div class="content"><div class="content-wrap"><span class="pgs-head">Топ сериалов</span>
            <div class="pgs-search"><div id="search-res"></div></div></div></div>"""
        assertTrue(SeasonvarParser.best(empty, day).entries.isEmpty())
    }

    @Test fun best_uses_its_own_get_cache_and_retains_good_data_on_failure() = runBlocking {
        val dir = folder.newFolder()
        var broken = false
        val requests = mutableListOf<String>()
        val repository = CatalogRepository(dir, object : HtmlSource {
            override suspend fun get(url: String): String {
                assertEquals("https://seasonvar.ru/?mode=top", url)
                requests += url
                return if (broken) "<h1>Blocked</h1>" else page
            }
            override suspend fun sidebar(feed: PublicFeed): String {
                assertEquals(PublicFeed.TOP, feed)
                return javaClass.getResource("/top-ajax.html")!!.readText()
            }
        }, ZoneOffset.UTC, { now })
        assertEquals(100, repository.load(day, PublicFeed.BEST).entries.size)
        repository.load(day, PublicFeed.TOP)
        val cached = File(dir, "best.html")
        assertTrue(cached.setLastModified(now - 13 * 60 * 60 * 1000))
        assertTrue(File(dir, "top-ajax.html").setLastModified(now))
        assertEquals(100, repository.cached(day, PublicFeed.BEST)!!.entries.size)
        broken = true
        try { repository.load(day, PublicFeed.BEST); fail("Expected a markup error") }
        catch (_: MarkupChangedException) { }
        assertEquals(page, cached.readText())
        assertEquals(2, requests.size)
        assertTrue(cached.setLastModified(now - 24 * 60 * 60 * 1000))
        assertNull(repository.cached(day, PublicFeed.BEST))
        assertEquals(49, repository.cached(day, PublicFeed.TOP)!!.entries.size)
        assertFalse(File(dir, "today.html").exists())
    }
}
