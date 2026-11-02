package ru.seasonvar.tv.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

class SeasonvarNewestTest {
    private val day = LocalDate.of(2026, 11, 2)
    private val now = day.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val newest = javaClass.getResource("/newest-ajax.html")!!.readText()

    @get:Rule val folder = TemporaryFolder()

    @Test fun real_newest_response_preserves_order_metadata_and_site_badges() {
        val entries = SeasonvarParser.sidebar(newest, day).entries
        val expected = "51092 51091 51090 51089 51087 51086 51085 51083 51082 51081 " +
            "51080 51079 51078 51077 51076 51075 51074 51073 51072 51071 " +
            "51070 51069 51068 51067 51066 51065 51064 51063 51062 51061 " +
            "51060 51059 51058 51057 51056 51055 51054 51053 51052 51051 " +
            "51050 51049 51048 51047 51046 51045 51044 51043 51042 51041"
        assertEquals(expected.split(" "), entries.map { it.id })
        val first = entries.first()
        assertEquals("Другие (Бразилия)", first.title)
        assertEquals("Os Outros", first.originalTitle)
        assertEquals(1, first.season)
        assertEquals("1-12 серия", first.update)
        assertEquals("https://seasonvar.ru/serial-51092-Drugie_Braziliya-1-season.html", first.url)
        assertEquals("https://cdn.svstat.net/oblojka/51092.jpg", first.posterUrl)
        assertFalse(first.isNew)
        assertEquals(27, entries.count { it.isNew })
    }

    @Test fun newest_request_uses_its_own_ajax_mode_and_common_form() {
        val request = SiteClient.sidebarRequest(PublicFeed.NEWEST)
        assertEquals("POST", request.method)
        assertEquals("https://seasonvar.ru/ajax.php?mode=newest", request.url.toString())
        assertEquals("XMLHttpRequest", request.header("X-Requested-With"))
        assertEquals("https://seasonvar.ru/", request.header("Referer"))
        val buffer = okio.Buffer()
        request.body!!.writeTo(buffer)
        assertEquals("ganre=&country=&block=0&main=1", buffer.readUtf8())
    }

    @Test fun newest_cache_is_independent_expires_and_survives_bad_responses() = runBlocking {
        val dir = folder.newFolder()
        val pages = mapOf(
            PublicFeed.NEWEST to newest,
            PublicFeed.TOP to javaClass.getResource("/top-ajax.html")!!.readText(),
            PublicFeed.UPDATES to javaClass.getResource("/updates-ajax.html")!!.readText(),
        )
        val names = mapOf(PublicFeed.NEWEST to "newest-ajax.html",
            PublicFeed.TOP to "top-ajax.html", PublicFeed.UPDATES to "updates-ajax.html")
        var broken = false
        val calls = mutableListOf<PublicFeed>()
        val repository = CatalogRepository(dir, object : HtmlSource {
            override suspend fun get(url: String): String = error("Unexpected homepage GET")
            override suspend fun sidebar(feed: PublicFeed): String {
                calls += feed
                return if (broken) "<h1>Blocked</h1>" else pages.getValue(feed)
            }
        }, ZoneOffset.UTC, { now })
        for (feed in pages.keys) {
            repository.load(day, feed)
            assertTrue(File(dir, names.getValue(feed)).setLastModified(now))
        }
        assertEquals(pages.keys.toList(), calls)
        val cached = File(dir, "newest-ajax.html")
        assertTrue(cached.setLastModified(now - 13 * 60 * 60 * 1000))
        assertEquals("51092", repository.cached(day, PublicFeed.NEWEST)!!.entries.first().id)
        broken = true
        try { repository.load(day, PublicFeed.NEWEST); fail("Expected a markup error") }
        catch (_: MarkupChangedException) { }
        assertEquals(newest, cached.readText())
        assertTrue(cached.setLastModified(now - 24 * 60 * 60 * 1000))
        assertNull(repository.cached(day, PublicFeed.NEWEST))
        assertEquals("50940", repository.cached(day, PublicFeed.TOP)!!.entries.first().id)
        assertEquals("51072", repository.cached(day, PublicFeed.UPDATES)!!.entries.first().id)
        assertFalse(File(dir, "today.html").exists())
    }
}
