package ru.seasonvar.tv.data

import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

class BrowseCatalogTest {
    @get:Rule val folder = TemporaryFolder()
    private val day = LocalDate.of(2026, 11, 10)
    private val fragment = """<div class="lside-serial" data-serial="list"><div hidden data-tabr="letter26">
        <a data-id="123" href="/serial-123-Test-6-season.html">Тест &amp; сериал</a>
        <a data-id="122" href="/serial-122-Second.html">Второй</a></div></div>"""
    private val metadata = listOf("country", "genre", "year", "seasc", "raiting", "golosa", "sort").joinToString("") { field ->
        val values = when (field) { "sort" -> listOf("name", "newest"); "country" -> listOf("all", "Япония")
            else -> listOf("all", "1", "2") }
        "<select data-filter='$field'>" + values.joinToString("") { "<option value='$it'>$it</option>" } + "</select>"
    }

    @Test fun streamed_result_preserves_order_exact_season_and_decodes_text() = runBlocking {
        val page = BrowseCatalogParser.page(fragment, day)
        assertEquals(listOf("123", "122"), page.entries.map { it.id })
        assertEquals("Тест & сериал", page.entries[0].title)
        assertEquals(6, page.entries[0].season)
        assertNull(page.entries[1].season)
        assertEquals("https://seasonvar.ru/serial-123-Test-6-season.html", page.entries[0].url)
        assertNull(page.entries[0].previewDescription)
        assertEquals(page, BrowseCatalogParser.page(fragment + fragment, day))
    }

    @Test fun rejects_unrecognized_empty_foreign_links_and_mismatched_ids() = runBlocking {
        for (html in listOf("", "<html>Blocked</html>", fragment.replace("/serial-123", "https://evil.test/serial-123"),
            fragment.replace("data-id=\"123\"", "data-id=\"999\""))) {
            try { BrowseCatalogParser.page(html, day); fail("Accepted invalid catalog") } catch (_: MarkupChangedException) { }
        }
        assertTrue(BrowseCatalogParser.page("<div class='pgs-msg'>Под заданный фильтр<br>ничего не подходит</div>", day).entries.isEmpty())
    }

    @Test fun full_catalog_is_parsed_without_a_dom_of_all_entries() = runBlocking {
        val html = "<div class='lside-serial' data-serial='list'><div hidden>" + (1..34_000).joinToString("") {
            "<a data-id='$it' href='/serial-$it-Test.html'>Сериал $it</a>"
        } + "</div></div>"
        val page = BrowseCatalogParser.page(html, day)
        assertEquals(34_000, page.entries.size)
        assertEquals("34000", page.entries.last().id)
    }

    @Test fun form_keeps_multiple_genres_and_uses_original_site_protocol() {
        val values = CatalogFilters(mapOf("genres" to "2,1", "excludedGenres" to "3", "country" to "Япония",
            "countryNot" to "true", "year" to "2024", "yearOp" to ">", "rating" to "8", "ratingSource" to "imdb", "mark" to "true"))
        val request = SiteClient.catalogRequest(values)
        val form = request.body as FormBody
        val pairs = (0 until form.size).map { form.name(it) to form.value(it) }
        assertEquals(listOf("1", "2"), pairs.filter { it.first == "filter[quotG][]" }.map { it.second })
        assertTrue(pairs.containsAll(listOf("filter[notQuotC][]" to "Япония", "filter[moreY][]" to "2024",
            "filter[sortTo][]" to "newest", "filter[rait]" to "imdb", "filter[mark]" to "yes")))
        assertTrue(pairs.none { it.first == "countFilter" })
        assertTrue(pairs.filter { it.first in setOf("filter[engName]", "filter[block]", "filter[exp]") }.all { it.second.isEmpty() })
    }

    @Test fun fresh_options_validate_saved_values_and_strip_private_guest_conditions() {
        val options = BrowseCatalogParser.options(metadata)
        val selected = options.normalize(CatalogFilters(mapOf("country" to "obsolete", "genres" to "1,2,999", "excludedGenres" to "1,2",
            "sort" to "old", "mark" to "true", "yearOp" to "?")), false)
        assertEquals("all", selected["country"])
        assertEquals(setOf("1", "2"), selected.genres("genres"))
        assertTrue(selected.genres("excludedGenres").isEmpty())
        assertEquals("newest", selected["sort"])
        assertEquals("false", selected["mark"])
        assertEquals("=", selected["yearOp"])
    }

    @Test fun html_cache_uses_filter_and_sort_identity_and_survives_new_repository() = runBlocking {
        var posts = 0
        val source = object : HtmlSource {
            override suspend fun get(url: String) = metadata
            override suspend fun sidebar(feed: PublicFeed) = error("Wrong source")
            override suspend fun catalog(filters: CatalogFilters): String { posts++; return fragment }
        }
        val directory = folder.newFolder()
        val repository = CatalogRepository(directory, source)
        val filters = repository.catalogOptions().normalize(CatalogFilters(), false)
        assertEquals(2, repository.catalog(filters).entries.size)
        val restored = CatalogRepository(directory, source)
        assertEquals(2, restored.cachedCatalog(filters)!!.entries.size)
        assertNotNull(restored.cachedCatalogOptions())
        assertNull(restored.cachedCatalog(filters.with("sort", "name")))
        assertEquals(1, posts)
    }
}
