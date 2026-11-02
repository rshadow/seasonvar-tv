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
}
