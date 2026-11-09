package ru.seasonvar.tv.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl

class BestListFiltersTest {
    @get:Rule val folder = TemporaryFolder()
    private val day = LocalDate.of(2026, 11, 8)
    private val html = javaClass.getResource("/best-filter-options.html")!!.readText()
    private fun page(filters: BestListFilters) = html.replace(".per[value=1]", ".per[value=${filters.period}]")
        .replace("<script>", if (filters.genre.isEmpty()) "<script>" else "<script>$(\".gen option[value=${filters.genre}]\").prop('selected', true);")

    @Test fun effective_selection_uses_server_script_and_preserves_the_full_catalog_model() {
        val selected = BestListFilters("17", "7")
        val result = BestFilterParser.parse(page(selected), day, selected)
        assertEquals(selected, result.filters)
        assertEquals(listOf("", "17", "18"), result.options.genres.map { it.value })
        assertEquals(listOf("1", "7", "31"), result.options.periods.map { it.value })
        assertEquals("Тест", result.page.entries.single().title)
        assertEquals("2 сезона", result.page.entries.single().seasonCountLabel)
        assertThrows(MarkupChangedException::class.java) { BestFilterParser.parse(page(selected), day, BestListFilters("18", "7")) }
        assertThrows(MarkupChangedException::class.java) { BestFilterParser.parse(page(selected), day, BestListFilters("17", "31")) }
    }

    @Test fun missing_or_ambiguous_controls_do_not_silently_use_defaults() {
        for (bad in listOf(html.replace("value=1", "value=999"), html.replace("class=\"filter gen\"", "class=\"other\""),
            html.replace("value=\"31\"", "value=\"7\""), html.replace("</script>", "$(\".per[value=7]\").prop('checked', true);</script>"))) {
            assertThrows(MarkupChangedException::class.java) { BestFilterParser.parse(bad, day, BestListFilters()) }
        }
    }

    @Test fun cache_is_separate_for_every_combination_and_failed_load_does_not_replace_good_response() = runBlocking {
        val requests = mutableListOf<String>()
        var fail = false
        val directory = folder.newFolder()
        val repository = CatalogRepository(directory, object : HtmlSource {
            override suspend fun get(url: String): String {
                requests += url
                if (fail) throw IOException()
                val parsed = url.toHttpUrl()
                return page(BestListFilters(parsed.queryParameter("genre").orEmpty(), parsed.queryParameter("period") ?: "1"))
            }
            override suspend fun sidebar(feed: PublicFeed): String = error("Wrong feed")
        })
        for (filters in listOf(BestListFilters(), BestListFilters("17", "7"), BestListFilters("18", "31"), BestListFilters("", "7"))) {
            assertNull(repository.cachedBest(day, filters))
            repository.best(day, filters)
            assertEquals(filters, repository.cachedBest(day, filters)!!.filters)
        }
        assertEquals(4, requests.size)
        assertEquals(4, directory.listFiles()!!.size)
        fail = true
        assertTrue(runCatching { repository.best(day, BestListFilters("17", "7")) }.isFailure)
        assertEquals("17", repository.cachedBest(day, BestListFilters("17", "7"))!!.filters.genre)
        assertEquals("", repository.cached(day, PublicFeed.BEST)!!.entries.single().update)
    }

    @Test fun removed_genre_is_normalized_and_untrusted_preferences_cannot_escape_cache_directory() = runBlocking {
        val requests = mutableListOf<String>()
        val repository = CatalogRepository(folder.newFolder(), object : HtmlSource {
            override suspend fun get(url: String): String { requests += url; return html }
            override suspend fun sidebar(feed: PublicFeed): String = error("Wrong feed")
        })
        assertEquals(BestListFilters(), repository.best(day, BestListFilters("999", "1")).filters)
        assertEquals(listOf("https://seasonvar.ru/?mode=top&genre=999&period=1", "https://seasonvar.ru/?mode=top&period=1"), requests)
        assertEquals(BestListFilters(), repository.best(day, BestListFilters("../../x", "99")).filters)
        assertFalse(requests.last().contains("genre="))
    }

    @Test fun session_cache_token_change_prevents_writing_a_response_for_a_new_account() = runBlocking {
        var token = 1L
        val directory = folder.newFolder()
        val repository = CatalogRepository(directory, object : HtmlSource {
            override suspend fun get(url: String): String { token++; return html }
            override suspend fun sidebar(feed: PublicFeed): String = error("Wrong feed")
        }, cacheToken = { token })
        assertTrue(runCatching { repository.best(day, BestListFilters()) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertTrue(directory.listFiles()!!.isEmpty())
    }
}
