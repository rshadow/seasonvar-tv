package ru.seasonvar.tv.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

class SeasonvarTopTest {
    private val day = LocalDate.of(2026, 11, 2)
    private val now = day.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val top = javaClass.getResource("/top-ajax.html")!!.readText()
    private val updates = javaClass.getResource("/updates-ajax.html")!!.readText()

    @get:Rule val folder = TemporaryFolder()

    @Test fun real_top_response_preserves_site_order_and_card_metadata() {
        val entries = SeasonvarParser.sidebar(top, day).entries
        val expected = "50940 51009 50928 49673 50883 51053 50682 50688 50841 50654 " +
            "50601 50670 50884 51006 50680 50785 51050 34141 50885 49799 " +
            "51042 51051 44279 50683 50653 41401 50537 50949 50051 49829 " +
            "50947 51056 49500 51058 44822 51008 43966 50729 47588 50605 " +
            "51044 43504 50169 50050 50846 51020 50241 50491 50778"
        assertEquals(expected.split(" "), entries.map { it.id })
        assertEquals("Тверская", entries.first().title)
        assertEquals("Тверская. Здесь и сейчас", entries.first().originalTitle)
        assertEquals(3, entries.first().season)
        assertEquals("19-20 серия", entries.first().update)
        assertEquals("https://seasonvar.ru/serial-50940-Tverskaya-3-season.html", entries.first().url)
        assertEquals("https://cdn.svstat.net/oblojka/50940.jpg", entries.first().posterUrl)
        assertFalse(entries.first().isNew)
        assertTrue(entries[1].isNew)
    }

    @Test fun top_request_uses_its_own_ajax_mode_and_the_same_form() {
        val request = SiteClient.sidebarRequest(PublicFeed.TOP)
        assertEquals("POST", request.method)
        assertEquals("https://seasonvar.ru/ajax.php?mode=pop", request.url.toString())
        assertEquals("XMLHttpRequest", request.header("X-Requested-With"))
        assertEquals("https://seasonvar.ru/", request.header("Referer"))
        val buffer = okio.Buffer()
        request.body!!.writeTo(buffer)
        assertEquals("ganre=&country=&block=0&main=1", buffer.readUtf8())
        assertThrows(IllegalArgumentException::class.java) { SiteClient.sidebarRequest(PublicFeed.TODAY) }
    }

    @Test fun top_and_updates_load_without_homepage_and_use_separate_caches() = runBlocking {
        val dir = folder.newFolder()
        val calls = mutableListOf<PublicFeed>()
        val repository = CatalogRepository(dir, object : HtmlSource {
            override suspend fun get(url: String): String = error("Unexpected homepage GET")
            override suspend fun sidebar(feed: PublicFeed): String {
                calls += feed
                return when (feed) {
                    PublicFeed.TOP -> top
                    PublicFeed.UPDATES -> updates
                    PublicFeed.TODAY, PublicFeed.NEWEST -> error("Unexpected feed")
                }
            }
        }, ZoneOffset.UTC, { now })
        assertEquals("50940", repository.load(day, PublicFeed.TOP).entries.first().id)
        assertEquals("51072", repository.load(day, PublicFeed.UPDATES).entries.first().id)
        assertEquals(listOf(PublicFeed.TOP, PublicFeed.UPDATES), calls)
        assertTrue(File(dir, "top-ajax.html").setLastModified(now))
        assertTrue(File(dir, "updates-ajax.html").setLastModified(now))
        assertEquals("50940", repository.cached(day, PublicFeed.TOP)!!.entries.first().id)
        assertEquals("51072", repository.cached(day, PublicFeed.UPDATES)!!.entries.first().id)
        assertFalse(File(dir, "today.html").exists())
    }

    @Test fun top_cache_survives_midnight_and_bad_responses_but_expires_after_24_hours() = runBlocking {
        val dir = folder.newFolder()
        val file = File(dir, "top-ajax.html").apply {
            writeText(top)
            setLastModified(now - 13 * 60 * 60 * 1000)
        }
        val repository = CatalogRepository(dir, object : HtmlSource {
            override suspend fun get(url: String): String = error("Unexpected homepage GET")
            override suspend fun sidebar(feed: PublicFeed): String = "<h1>Blocked</h1>"
        }, ZoneOffset.UTC, { now })
        assertEquals(49, repository.cached(day, PublicFeed.TOP)!!.entries.size)
        try { repository.load(day, PublicFeed.TOP); fail("Expected a markup error") }
        catch (_: MarkupChangedException) { }
        assertEquals(top, file.readText())
        file.setLastModified(now - 24 * 60 * 60 * 1000)
        assertNull(repository.cached(day, PublicFeed.TOP))
    }
}
