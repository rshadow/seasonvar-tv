package ru.seasonvar.tv.ui.account

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.account.*
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class MarkRemovalViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val session = MutableStateFlow(AccountSession(AccountUser("12", "Тест", null), 1))
    private val target = AccountListMark("123", "https://seasonvar.ru/serial-123-Test.html")
    private val entry = SeriesEntry("123", target.pageUrl, "Тестовый сериал", 2, "", "", accountListMark = target)
    private val removed = mutableListOf<AccountListMark>()
    private fun model(remove: suspend (AccountListMark, Long) -> Unit) =
        MarkRemovalViewModel(session, remove, removed::add).also { store.put("removal", it) }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { store.clear(); Dispatchers.resetMain() }

    @Test fun actions_and_cancellation_do_not_write_and_confirmation_cannot_be_submitted_twice() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val model = model { mark, version -> assertEquals(target, mark); assertEquals(1L, version); calls++; gate.await() }
        runCurrent()
        model.show(entry); model.confirm(); runCurrent()
        assertEquals(0, calls)
        model.dismiss()
        assertFalse(model.state.value.visible)
        model.show(entry); model.ask(); model.confirm(); model.confirm(); runCurrent()
        assertEquals(1, calls)
        assertTrue(model.state.value.busy)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(target), removed)
        assertFalse(model.state.value.visible)
    }

    @Test fun failed_delete_keeps_confirmation_and_does_not_remove_a_card_until_retry_succeeds() = runTest(dispatcher) {
        var fail = true
        val model = model { _, _ -> if (fail) throw IOException() }
        runCurrent()
        model.show(entry, RemovalStage.CONFIRM); model.confirm(); runCurrent()
        assertTrue(model.state.value.visible)
        assertFalse(model.state.value.busy)
        assertNotNull(model.state.value.failure)
        assertTrue(removed.isEmpty())
        fail = false
        model.confirm(); runCurrent()
        assertEquals(listOf(target), removed)
    }

    @Test fun account_change_closes_the_dialog_and_old_confirmation_cannot_delete_for_a_new_user() = runTest(dispatcher) {
        var calls = 0
        val model = model { _, _ -> calls++ }
        runCurrent(); model.show(entry, RemovalStage.CONFIRM)
        session.value = AccountSession(AccountUser("13", "Другой", null), 2)
        runCurrent(); model.confirm(); runCurrent()
        assertEquals(0, calls)
        assertFalse(model.state.value.visible)
        model.show(entry.copy(accountListMark = null), RemovalStage.CONFIRM)
        assertFalse(model.state.value.visible)
    }

    @Test fun closing_the_progress_dialog_does_not_abort_a_confirmed_deletion() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val model = model { _, _ -> gate.await() }
        runCurrent(); model.show(entry, RemovalStage.CONFIRM); model.confirm(); runCurrent()
        model.dismiss()
        assertFalse(model.state.value.visible)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(target), removed)
        assertFalse(model.state.value.busy)
    }
    @Test fun unmarked_public_entries_can_open_status_actions_but_not_deletion() = runTest(dispatcher) {
        val model = model { _, _ -> fail() }; runCurrent()
        model.show(entry.copy(accountListMark = null)); assertTrue(model.state.value.visible)
        runCurrent(); assertEquals(RemovalStage.ACTIONS, model.state.value.stage)
        assertNull(model.state.value.status)
        assertNull(model.state.value.entry?.accountListMark)
        model.ask(); model.confirm(); runCurrent()
        assertEquals(RemovalStage.ACTIONS, model.state.value.stage)
    }
    @Test fun status_is_loaded_and_only_verified_change_notifies_once() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); var calls = 0
        val changed = mutableListOf<AccountListMark>()
        val model = MarkRemovalViewModel(session, { _, _ -> fail() }, {}, { AccountListStatus.WATCHED },
            { _, status, version -> calls++; assertEquals(1L, version); gate.await(); PlaybackMarkSnapshot(status = status.code) }, { target, _ -> changed.add(target) })
        store.put("status", model); runCurrent()
        model.show(entry, RemovalStage.STATUS); runCurrent()
        assertEquals(AccountListStatus.WATCHED, model.state.value.status)
        model.selectStatus(AccountListStatus.WANT_TO_WATCH); model.selectStatus(AccountListStatus.WANT_TO_WATCH); runCurrent()
        assertTrue(model.state.value.busy); assertTrue(changed.isEmpty()); assertEquals(1, calls)
        model.dismiss(); gate.complete(Unit); runCurrent()
        assertEquals(listOf(target), changed); assertFalse(model.state.value.visible)
    }
    @Test fun failed_status_change_stays_open_and_can_be_retried() = runTest(dispatcher) {
        var fails = true
        val model = MarkRemovalViewModel(session, { _, _ -> }, {}, changeStatus = { _, _, _ ->
            if (fails) throw IOException(); PlaybackMarkSnapshot(status = -1)
        })
        store.put("status", model); runCurrent(); model.show(entry, RemovalStage.STATUS); runCurrent()
        model.selectStatus(AccountListStatus.WANT_TO_WATCH); runCurrent()
        assertNotNull(model.state.value.failure); assertTrue(model.state.value.visible); assertFalse(model.state.value.busy)
        fails = false; model.selectStatus(AccountListStatus.WANT_TO_WATCH); runCurrent(); assertFalse(model.state.value.visible)
    }
    @Test fun account_change_invalidates_a_status_selection() = runTest(dispatcher) {
        var calls = 0
        val model = MarkRemovalViewModel(session, { _, _ -> }, {}, changeStatus = { _, _, _ -> calls++; PlaybackMarkSnapshot(status = -1) })
        store.put("status", model); runCurrent(); model.show(entry, RemovalStage.STATUS); runCurrent()
        session.value = AccountSession(version = 2); runCurrent(); model.selectStatus(AccountListStatus.WANT_TO_WATCH); runCurrent()
        assertEquals(0, calls); assertFalse(model.state.value.visible)
    }

    @Test fun long_press_loads_current_status_and_allows_selection_without_an_intermediate_menu() = runTest(dispatcher) {
        val gate = CompletableDeferred<AccountListStatus?>()
        var writes = 0
        val model = MarkRemovalViewModel(session, { _, _ -> fail() }, {}, loadStatus = { gate.await() },
            changeStatus = { _, _, _ -> writes++; PlaybackMarkSnapshot(status = -1) })
        store.put("flat", model); runCurrent()
        model.show(entry.copy(accountListMark = null)); runCurrent()
        assertEquals(RemovalStage.ACTIONS, model.state.value.stage)
        assertTrue(model.state.value.loadingStatus)
        model.selectStatus(AccountListStatus.WANT_TO_WATCH); runCurrent(); assertEquals(0, writes)
        gate.complete(AccountListStatus.WATCHED); runCurrent()
        assertEquals(AccountListStatus.WATCHED, model.state.value.status)
        assertEquals(target, model.state.value.entry?.accountListMark)
        model.selectStatus(AccountListStatus.WANT_TO_WATCH); runCurrent()
        assertEquals(1, writes); assertFalse(model.state.value.visible)
    }

    @Test fun deletion_confirmation_cancels_pending_status_read_and_cancel_does_not_write() = runTest(dispatcher) {
        val gate = CompletableDeferred<AccountListStatus?>()
        val model = MarkRemovalViewModel(session, { _, _ -> fail() }, {}, loadStatus = { gate.await() })
        store.put("confirm", model); runCurrent(); model.show(entry); runCurrent()
        assertTrue(model.state.value.loadingStatus)
        model.ask(); gate.complete(AccountListStatus.WATCHED); runCurrent()
        assertEquals(RemovalStage.CONFIRM, model.state.value.stage)
        assertFalse(model.state.value.loadingStatus)
        assertNull(model.state.value.status)
        model.dismiss(); runCurrent(); assertFalse(model.state.value.visible)
    }

    @Test fun switching_accounts_cancels_a_late_status_read_from_the_flat_menu() = runTest(dispatcher) {
        val gate = CompletableDeferred<AccountListStatus?>()
        val model = MarkRemovalViewModel(session, { _, _ -> fail() }, {}, loadStatus = { gate.await() })
        store.put("late", model); runCurrent(); model.show(entry); runCurrent()
        session.value = AccountSession(version = 2); runCurrent()
        gate.complete(AccountListStatus.WATCHED); runCurrent()
        assertEquals(MarkRemovalUiState(), model.state.value)
    }

}
