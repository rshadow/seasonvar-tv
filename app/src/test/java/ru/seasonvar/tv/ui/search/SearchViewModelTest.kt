package ru.seasonvar.tv.ui.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.seasonvar.tv.data.MarkupChangedException
import ru.seasonvar.tv.data.SeriesDescription
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.SiteHttpException
import ru.seasonvar.tv.ui.catalog.LoadFailure
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun entry(id: String) = SeriesEntry(id, "https://seasonvar.ru/serial-$id-test.html", id, null, "", "")
    private fun model(search: suspend (String) -> List<SeriesEntry>) =
        SearchViewModel(search) { SeriesDescription(emptyList()) }.also { it.activate() }

    @Test fun typing_is_debounced_and_blank_query_clears_results_without_request() = runTest(dispatcher) {
        val calls = mutableListOf<String>()
        val model = model { calls += it; listOf(entry("1")) }
        model.queryChanged("И")
        runCurrent()
        advanceTimeBy(200)
        model.queryChanged("Игра")
        runCurrent()
        advanceTimeBy(299)
        assertTrue(calls.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf("Игра"), calls)
        assertEquals("1", model.state.value.results.entries.single().id)
        model.queryChanged(" ")
        advanceUntilIdle()
        assertTrue(model.state.value.results.entries.isEmpty())
        assertFalse(model.state.value.results.loading)
        assertEquals(1, calls.size)
    }

    @Test fun obsolete_response_cannot_replace_new_query_even_if_transport_finishes_after_cancellation() = runTest(dispatcher) {
        val model = model {
            if (it == "old") withContext(NonCancellable) { delay(1000) }
            listOf(entry(if (it == "old") "1" else "2"))
        }
        model.queryChanged("old"); model.submit(); runCurrent()
        model.queryChanged("new"); model.submit(); runCurrent()
        assertEquals("2", model.state.value.results.entries.single().id)
        advanceUntilIdle()
        assertEquals("new", model.state.value.query)
        assertEquals("2", model.state.value.results.entries.single().id)
    }

    @Test fun submit_does_not_wait_for_debounce_and_trims_query() = runTest(dispatcher) {
        val calls = mutableListOf<String>()
        val model = model { calls += it; emptyList() }
        model.queryChanged("  test  "); model.submit(); runCurrent()
        assertEquals(listOf("test"), calls)
        assertTrue(model.state.value.results.loaded)
        assertFalse(model.state.value.results.loading)
        advanceUntilIdle()
        assertEquals(1, calls.size)
    }

    @Test fun leaving_search_cancels_work_and_return_retries_pending_query() = runTest(dispatcher) {
        val calls = mutableListOf<String>()
        val model = model { calls += it; listOf(entry("1")) }
        model.queryChanged("test"); runCurrent(); model.deactivate()
        advanceUntilIdle()
        assertTrue(calls.isEmpty())
        assertFalse(model.state.value.results.loading)
        model.activate(); runCurrent()
        assertEquals(listOf("test"), calls)
    }

    @Test fun network_http_and_invalid_response_have_distinct_errors_and_retry_recovers() = runTest(dispatcher) {
        var failure: Exception? = null
        val model = model { failure?.let { throw it }; emptyList() }
        model.queryChanged("test")
        listOf(IOException() to LoadFailure.NETWORK, SiteHttpException(503) to LoadFailure.HTTP,
            MarkupChangedException() to LoadFailure.MARKUP).forEach { (exception, expected) ->
            failure = exception; model.submit(); runCurrent()
            assertEquals(expected, model.state.value.results.failure)
            assertFalse(model.state.value.results.loading)
        }
        failure = null; model.submit(); runCurrent()
        assertNull(model.state.value.results.failure)
        assertTrue(model.state.value.results.loaded)
    }
}
