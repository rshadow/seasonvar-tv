package ru.seasonvar.tv.ui.search

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.seasonvar.tv.data.*
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SearchHistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private class MemoryStore(var saved: List<String> = emptyList()) : SearchHistoryStore {
        var fail = false
        override suspend fun read() = saved
        override suspend fun write(queries: List<String>) { if (fail) throw IOException(); saved = queries }
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { models.clear(); Dispatchers.resetMain() }
    private fun model(store: SearchHistoryStore, search: suspend (String) -> List<SeriesEntry> = { emptyList() }): SearchViewModel =
        SearchViewModel(search, SearchHistory(store)).also { models.put("search", it); it.activate() }

    @Test fun opening_recent_queries_does_not_send_a_request_and_recall_runs_immediately_once() = runTest(dispatcher) {
        val store = MemoryStore(listOf("old", "Naruto")); val calls = mutableListOf<String>()
        val model = model(store) { calls += it; emptyList() }; runCurrent()
        assertTrue(model.state.value.showHistory); assertTrue(model.state.value.focusHistoryOnShow); assertEquals(store.saved, model.state.value.recentQueries); assertTrue(calls.isEmpty())
        model.recall("NARUTO"); runCurrent()
        assertEquals(listOf("NARUTO"), calls); assertFalse(model.state.value.showHistory)
        assertEquals(listOf("NARUTO", "old"), store.saved)
        advanceUntilIdle(); assertEquals(1, calls.size)
        model.deactivate(); model.activate(); runCurrent()
        assertTrue(model.state.value.showHistory); assertEquals(1, calls.size)
    }
    @Test fun auto_search_prefixes_are_not_stored_and_leaving_remembers_only_the_last_complete_query() = runTest(dispatcher) {
        val store = MemoryStore(); val model = model(store); runCurrent()
        for (query in listOf("n", "nar", "naruto")) { model.queryChanged(query); advanceUntilIdle() }
        assertTrue(store.saved.isEmpty()); model.deactivate(); runCurrent()
        assertEquals(listOf("naruto"), store.saved)
    }
    @Test fun explicit_submit_and_opening_a_result_remember_the_query_without_waiting_to_leave() = runTest(dispatcher) {
        val store = MemoryStore(); val model = model(store); runCurrent()
        model.queryChanged("  Naruto  "); model.submit(); runCurrent()
        assertEquals(listOf("Naruto"), store.saved)
        model.queryChanged("Игра"); advanceUntilIdle(); model.rememberCurrentQuery(); runCurrent()
        assertEquals(listOf("Игра", "Naruto"), store.saved)
    }
    @Test fun failed_automatic_queries_are_not_remembered_but_an_explicit_attempt_is() = runTest(dispatcher) {
        val store = MemoryStore(); val model = model(store) { throw IOException() }; runCurrent()
        model.queryChanged("retry"); advanceUntilIdle(); model.deactivate(); runCurrent(); assertTrue(store.saved.isEmpty())
        model.activate(); model.submit(); runCurrent(); assertEquals(listOf("retry"), store.saved)
    }
    @Test fun clearing_preserves_current_query_and_does_not_resurrect_it_on_leave_or_refresh() = runTest(dispatcher) {
        val store = MemoryStore(); val model = model(store); runCurrent()
        model.queryChanged("Naruto"); model.submit(); runCurrent(); model.clearHistory(); runCurrent()
        assertTrue(store.saved.isEmpty()); assertEquals("Naruto", model.state.value.query)
        model.networkChanged(); model.marksChanged(); model.sessionChanged(); advanceUntilIdle()
        model.deactivate(); runCurrent(); assertTrue(store.saved.isEmpty())
        model.activate(); runCurrent(); assertTrue(model.state.value.recentQueries.isEmpty())
        model.submit(); runCurrent(); assertEquals(listOf("Naruto"), store.saved)
    }
    @Test fun clearing_invalidates_an_older_record_job_that_has_not_started() = runTest(dispatcher) {
        val store = MemoryStore(); val model = model(store); runCurrent()
        model.queryChanged("Naruto"); model.submit(); model.clearHistory(); runCurrent()
        assertTrue(store.saved.isEmpty()); assertTrue(model.state.value.recentQueries.isEmpty())
    }
    @Test fun slow_initial_history_does_not_replace_new_typing_or_its_results() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val model = model(object : SearchHistoryStore {
            override suspend fun read(): List<String> { gate.await(); return listOf("old") }
            override suspend fun write(queries: List<String>) {}
        }); runCurrent(); assertTrue(model.state.value.historyLoading)
        model.queryChanged("new"); advanceTimeBy(300); runCurrent(); gate.complete(Unit); runCurrent()
        assertEquals("new", model.state.value.query); assertFalse(model.state.value.showHistory)
        assertEquals(listOf("old"), model.state.value.recentQueries); assertTrue(model.state.value.results.loaded)
    }
    @Test fun a_history_failure_does_not_turn_a_successful_search_into_a_network_error() = runTest(dispatcher) {
        val store = MemoryStore(); val model = model(store); runCurrent(); store.fail = true
        model.queryChanged("Naruto"); model.submit(); runCurrent()
        assertTrue(model.state.value.historyFailed); assertNull(model.state.value.results.failure); assertTrue(model.state.value.results.loaded)
    }
    @Test fun backgrounding_remembers_a_completed_auto_query_but_not_an_unfinished_one() = runTest(dispatcher) {
        val store = MemoryStore(); val model = model(store); runCurrent()
        model.queryChanged("first"); model.rememberPendingQuery(); runCurrent(); assertTrue(store.saved.isEmpty())
        advanceUntilIdle(); model.rememberPendingQuery(); runCurrent(); assertEquals(listOf("first"), store.saved)
        model.clearHistory(); runCurrent(); model.rememberPendingQuery(); runCurrent(); assertTrue(store.saved.isEmpty())
    }

    @Test fun erasing_the_input_shows_history_without_requesting_keyboard_focus() = runTest(dispatcher) {
        val model = model(MemoryStore(listOf("old"))); runCurrent(); assertTrue(model.state.value.showHistory)
        model.queryChanged("new"); model.queryChanged(""); runCurrent()
        assertTrue(model.state.value.showHistory); assertFalse(model.state.value.focusHistoryOnShow)
        assertTrue(model.state.value.results.entries.isEmpty())
        assertEquals(listOf("old"), model.state.value.recentQueries)
    }

    @Test fun late_history_load_after_erasing_input_shows_queries_without_taking_focus() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val model = model(object : SearchHistoryStore {
            override suspend fun read(): List<String> { gate.await(); return listOf("old") }
            override suspend fun write(queries: List<String>) {}
        })
        runCurrent(); model.queryChanged("new"); model.queryChanged(""); gate.complete(Unit); runCurrent()
        assertTrue(model.state.value.showHistory); assertFalse(model.state.value.focusHistoryOnShow)
        assertEquals(listOf("old"), model.state.value.recentQueries)
    }

}
