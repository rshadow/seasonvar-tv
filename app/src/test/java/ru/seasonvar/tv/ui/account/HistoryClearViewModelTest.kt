package ru.seasonvar.tv.ui.account

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.seasonvar.tv.data.account.*
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryClearViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val session = MutableStateFlow(AccountSession(AccountUser("12", "Test", null), 1))
    private var started = 0
    private var cleared = 0
    private fun model(clear: suspend (Long) -> Unit) = HistoryClearViewModel(session, clear, { started++ }, { cleared++ })
        .also { store.put("history", it) }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { store.clear(); Dispatchers.resetMain() }
    @Test fun confirmation_is_required_and_repeated_ok_does_not_start_duplicate_operations() = runTest(dispatcher) {
        var calls = 0; val gate = CompletableDeferred<Unit>()
        val model = model { assertEquals(1L, it); calls++; gate.await() }
        runCurrent(); model.confirm(); model.ask(); model.dismiss(); model.confirm(); runCurrent()
        assertEquals(0, calls); assertEquals(0, started)
        model.ask(); model.confirm(); model.confirm(); runCurrent()
        assertEquals(1, calls); assertEquals(1, started); assertEquals(0, cleared)
        gate.complete(Unit); runCurrent(); assertEquals(1, cleared); assertFalse(model.state.value.visible)
    }
    @Test fun dismissing_busy_dialog_does_not_abort_confirmed_clear() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val model = model { gate.await() }
        runCurrent(); model.ask(); model.confirm(); runCurrent(); model.dismiss(); model.ask()
        assertFalse(model.state.value.visible); assertTrue(model.state.value.busy)
        gate.complete(Unit); runCurrent(); assertEquals(1, cleared); assertFalse(model.state.value.busy)
    }
    @Test fun failure_keeps_confirmation_and_only_explicit_retry_can_clear() = runTest(dispatcher) {
        var fail = true; val model = model { if (fail) throw IOException() }
        runCurrent(); model.ask(); model.confirm(); runCurrent()
        assertTrue(model.state.value.visible); assertNotNull(model.state.value.failure); assertEquals(0, cleared)
        fail = false; model.confirm(); runCurrent(); assertEquals(1, cleared)
    }
    @Test fun account_change_invalidates_confirmation_and_late_result() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val model = model { withContext(NonCancellable) { gate.await() } }
        runCurrent(); model.ask(); model.confirm(); runCurrent()
        session.value = AccountSession(version = 2); runCurrent(); model.confirm()
        gate.complete(Unit); runCurrent()
        assertEquals(0, cleared); assertFalse(model.state.value.visible)
        model.ask(); assertFalse(model.state.value.visible)
    }
}
