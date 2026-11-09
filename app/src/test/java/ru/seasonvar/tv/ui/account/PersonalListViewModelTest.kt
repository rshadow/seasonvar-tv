package ru.seasonvar.tv.ui.account

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.*
import ru.seasonvar.tv.ui.catalog.LoadFailure
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalListViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val user = AccountUser("12", "Тест", null)
    private val session = MutableStateFlow(AccountSession(user, 1))
    private fun entry(id: String) = SeriesEntry(id, "https://seasonvar.ru/serial-$id-test.html", id, null, "", "")
    private fun model(key: String, load: suspend () -> AccountPage) =
        PersonalListViewModel(session, load) { SeriesDescription(emptyList()) }.also { store.put(key, it) }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { store.clear(); Dispatchers.resetMain() }

    @Test fun cleared_history_cannot_be_restored_by_old_pagination_or_late_refresh() = runTest(dispatcher) {
        var calls = 0
        val history = PersonalListViewModel(session, {
            calls++
            AccountPage(user, listOf(entry("1")), "page2")
        }, { withContext(NonCancellable) { delay(1000) }; AccountPage(user, listOf(entry("2"))) }) {
            SeriesDescription(emptyList())
        }.also { store.put("history", it) }
        runCurrent(); history.activate(); runCurrent(); history.loadMore(); runCurrent()
        history.historyCleared(); advanceUntilIdle(); history.activate(); runCurrent()
        assertTrue(history.state.value.loaded); assertTrue(history.state.value.entries.isEmpty())
        assertFalse(history.state.value.hasMore); assertFalse(history.state.value.loadingMore); assertEquals(1, calls)
    }

    @Test fun history_invalidation_is_lazy_keeps_cards_and_refreshes_from_page_one_without_touching_other_lists() = runTest(dispatcher) {
        var calls = 0; var wantCalls = 0; var nextCalls = 0
        val history = PersonalListViewModel(session, {
            calls++; delay(100); AccountPage(user, listOf(entry(if (calls == 1) "1" else "3")), "page2")
        }, { nextCalls++; AccountPage(user, listOf(entry("2"))) }) { SeriesDescription(emptyList()) }.also { store.put("history", it) }
        val want = model("want") { wantCalls++; AccountPage(user, listOf(entry("9"))) }
        runCurrent(); history.activate(); want.activate(); advanceUntilIdle(); history.loadMore(); runCurrent()
        assertEquals(listOf("1", "2"), history.state.value.entries.map { it.id })
        history.invalidate(); runCurrent()
        assertEquals(1, calls); assertEquals(1, wantCalls); assertFalse(history.state.value.hasMore)
        assertEquals(listOf("1", "2"), history.state.value.entries.map { it.id })
        history.loadMore(); runCurrent()
        assertTrue(history.state.value.loading); assertEquals(listOf("1", "2"), history.state.value.entries.map { it.id })
        advanceUntilIdle()
        assertEquals(listOf("3"), history.state.value.entries.map { it.id })
        history.activate(); runCurrent(); assertEquals(2, calls)
        assertEquals(1, nextCalls); assertEquals(1, wantCalls)
        assertEquals(listOf("9"), want.state.value.entries.map { it.id })
    }

    @Test fun invalidation_cancels_old_pagination_and_failed_refresh_remains_dirty_for_the_next_open() = runTest(dispatcher) {
        var calls = 0; var failRefresh = false
        val history = PersonalListViewModel(session, {
            calls++; if (failRefresh) throw IOException(); AccountPage(user, listOf(entry("1")), "page2")
        }, { withContext(NonCancellable) { delay(1000) }; AccountPage(user, listOf(entry("2"))) }) {
            SeriesDescription(emptyList())
        }.also { store.put("history", it) }
        runCurrent(); history.activate(); runCurrent(); history.loadMore(); runCurrent()
        history.invalidate(); failRefresh = true; history.activate(); advanceUntilIdle()
        assertEquals(listOf("1"), history.state.value.entries.map { it.id })
        assertEquals(LoadFailure.NETWORK, history.state.value.failure); assertFalse(history.state.value.hasMore)
        failRefresh = false; history.activate(); advanceUntilIdle()
        assertEquals(3, calls); assertNull(history.state.value.failure); assertTrue(history.state.value.hasMore)
    }

    @Test fun lists_load_independently_and_returning_to_a_loaded_list_does_not_reload() = runTest(dispatcher) {
        var updatesCalls = 0
        var wantCalls = 0
        val updates = model("updates") { updatesCalls++; AccountPage(user, listOf(entry("1"))) }
        val want = model("want") { wantCalls++; AccountPage(user, listOf(entry("2"))) }
        runCurrent()
        assertEquals(0, updatesCalls + wantCalls)
        updates.activate(); runCurrent()
        assertEquals("1", updates.state.value.entries.single().id)
        assertFalse(want.state.value.loaded)
        want.activate(); runCurrent()
        updates.activate(); runCurrent()
        assertEquals("2", want.state.value.entries.single().id)
        assertEquals(1, updatesCalls)
        assertEquals(1, wantCalls)
    }
    @Test fun refreshing_a_list_keeps_its_cards_and_does_not_reset_other_lists() = runTest(dispatcher) {
        var offline = false
        val updates = model("updates") { if (offline) throw IOException(); AccountPage(user, listOf(entry("1"))) }
        val want = model("want") { AccountPage(user, listOf(entry("2"))) }
        runCurrent(); updates.activate(); want.activate(); runCurrent()
        offline = true
        updates.refresh()
        assertTrue(updates.state.value.loading)
        assertEquals("1", updates.state.value.entries.single().id)
        runCurrent()
        assertEquals(LoadFailure.NETWORK, updates.state.value.failure)
        assertEquals("2", want.state.value.entries.single().id)
        assertNull(want.state.value.failure)
    }
    @Test fun logout_clears_all_lists_and_late_results_cannot_restore_private_data() = runTest(dispatcher) {
        val fast = model("fast") { AccountPage(user, listOf(entry("1"))) }
        val slow = model("slow") { withContext(NonCancellable) { delay(1000) }; AccountPage(user, listOf(entry("2"))) }
        runCurrent(); fast.activate(); slow.activate(); runCurrent()
        fast.select(entry("1"), false); advanceTimeBy(200); runCurrent()
        session.value = AccountSession(version = 2)
        runCurrent(); advanceUntilIdle()
        listOf(fast, slow).forEach {
            assertTrue(it.state.value.entries.isEmpty())
            assertFalse(it.state.value.loading)
            assertNull(it.description.value.key)
        }
    }
    @Test fun additional_pages_append_without_replacing_existing_cards_and_duplicate_requests_are_ignored() = runTest(dispatcher) {
        val calls = mutableListOf<String>()
        val model = PersonalListViewModel(session, { AccountPage(user, listOf(entry("1")), "page2") }, { url ->
            calls += url; delay(100)
            AccountPage(user, listOf(entry(if (url == "page2") "2" else "3")), if (url == "page2") "page3" else null)
        }) { SeriesDescription(emptyList()) }.also { store.put("history", it) }
        runCurrent(); model.activate(); runCurrent()
        model.loadMore(); model.loadMore(); runCurrent()
        assertEquals(listOf("1"), model.state.value.entries.map { it.id })
        assertTrue(model.state.value.loadingMore)
        advanceUntilIdle()
        assertEquals(listOf("1", "2"), model.state.value.entries.map { it.id })
        assertEquals(listOf("page2"), calls)
        model.loadMore(); advanceUntilIdle(); model.loadMore(); runCurrent()
        assertEquals(listOf("1", "2", "3"), model.state.value.entries.map { it.id })
        assertEquals(listOf("page2", "page3"), calls)
        assertFalse(model.state.value.hasMore)
    }
    @Test fun failed_next_page_keeps_cards_and_retries_only_on_explicit_action() = runTest(dispatcher) {
        var offline = true; var calls = 0
        val model = PersonalListViewModel(session, { AccountPage(user, listOf(entry("1")), "page2") }, {
            calls++; if (offline) throw IOException(); AccountPage(user, listOf(entry("2")))
        }) { SeriesDescription(emptyList()) }.also { store.put("history", it) }
        runCurrent(); model.activate(); runCurrent(); model.loadMore(); runCurrent()
        assertEquals(LoadFailure.NETWORK, model.state.value.moreFailure)
        model.loadMore(); runCurrent(); assertEquals(1, calls)
        assertEquals(listOf("1"), model.state.value.entries.map { it.id })
        offline = false; model.retryOrRefresh(); advanceUntilIdle()
        assertEquals(listOf("1", "2"), model.state.value.entries.map { it.id })
        assertNull(model.state.value.moreFailure)
    }
    @Test fun refresh_and_logout_discard_late_pagination_results() = runTest(dispatcher) {
        val model = PersonalListViewModel(session, { AccountPage(user, listOf(entry("1")), "page2") }, {
            withContext(NonCancellable) { delay(1000) }; AccountPage(user, listOf(entry("2")))
        }) { SeriesDescription(emptyList()) }.also { store.put("history", it) }
        runCurrent(); model.activate(); runCurrent(); model.loadMore(); runCurrent()
        model.refresh(); advanceUntilIdle()
        assertEquals(listOf("1"), model.state.value.entries.map { it.id })
        model.loadMore(); runCurrent(); session.value = AccountSession(version = 2); advanceUntilIdle()
        assertTrue(model.state.value.entries.isEmpty())
        assertFalse(model.state.value.hasMore)
        assertFalse(model.state.value.loadingMore)
    }
    @Test fun pagination_loop_is_reported_without_duplicate_rows() = runTest(dispatcher) {
        val model = PersonalListViewModel(session, { AccountPage(user, listOf(entry("1")), "page2") }, {
            AccountPage(user, listOf(entry("2")), "page2")
        }) { SeriesDescription(emptyList()) }.also { store.put("history", it) }
        runCurrent(); model.activate(); runCurrent(); model.loadMore(); runCurrent()
        assertEquals(LoadFailure.MARKUP, model.state.value.moreFailure)
        assertEquals(listOf("1"), model.state.value.entries.map { it.id })
    }

    @Test fun late_error_from_cancelled_pagination_does_not_override_successful_refresh() = runTest(dispatcher) {
        val model = PersonalListViewModel(session, { AccountPage(user, listOf(entry("1")), "page2") }, {
            withContext(NonCancellable) { delay(1000) }; throw IOException()
        }) { SeriesDescription(emptyList()) }.also { store.put("history", it) }
        runCurrent(); model.activate(); runCurrent(); model.loadMore(); runCurrent()
        model.refresh(); advanceUntilIdle()
        assertEquals(listOf("1"), model.state.value.entries.map { it.id })
        assertNull(model.state.value.moreFailure)
        assertFalse(model.state.value.loadingMore)
    }


    @Test fun confirmed_mark_removal_filters_related_list_entries_but_keeps_history_records() = runTest(dispatcher) {
        val mark = AccountListMark("1", entry("1").url)
        val first = entry("1").copy(accountListMark = mark)
        val history = entry("1").copy(sourceKey = "history:one", historyVisitedAt = "test")
        val list = model("list") { AccountPage(user, listOf(first, entry("2"))) }
        val visited = model("history") { AccountPage(user, listOf(history)) }
        runCurrent(); list.activate(); visited.activate(); runCurrent()
        list.markRemoved(mark); visited.markRemoved(mark)
        assertEquals(listOf("2"), list.state.value.entries.map { it.id })
        assertEquals(listOf(history), visited.state.value.entries)
    }
}
