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

}
