package ru.seasonvar.tv.ui.catalog

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.ui.filters.ListFilterPreferences
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseCatalogViewModelTest {
    @get:Rule val folder = TemporaryFolder()
    private val store = ViewModelStore()
    private val saved = mutableMapOf<String, String>()
    private val preferences = ListFilterPreferences(saved::get) { key, value -> saved[key] = value }
    private class Source : HtmlSource {
        var posts = 0
        var fail = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun get(url: String) = listOf("country", "genre", "year", "seasc", "raiting", "golosa", "sort").joinToString("") { name ->
            val values = if (name == "sort") listOf("newest", "name") else listOf("all", "1", "2")
            "<select data-filter='$name'>" + values.joinToString("") { "<option value='$it'>$it</option>" } + "</select>"
        }
        override suspend fun sidebar(feed: PublicFeed) = error("Wrong source")
        override suspend fun catalog(filters: CatalogFilters): String {
            posts++; gate?.await(); if (fail) throw IOException()
            if (filters["year"] == "2") return "<div class='pgs-msg'>Под заданный фильтр<br>ничего не подходит</div>"
            return "<div class='lside-serial' data-serial='list'><a data-id='123' href='/serial-123-Test.html'>Тест</a></div>"
        }
    }
    @Before fun before() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun after() { store.clear(); Dispatchers.resetMain() }
    private fun model(source: Source, name: String = "test", owner: () -> String? = { null }) =
        BrowseCatalogViewModel(CatalogRepository(folder.newFolder(), source), preferences, owner = owner).also { store.put(name, it) }
    private suspend fun ready(model: BrowseCatalogViewModel) = withTimeout(10_000) { model.state.first { it.page.loaded && !it.page.loading } }

    @Test fun initial_open_is_lazy_draft_does_not_request_and_apply_keeps_old_results_until_ready() = runBlocking {
        val source = Source(); val model = model(source)
        assertEquals(0, source.posts)
        model.activate(); ready(model); assertEquals(1, source.posts)
        model.choose("year", "2")
        assertTrue(model.state.value.changed); assertEquals(1, source.posts)
        source.gate = CompletableDeferred(); model.apply(); model.apply()
        assertEquals(1, model.state.value.page.entries.size)
        source.gate!!.complete(Unit)
        val result = ready(model)
        assertTrue(result.page.entries.isEmpty()); assertEquals("2", result.applied["year"])
        assertEquals(2, source.posts)
    }

    @Test fun reset_applies_immediately_and_successful_values_survive_model_recreation() = runBlocking {
        val source = Source(); val model = model(source)
        model.activate(); ready(model)
        model.choose("year", "2"); model.choose("sort", "name"); model.apply(); ready(model)
        val restored = model(source, "restored")
        assertEquals("2", restored.state.value.draft["year"]); assertEquals("name", restored.state.value.draft["sort"])
        model.reset(); val reset = ready(model)
        assertEquals("all", reset.applied["year"]); assertEquals("newest", reset.applied["sort"])
        assertEquals(1, reset.page.entries.size)
    }

    @Test fun failure_preserves_applied_values_and_previous_cards() = runBlocking {
        val source = Source(); val model = model(source)
        model.activate(); ready(model); model.choose("year", "2")
        source.fail = true; model.apply()
        val failed = withTimeout(10_000) { model.state.first { it.page.failure != null } }
        assertEquals(LoadFailure.NETWORK, failed.page.failure)
        assertEquals("all", failed.applied["year"]); assertEquals(1, failed.page.entries.size)
        source.fail = false; model.apply(); ready(model)
        assertEquals("2", model.state.value.applied["year"])
    }

    @Test fun logout_clears_private_draft_and_keeps_common_conditions() = runBlocking {
        val source = Source(); var owner: String? = "one"; val model = model(source, owner = { owner })
        model.activate(); ready(model); model.choose("mark", "true"); model.choose("sort", "name"); model.apply(); ready(model)
        assertEquals("true", model.state.value.applied["mark"])
        owner = null; model.sessionChanged(); ready(model)
        assertEquals("false", model.state.value.applied["mark"]); assertEquals("name", model.state.value.applied["sort"])
    }
}
