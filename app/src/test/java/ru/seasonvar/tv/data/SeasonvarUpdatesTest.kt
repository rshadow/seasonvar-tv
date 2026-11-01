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

class SeasonvarUpdatesTest {
    private val day = LocalDate.of(2026, 11, 2)
    private val now = day.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val page = """
          <a class="pst rside-p" href="/serial-100-First.html">
            <div class="rside-t">Первый &amp; второй</div>
            <div class="rside-t_en">First &amp; Second</div>
            <div class="rside-ss">Сезон 2<br><span>6 серия (Студия)</span></div>
          </a>
          <a class="pst rside-p rside-p-new" href="/serial-200-Second.html">
            <div class="rside-t">Другой сериал</div>
            <div class="rside-ss"><span>10-12 серия</span></div>
          </a>
    """.trimIndent()

    @get:Rule val folder = TemporaryFolder()

    @Test fun reads_ajax_fragment_in_site_order_with_season_and_original_title() {
        val entries = SeasonvarParser.sidebar(page, day).entries
        assertEquals(listOf("100", "200"), entries.map { it.id })
        assertEquals("Первый & второй", entries[0].title)
        assertEquals("First & Second", entries[0].originalTitle)
        assertEquals("Первый & второй / First & Second", entries[0].fullTitle)
        assertEquals(2, entries[0].season)
        assertEquals("6 серия (Студия)", entries[0].update)
        assertEquals("https://seasonvar.ru/serial-100-First.html", entries[0].url)
        assertEquals("https://cdn.svstat.net/oblojka/100.jpg", entries[0].posterUrl)
        assertNull(entries[1].season)
        assertEquals("Другой сериал", entries[1].title)
        assertFalse(entries[0].isNew)
        assertTrue(entries[1].isNew)
        assertEquals(SeasonvarParser.sidebar(page, day).entries,
            SeasonvarParser.sidebar(page, day.plusDays(1)).entries)
    }

    @Test fun real_ajax_response_preserves_all_50_positions_in_site_order() {
        val html = javaClass.getResource("/updates-ajax.html")!!.readText()
        val entries = SeasonvarParser.sidebar(html, day).entries
        val expected = "51072 36145 50709 50570 50710 50996 50836 51067 47435 50713 " +
            "50990 50920 51055 50579 51081 51082 50392 50865 50906 51077 " +
            "51080 50987 50625 50749 50765 50921 50907 51043 50888 51078 " +
            "37364 51079 49711 49729 49353 51058 50934 50680 50531 20574 " +
            "49384 50743 44886 50711 50804 50921 50838 50994 50684 50363"
        assertEquals(expected.split(" "), entries.map { it.id })
        assertEquals(50, entries.map { it.key }.distinct().size)
        assertEquals(2, entries.count { it.id == "50921" })
        assertEquals("0 серия (RuDub)", entries.first().update)
        assertEquals(listOf("50990", "51055", "51081", "51082", "51077",
            "51080", "50987", "51043", "51079", "50994"),
            entries.filter { it.isNew }.map { it.id })
    }

    @Test fun original_title_is_used_only_when_russian_name_is_absent() {
        for (missing in listOf("", "<div class='rside-t'> </div>")) {
            val html = page.replace("<div class=\"rside-t\">Первый &amp; второй</div>", missing)
            val entry = SeasonvarParser.sidebar(html, day).entries.first()
            assertEquals("First & Second", entry.title)
            assertEquals("First & Second", entry.fullTitle)
        }
        val withSlash = page.replace("Первый &amp; второй", "Первый / Второй")
        assertEquals("Первый / Второй", SeasonvarParser.sidebar(withSlash, day).entries.first().title)
        assertThrows(MarkupChangedException::class.java) {
            SeasonvarParser.sidebar(page.replace("Первый &amp; второй", "").replace("First &amp; Second", ""), day)
        }
    }

    @Test fun new_badge_requires_the_exact_site_marker_not_the_episode_number() {
        val firstEpisode = page.replace("6 серия (Студия)", "1 серия")
        assertFalse(SeasonvarParser.sidebar(firstEpisode, day).entries.first().isNew)
        val similarClass = page.replace("rside-p-new", "rside-p-newish")
        assertTrue(SeasonvarParser.sidebar(similarClass, day).entries.none { it.isNew })
    }

    @Test fun exact_duplicate_is_not_duplicated() {
        assertEquals(2, SeasonvarParser.sidebar(page + page, day).entries.size)
    }

