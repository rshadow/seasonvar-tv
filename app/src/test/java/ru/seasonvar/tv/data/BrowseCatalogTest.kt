package ru.seasonvar.tv.data

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.seasonvar.tv.ui.catalog.BrowseCatalogViewModel
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun first_catalog_opening_waits_for_a_fresh_batch_when_an_old_snapshot_exists() = runBlocking {
        val requested = CompletableDeferred<Unit>()
        val response = CompletableDeferred<String>()
        val source = object : HtmlSource {
            private var requests = 0
            override suspend fun get(url: String) = metadata
            override suspend fun sidebar(feed: PublicFeed) = error("Wrong source")
            override suspend fun catalog(filters: CatalogFilters): String {
                if (requests++ == 0) return fragment
                requested.complete(Unit)
                return response.await()
            }
        }
        val repository = CatalogRepository(folder.newFolder(), source)
        val filters = repository.catalogOptions().normalize(CatalogFilters(), false)
        repository.catalog(filters)
        assertEquals("123", repository.cachedCatalog(filters)!!.entries.first().id)
        Dispatchers.setMain(Dispatchers.Unconfined)
        val store = ViewModelStore()
        try {
            val model = BrowseCatalogViewModel(repository)
            store.put("catalog", model)
            model.activate()
            withTimeout(10_000) { requested.await() }
            val pending = model.state.value.page
            assertTrue(pending.loading)
            assertFalse(pending.loaded || pending.cached)
            assertTrue(pending.entries.isEmpty())
            response.complete(fragment.replace("\"123\"", "\"456\"").replace("/serial-123-", "/serial-456-"))
            val first = withTimeout(10_000) { model.state.first { it.page.loaded } }
            assertEquals(listOf("456", "122"), first.page.entries.map { it.id })
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
