package ru.seasonvar.tv.ui.smart

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.*
import ru.seasonvar.tv.data.smart.*
import ru.seasonvar.tv.ui.catalog.LoadFailure
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SmartContinueViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val session = MutableStateFlow(AccountSession(AccountUser("1", "Тест", null), 1))
    private val entry = SeriesEntry("40", "https://seasonvar.ru/serial-40-test.html", "Тест", 4, "", "", sourceKey = "smart:100")
    private class Cache(var rows: List<SeriesEntry>? = null) : SmartContinueCache {
        var cleared = 0
        override suspend fun read(owner: String) = rows
        override suspend fun write(owner: String, entries: List<SeriesEntry>) { rows = entries }
        override suspend fun clear(owner: String) { rows = null; cleared++ }
    }
    private fun model(cache: Cache, load: suspend () -> SmartContinuePage) = SmartContinueViewModel(session, load, cache)
        .also { store.put("smart", it) }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { store.clear(); Dispatchers.resetMain() }
    @Test fun hidden_continue_invalidation_keeps_cached_cards_and_waits_until_open_to_load_new_history_order() = runTest(dispatcher) {
        var calls = 0
        val other = entry.copy(id = "50", sourceKey = "smart:200")
        val model = model(Cache(listOf(entry))) {
            calls++; SmartContinuePage(if (calls == 1) listOf(entry, other) else listOf(other, entry), emptySet(), false)
        }
        runCurrent(); model.setVisible(true); runCurrent(); model.setVisible(false)
        model.invalidate(); runCurrent()
        assertEquals(1, calls); assertEquals(listOf(entry, other), model.state.value.entries)
        model.setVisible(true); runCurrent()
        assertEquals(2, calls); assertEquals(listOf(other, entry), model.state.value.entries)
    }
    @Test fun cache_is_visible_while_network_loads_and_error_does_not_erase_it() = runTest(dispatcher) {
        val gate = CompletableDeferred<SmartContinuePage>();val cache = Cache(listOf(entry))
        val model = model(cache) { gate.await() };runCurrent();model.setVisible(true);runCurrent()
        assertEquals(listOf(entry), model.state.value.entries);assertTrue(model.state.value.loading)
        gate.completeExceptionally(IOException());runCurrent()
        assertEquals(listOf(entry), model.state.value.entries);assertEquals(LoadFailure.NETWORK, model.state.value.failure)
    }
    @Test fun partial_refresh_retains_unresolved_cached_series_but_removes_confirmed_finished_ones() = runTest(dispatcher) {
        val other = entry.copy(id = "50", sourceKey = "smart:200")
        val cache = Cache(listOf(entry, other))
        val model = model(cache) { SmartContinuePage(emptyList(), setOf(entry.key), true, IOException()) }
        runCurrent();model.setVisible(true);runCurrent()
        assertEquals(listOf(other), model.state.value.entries)
    }
    @Test fun complete_history_refresh_removes_cached_series_that_are_no_longer_eligible() = runTest(dispatcher) {
        val stale = entry.copy(id = "50", sourceKey = "smart:200")
        val model = model(Cache(listOf(entry, stale))) { SmartContinuePage(listOf(entry), setOf(entry.key), false) }
        runCurrent(); model.setVisible(true); runCurrent()
        assertEquals(listOf(entry), model.state.value.entries)
    }
    @Test fun account_change_cancels_late_results_and_clears_previous_owner_cache() = runTest(dispatcher) {
        val gate = CompletableDeferred<SmartContinuePage>();val cache = Cache(listOf(entry))
        val model = model(cache) { gate.await() };runCurrent();model.setVisible(true);runCurrent()
        session.value = AccountSession(version = 2);runCurrent();gate.complete(SmartContinuePage(listOf(entry), emptySet(), false));runCurrent()
        assertTrue(model.state.value.entries.isEmpty());assertNull(cache.rows);assertEquals(1, cache.cleared)
    }
    @Test fun invalidation_is_deferred_while_hidden_and_refreshes_when_opened() = runTest(dispatcher) {
        var calls = 0;val model = model(Cache()) { calls++;SmartContinuePage(listOf(entry), emptySet(), false) }
        runCurrent();model.invalidate();runCurrent();assertEquals(0, calls)
        model.setVisible(true);runCurrent();assertEquals(1, calls)
        model.setVisible(false);model.invalidate();runCurrent();assertEquals(1, calls)
        model.setVisible(true);runCurrent();assertEquals(2, calls)
    }
    @Test fun entering_again_refreshes_server_changes_even_without_local_activity() = runTest(dispatcher) {
        var calls = 0;val model = model(Cache()) { calls++;SmartContinuePage(listOf(entry), emptySet(), false) }
        runCurrent();model.setVisible(true);runCurrent();assertEquals(1, calls)
        model.setVisible(false);runCurrent();model.setVisible(true);runCurrent();assertEquals(2, calls)
    }

    @Test fun history_clear_erases_continue_cache_and_blocks_late_refresh() = runTest(dispatcher) {
        val gate = CompletableDeferred<SmartContinuePage>(); val cache = Cache(listOf(entry))
        val model = model(cache) { withContext(NonCancellable) { gate.await() } }
        runCurrent(); model.setVisible(true); runCurrent()
        assertEquals(listOf(entry), model.state.value.entries)
        model.historyCleared(); runCurrent()
        assertTrue(model.state.value.loaded); assertTrue(model.state.value.entries.isEmpty()); assertNull(cache.rows)
        gate.complete(SmartContinuePage(listOf(entry), emptySet(), false)); runCurrent()
        assertTrue(model.state.value.entries.isEmpty()); assertNull(cache.rows)
    }

}