    @Test fun different_updates_of_the_same_season_are_preserved() {
        val extra = """
            <a class="pst rside-p" href="/serial-100-First.html">
              <div class="rside-t">Первый &amp; второй</div>
              <div class="rside-ss">Сезон 2<span>6 серия (Другая студия)</span></div>
            </a>
        """
        val entries = SeasonvarParser.sidebar(page + extra, day).entries
        assertEquals(3, entries.size)
        assertEquals(2, entries.count { it.id == "100" })
        assertEquals(3, entries.map { it.key }.distinct().size)
    }

    @Test fun valid_empty_updates_are_distinct_from_changed_or_blocked_markup() {
        assertTrue(SeasonvarParser.sidebar("  ", day).entries.isEmpty())
        for (invalid in listOf(
            "<h1>Blocked</h1>",
            "<div class='pgs-msg'>ошибка загрузки</div>",
            "<li data-tabr='rside-new' data-tabgr='rightside'>" + page + "</li>",
            page.replace("rside-t\"", "changed\"").replace("rside-t_en\"", "changed-en\""),
            page.replace("<span>6 серия (Студия)</span>", ""),
        )) {
            assertThrows(MarkupChangedException::class.java) { SeasonvarParser.sidebar(invalid, day) }
        }
    }

    @Test fun external_season_url_is_rejected() {
        assertThrows(MarkupChangedException::class.java) {
            SeasonvarParser.sidebar(page.replace("/serial-100-First.html",
                "https://example.org/serial-100-First.html"), day)
        }
    }

    @Test fun updates_cache_can_cross_midnight_but_expires_after_24_hours() = runBlocking {
        val dir = folder.newFolder()
        val file = File(dir, "updates-ajax.html").apply { writeText(page) }
        val repository = repository(dir)
        file.setLastModified(now - 13 * 60 * 60 * 1000)
        val entries = repository.cached(day, PublicFeed.UPDATES)!!.entries
        assertEquals(2, entries.size)
        assertTrue(entries[1].isNew)
        file.setLastModified(now - 24 * 60 * 60 * 1000)
        assertNull(repository.cached(day, PublicFeed.UPDATES))
    }

    @Test fun today_and_updates_use_independent_cache_files() = runBlocking {
        val dir = folder.newFolder()
        val repository = repository(dir, page)
        repository.load(day, PublicFeed.UPDATES)
        assertEquals(page, File(dir, "updates-ajax.html").readText())
        assertFalse(File(dir, "today.html").exists())
        assertNull(repository.cached(day, PublicFeed.TODAY))
    }

    @Test fun broken_updates_do_not_replace_a_good_cache() = runBlocking {
        val dir = folder.newFolder()
        val cached = File(dir, "updates-ajax.html").apply { writeText(page) }
        try { repository(dir, "<h1>Blocked</h1>").load(day, PublicFeed.UPDATES); fail() }
        catch (_: MarkupChangedException) { }
        assertEquals(page, cached.readText())
    }

    @Test fun old_homepage_cache_is_not_used_for_ajax_updates() = runBlocking {
        val dir = folder.newFolder()
        File(dir, "updates.html").writeText(page)
        assertNull(repository(dir).cached(day, PublicFeed.UPDATES))
    }

    @Test fun ajax_request_matches_the_site_form() {
        val request = SiteClient.sidebarRequest(PublicFeed.UPDATES)
        assertEquals("POST", request.method)
        assertEquals("https://seasonvar.ru/ajax.php?mode=new", request.url.toString())
        assertEquals("https://seasonvar.ru/", request.header("Referer"))
        assertEquals("XMLHttpRequest", request.header("X-Requested-With"))
        val body = request.body!!
        assertEquals("application/x-www-form-urlencoded", body.contentType().toString())
        val buffer = okio.Buffer()
        body.writeTo(buffer)
        assertEquals("ganre=&country=&block=0&main=1", buffer.readUtf8())
    }

    private fun repository(directory: File, response: String? = null) =
        CatalogRepository(directory, object : HtmlSource {
            override suspend fun get(url: String): String = error("Updates must not GET the homepage")
            override suspend fun sidebar(feed: PublicFeed): String {
                assertEquals(PublicFeed.UPDATES, feed)
                return response ?: throw IOException("offline")
            }
        }, ZoneOffset.UTC, { now })
}
