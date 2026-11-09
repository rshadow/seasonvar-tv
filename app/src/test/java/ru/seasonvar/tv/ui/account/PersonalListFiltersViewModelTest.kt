package ru.seasonvar.tv.ui.account

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.*
import ru.seasonvar.tv.ui.filters.*
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalListFiltersViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val user = AccountUser("12", "Тест", null)
    private val session = MutableStateFlow(AccountSession(user, 1))
    private val entry = SeriesEntry("123", "https://seasonvar.ru/serial-123-test.html", "Тест", null, "", "")
    private val defaults = mapOf("genre" to "all", "country" to "all")
    private inner class Source : ListFilterSource<AccountPage> {
        override val fields = listOf(ListFilterField("genre", R.string.filter_genre, listOf(FilterOption("all", "Любой"), FilterOption("17", "Комедия"))),
            ListFilterField("country", R.string.filter_country, listOf(FilterOption("all", "Любая"), FilterOption("Япония", "Япония"))))
        val calls = mutableListOf<Map<String, String>>()
        val saved = mutableMapOf<String, Map<String, String>>()
        var fail = false
        var failOptions = false
        var slow = false
        override suspend fun options(): List<ListFilterField> { delay(100); if (failOptions) throw IOException(); return fields }
        override suspend fun load(values: Map<String, String>): FilteredListPage<AccountPage> {
            calls += values
            if (slow) withContext(NonCancellable) { delay(1000) } else delay(100)
            if (fail) throw IOException()
            return FilteredListPage(AccountPage(user, if (values["country"] == "Япония") emptyList() else listOf(entry)), fields, values)
        }
        override fun restore(owner: String) = saved[owner].orEmpty()
        override fun save(owner: String, values: Map<String, String>) { saved[owner] = values }
    }
    private fun model(name: String, source: Source) = PersonalListViewModel(session, { error("Unfiltered bypass") }, filterSource = source)
        .also { store.put(name, it) }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { store.clear(); Dispatchers.resetMain() }

    @Test fun editing_and_cancel_do_not_request_results_and_unchanged_apply_only_closes() = runTest(dispatcher) {
        val source = Source(); val model = model("want", source); val controls = model.filters!!
        runCurrent(); model.activate(); advanceUntilIdle(); assertEquals(1, source.calls.size)
        controls.open(); advanceUntilIdle(); controls.choose("genre", "17")
        assertEquals(0, controls.state.value.activeCount); assertEquals(1, source.calls.size)
        controls.dismiss(); assertEquals(defaults, controls.state.value.draft)
        controls.open(); advanceUntilIdle(); controls.apply(); runCurrent()
        assertFalse(controls.state.value.open); assertEquals(1, source.calls.size)
    }

    @Test fun apply_is_single_atomic_and_keeps_previous_cards_and_count_until_success_including_empty_results() = runTest(dispatcher) {
        val source = Source(); val model = model("want", source); val controls = model.filters!!
        runCurrent(); model.activate(); advanceUntilIdle(); controls.open(); advanceUntilIdle()
        controls.choose("country", "Япония"); controls.apply(); controls.apply(); runCurrent()
        assertTrue(controls.state.value.applying); assertEquals(listOf(entry), model.state.value.entries)
        assertEquals(0, controls.state.value.activeCount); assertEquals(2, source.calls.size)
        advanceUntilIdle()
        assertFalse(controls.state.value.open); assertTrue(model.state.value.entries.isEmpty()); assertTrue(model.state.value.loaded)
        assertEquals(1, controls.state.value.activeCount); assertEquals("Япония", controls.state.value.summary)
        assertEquals("Япония", source.saved.values.single()["country"])
        controls.open(); advanceUntilIdle(); controls.reset(); controls.reset(); runCurrent()
        assertEquals(3, source.calls.size); assertEquals(defaults, source.calls.last())
        assertTrue(controls.state.value.applying); assertTrue(model.state.value.entries.isEmpty())
        advanceUntilIdle(); assertEquals(listOf(entry), model.state.value.entries); assertEquals(0, controls.state.value.activeCount)
        assertFalse(controls.state.value.open)
    }

    @Test fun failed_apply_keeps_conditions_cards_and_draft_for_retry_and_other_sections_do_not_change() = runTest(dispatcher) {
        val source = Source(); val model = model("want", source); val otherSource = Source(); val other = model("other", otherSource)
        runCurrent(); model.activate(); other.activate(); advanceUntilIdle()
        val controls = model.filters!!; controls.open(); advanceUntilIdle(); controls.choose("genre", "17"); source.fail = true
        controls.apply(); advanceUntilIdle()
        assertTrue(controls.state.value.open); assertTrue(controls.state.value.applyFailed)
        assertEquals(ru.seasonvar.tv.ui.catalog.LoadFailure.NETWORK, controls.state.value.applyFailure)
        assertEquals("17", controls.state.value.draft["genre"]); assertEquals(defaults, controls.state.value.values)
        assertEquals(listOf(entry), model.state.value.entries); assertEquals(defaults, other.filters!!.state.value.values)
        assertEquals(1, otherSource.calls.size)
        source.fail = false; controls.apply(); advanceUntilIdle()
        assertEquals(1, controls.state.value.activeCount); assertFalse(controls.state.value.open)
        model.refresh(); advanceUntilIdle(); assertEquals("17", source.calls.last()["genre"])
    }

    @Test fun dismissed_or_logged_out_queries_cannot_apply_late_results_or_restore_another_owners_conditions() = runTest(dispatcher) {
        val source = Source(); val model = model("want", source); val controls = model.filters!!
        runCurrent(); model.activate(); advanceUntilIdle(); controls.open(); advanceUntilIdle(); source.slow = true
        controls.choose("genre", "17"); controls.apply(); runCurrent(); controls.dismiss(); advanceUntilIdle()
        assertEquals(defaults, controls.state.value.values)
        controls.open(); advanceUntilIdle(); controls.choose("genre", "17"); controls.apply(); runCurrent()
        session.value = AccountSession(version = 2); runCurrent(); advanceUntilIdle()
        assertFalse(controls.state.value.open); assertEquals(defaults, controls.state.value.values); assertTrue(model.state.value.entries.isEmpty())
        session.value = AccountSession(user.copy(id = "13"), 3); advanceUntilIdle()
        assertEquals(defaults, controls.state.value.values)
    }

    @Test fun failed_options_have_an_explicit_retry_without_changing_the_result_list() = runTest(dispatcher) {
        val source = Source(); val model = model("want", source); val controls = model.filters!!
        runCurrent(); model.activate(); advanceUntilIdle(); source.failOptions = true
        controls.open(); advanceUntilIdle(); assertNotNull(controls.state.value.failure)
        controls.apply(); advanceUntilIdle(); assertEquals(1, source.calls.size)
        source.failOptions = false; controls.open(); advanceUntilIdle(); assertNull(controls.state.value.failure)
        assertEquals(listOf(entry), model.state.value.entries)
    }

    @Test fun reset_of_an_unapplied_draft_closes_without_reloading_the_already_unfiltered_list() = runTest(dispatcher) {
        val source = Source(); val model = model("want", source); val controls = model.filters!!
        runCurrent(); model.activate(); advanceUntilIdle(); controls.open(); advanceUntilIdle()
        controls.choose("genre", "17"); controls.reset(); advanceUntilIdle()
        assertEquals(1, source.calls.size); assertEquals(defaults, controls.state.value.values)
        assertEquals(defaults, controls.state.value.draft); assertFalse(controls.state.value.open)
    }

    @Test fun failed_immediate_reset_keeps_the_filtered_list_and_can_be_retried_with_reset_alone() = runTest(dispatcher) {
        val source = Source(); val model = model("want", source); val controls = model.filters!!
        runCurrent(); model.activate(); advanceUntilIdle(); controls.open(); advanceUntilIdle()
        controls.choose("country", "Япония"); controls.apply(); advanceUntilIdle()
        controls.open(); advanceUntilIdle(); source.fail = true; controls.reset(); advanceUntilIdle()
        assertTrue(controls.state.value.open); assertTrue(controls.state.value.applyFailed)
        assertEquals("Япония", controls.state.value.values["country"])
        assertEquals(defaults, controls.state.value.draft); assertTrue(model.state.value.entries.isEmpty())
        source.fail = false; controls.reset(); advanceUntilIdle()
        assertEquals(defaults, controls.state.value.values); assertEquals(listOf(entry), model.state.value.entries)
        assertFalse(controls.state.value.open)
    }

    @Test fun another_model_restores_the_same_owners_last_successful_conditions_on_first_load() = runTest(dispatcher) {
        val source = Source(); val first = model("first", source)
        runCurrent(); first.activate(); advanceUntilIdle()
        first.filters!!.open(); advanceUntilIdle(); first.filters.choose("genre", "17"); first.filters.apply(); advanceUntilIdle()
        val restored = model("restored", source); runCurrent(); restored.activate(); advanceUntilIdle()
        assertEquals("17", source.calls.last()["genre"])
        assertEquals(1, restored.filters!!.state.value.activeCount)
    }

    @Test fun preferences_are_scoped_to_owner_and_section_and_ignore_corrupt_data() {
        val values = mutableMapOf<String, String>()
        val preferences = ListFilterPreferences(values::get) { key, value -> values[key] = value }
        preferences.write("owner1", "WANT", mapOf("genre" to "17"))
        assertEquals(mapOf("genre" to "17"), preferences.read("owner1", "WANT"))
        assertTrue(preferences.read("owner2", "WANT").isEmpty()); assertTrue(preferences.read("owner1", "WATCHED").isEmpty())
        values["owner1:WANT"] = "broken"; assertTrue(preferences.read("owner1", "WANT").isEmpty())
    }
}
