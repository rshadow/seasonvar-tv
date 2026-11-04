package ru.seasonvar.tv.data

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class SeasonvarWatchingTest {
    private val day = LocalDate.of(2026, 11, 4)
    private val updates = javaClass.getResource("/updates-ajax.html")!!.readText()

    @get:Rule val folder = TemporaryFolder()

    @Test fun today_reads_site_watching_icon_without_affecting_title_or_selection_key() {
        val plain = """
            <div class="news"><div class="news-head">04.11.2026</div>
            <a href="/serial-100-Test.html" data-id="100"><div class="news-w">
            <div class="news_n">Название <span>/ Original</span></div>
            (1 сезон)<span class="news_s">2 серия</span></div></a></div>
        """.trimIndent()
        val marked = plain.replace("Название", "<i class='svico-mwatch'></i>Название")
        val guest = SeasonvarParser.today(plain, day).entries.single()
        val entry = SeasonvarParser.today(marked, day).entries.single()
        assertFalse(guest.isWatching)
        assertTrue(entry.isWatching)
        assertEquals(guest.title, entry.title)
        assertEquals(guest.originalTitle, entry.originalTitle)
        assertEquals(guest.key, entry.key)
        assertFalse(SeasonvarParser.today(marked.replace("svico-mwatch", "svico-mwatched"), day).entries.single().isWatching)
    }

    @Test fun sidebar_badge_coexists_with_new_and_ignores_watched_or_tooltip_icons() {
        val document = Jsoup.parseBodyFragment(updates)
        val row = document.selectFirst("a.rside-p-new")!!
        val index = document.body().children().indexOf(row)
        val icon = row.prependElement("i").addClass("svico-mwatch")
        val entry = SeasonvarParser.sidebar(document.body().html(), day).entries[index]
        assertTrue(entry.isWatching)
        assertTrue(entry.isNew)
        assertEquals(SeasonvarParser.sidebar(updates, day).entries[index].key, entry.key)
        icon.attr("class", "svico-mwatched")
        assertFalse(SeasonvarParser.sidebar(document.body().html(), day).entries[index].isWatching)
        icon.attr("class", "svico-mwatch")
        row.appendElement("div").addClass("rside-d").appendChild(icon)
        assertFalse(SeasonvarParser.sidebar(document.body().html(), day).entries[index].isWatching)
    }

    @Test fun page_lists_read_the_same_site_marker_without_changing_other_records() {
        for (resource in listOf("/best.html", "/announcements.html")) {
            val document = Jsoup.parse(javaClass.getResource(resource)!!.readText())
            document.selectFirst(".pgs-search-info")!!.prependElement("i").addClass("svico-mwatch")
            val page = if (resource == "/best.html") SeasonvarParser.best(document.outerHtml(), day)
                else SeasonvarParser.announcements(document.outerHtml(), day)
            assertTrue(page.entries.first().isWatching)
            assertTrue(page.entries.drop(1).none { it.isWatching })
        }
    }

    @Test fun search_preserves_a_watching_icon_from_name_html_but_cleans_the_display_name() {
        val json = JSONObject().put("query", "Тест")
            .put("suggestions", JSONObject().put("valu", JSONArray().put(
                "<i class='svico-mwatch'></i>Название / Original (1 сезон)")))
            .put("data", JSONArray().put("serial-100-Test.html"))
            .put("id", JSONArray().put("100"))
        val entry = SeasonvarParser.search(json.toString(), "Тест").single()
        assertTrue(entry.isWatching)
        assertEquals("Название", entry.title)
        assertEquals("Original", entry.originalTitle)
    }

    @Test fun refreshed_mark_disappears_and_private_markup_is_never_saved(): Unit = runBlocking {
        val directory = folder.newFolder()
        val document = Jsoup.parseBodyFragment(updates)
        document.selectFirst("a.rside-p")!!.prependElement("i").addClass("svico-mwatch")
        var watched = true
        var calls = 0
        val repository = CatalogRepository(directory, object : HtmlSource {
            override suspend fun get(url: String): String = error("Unexpected request")
            override suspend fun sidebar(feed: PublicFeed): String {
                assertEquals(PublicFeed.UPDATES, feed)
                calls++
                return if (watched) document.body().html() else updates
            }
        }, cacheToken = { null })
        val marked = repository.load(day, PublicFeed.UPDATES).entries.first()
        assertTrue(marked.isWatching)
        assertFalse(File(directory, "updates-ajax.html").exists())
        assertNull(repository.cached(day, PublicFeed.UPDATES))
        watched = false
        val unmarked = repository.load(day, PublicFeed.UPDATES).entries.first()
        assertFalse(unmarked.isWatching)
        assertEquals(marked.key, unmarked.key)
        assertEquals(2, calls)
    }
}
