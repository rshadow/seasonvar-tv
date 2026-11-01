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
        <ul>
        <li data-tabr="rside-new" data-tabgr="rightside">
          <a class="pst rside-p" href="/serial-100-First.html">
            <div class="rside-t">Первый &amp; второй</div>
            <div class="rside-t_en">First &amp; Second</div>
            <div class="rside-ss">Сезон 2<br><span>6 серия (Студия)</span></div>
          </a>
          <a class="pst rside-p rside-p-new" href="/serial-200-Second.html">
            <div class="rside-t">Другой сериал</div>
            <div class="rside-ss"><span>10-12 серия</span></div>
          </a>
        </li>
        <li data-tabr="rside-pop" data-tabgr="rightside">
          <a class="pst rside-p" href="/serial-300-Top.html">
            <div class="rside-t">Не из обновлений</div>
            <div class="rside-ss"><span>1 серия</span></div>
          </a>
        </li></ul>
    """.trimIndent()

    @get:Rule val folder = TemporaryFolder()

    @Test fun reads_only_updates_in_site_order_with_season_and_original_title() {
        val entries = SeasonvarParser.updates(page, day).entries
        assertEquals(listOf("100", "200"), entries.map { it.id })
        assertEquals("Первый & второй / First & Second", entries[0].title)
        assertEquals(2, entries[0].season)
        assertEquals("6 серия (Студия)", entries[0].update)
        assertEquals("https://seasonvar.ru/serial-100-First.html", entries[0].url)
        assertEquals("https://cdn.svstat.net/oblojka/100.jpg", entries[0].posterUrl)
        assertNull(entries[1].season)
        assertEquals("Другой сериал", entries[1].title)
        assertEquals(SeasonvarParser.updates(page, day).entries,
            SeasonvarParser.updates(page, day.plusDays(1)).entries)
    }

    @Test fun exact_duplicate_is_not_duplicated() {
        val first = page.substringAfter("<li data-tabr=\"rside-new\" data-tabgr=\"rightside\">")
            .substringBefore("</li>")
        val repeated = page.replace("</li>", first + "</li>")
        assertEquals(2, SeasonvarParser.updates(repeated, day).entries.size)
    }

    @Test fun different_updates_of_the_same_season_are_preserved() {
        val extra = """
            <a class="pst rside-p" href="/serial-100-First.html">
              <div class="rside-t">Первый &amp; второй</div>
              <div class="rside-ss">Сезон 2<span>6 серия (Другая студия)</span></div>
            </a>
        """
        val entries = SeasonvarParser.updates(page.replace("</li>", extra + "</li>"), day).entries
        assertEquals(3, entries.size)
        assertEquals(2, entries.count { it.id == "100" })
        assertEquals(3, entries.map { it.key }.distinct().size)
    }

    @Test fun valid_empty_updates_are_distinct_from_changed_or_blocked_markup() {
        assertTrue(SeasonvarParser.updates(
            "<li data-tabr='rside-new' data-tabgr='rightside'></li>", day).entries.isEmpty())
        for (invalid in listOf(
            "<h1>Blocked</h1>",
            "<li data-tabr='rside-new' data-tabgr='rightside'><div class='svload'></div></li>",
            page.replace("rside-t\"", "changed\""),
            page.replace("<span>6 серия (Студия)</span>", ""),
        )) {
            assertThrows(MarkupChangedException::class.java) { SeasonvarParser.updates(invalid, day) }
        }
    }

    @Test fun external_season_url_is_rejected() {
        assertThrows(MarkupChangedException::class.java) {
            SeasonvarParser.updates(page.replace("/serial-100-First.html",
                "https://example.org/serial-100-First.html"), day)
        }
    }

    @Test fun updates_cache_can_cross_midnight_but_expires_after_24_hours() = runBlocking {
        val dir = folder.newFolder()
        val file = File(dir, "updates.html").apply { writeText(page) }
        val repository = repository(dir)
        file.setLastModified(now - 13 * 60 * 60 * 1000)
        assertEquals(2, repository.cached(day, PublicFeed.UPDATES)?.entries?.size)
        file.setLastModified(now - 24 * 60 * 60 * 1000)
        assertNull(repository.cached(day, PublicFeed.UPDATES))
    }

    @Test fun today_and_updates_use_independent_cache_files() = runBlocking {
        val dir = folder.newFolder()
        val repository = repository(dir, page)
        repository.load(day, PublicFeed.UPDATES)
        assertEquals(page, File(dir, "updates.html").readText())
        assertFalse(File(dir, "today.html").exists())
        assertNull(repository.cached(day, PublicFeed.TODAY))
    }

    @Test fun broken_updates_do_not_replace_a_good_cache() = runBlocking {
        val dir = folder.newFolder()
        val cached = File(dir, "updates.html").apply { writeText(page) }
        try { repository(dir, "<h1>Blocked</h1>").load(day, PublicFeed.UPDATES); fail() }
        catch (_: MarkupChangedException) { }
        assertEquals(page, cached.readText())
    }

    private fun repository(directory: File, response: String? = null) =
        CatalogRepository(directory, object : HtmlSource {
            override suspend fun get(url: String): String = response ?: throw IOException("offline")
        }, ZoneOffset.UTC, { now })
}
