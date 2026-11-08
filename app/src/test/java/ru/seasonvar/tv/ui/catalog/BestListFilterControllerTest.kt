package ru.seasonvar.tv.ui.catalog

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.account.FilterOption
import ru.seasonvar.tv.ui.filters.*
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class BestListFilterControllerTest {
    private val defaults = mapOf("genre" to "", "period" to "1")
    private class Source : ListFilterSource<List<String>> {
        override val fields = listOf(
            ListFilterField("genre", R.string.filter_genre, listOf(FilterOption("", "Любой жанр"), FilterOption("17", "Комедия")), ""),
            ListFilterField("period", R.string.filter_period, listOf(FilterOption("1", "1 день"), FilterOption("7", "1 неделя"), FilterOption("31", "1 месяц")), "1"))
        val queries = mutableListOf<Map<String, String>>()
        val saved = mutableMapOf<String, Map<String, String>>()
        var fail = false
        var failOptions = false
        var slow = false
        override suspend fun options(): List<ListFilterField> { delay(100); if (failOptions) throw IOException(); return fields }
        override suspend fun load(values: Map<String, String>): FilteredListPage<List<String>> {
            queries += values
            if (slow) withContext(NonCancellable) { delay(1000) } else delay(100)
            if (fail) throw IOException()
            return FilteredListPage(if (values["period"] == "31") emptyList() else listOf(values.getValue("period")), fields, values)
        }
        override fun restore(owner: String) = saved[owner].orEmpty()
        override fun save(owner: String, values: Map<String, String>) { saved[owner] = values }
    }

    @Test fun editing_cancels_without_query_and_apply_updates_cards_and_conditions_together_once() = runTest {
        val source = Source()
        var cards = listOf("old")
        var starts = 0
        val controls = ListFilterController(this, source, "public", { starts++ }, { cards = it })
        controls.open(); advanceUntilIdle(); controls.choose("period", "7"); controls.dismiss()
        assertTrue(source.queries.isEmpty()); assertEquals(defaults, controls.state.value.values)
        controls.open(); advanceUntilIdle(); controls.choose("genre", "17"); controls.choose("period", "7")
        controls.apply(); controls.apply(); runCurrent()
        assertEquals(1, starts); assertEquals(listOf("old"), cards); assertEquals(0, controls.state.value.activeCount)
        advanceUntilIdle()
        assertEquals(listOf("7"), cards); assertEquals(2, controls.state.value.activeCount)
        assertEquals("Комедия · 1 неделя", controls.state.value.summary)
        assertFalse(controls.state.value.open); assertEquals(1, source.queries.size)
        assertEquals(source.queries.single(), source.saved["public"])
    }

    @Test fun empty_result_is_applied_and_reset_immediately_restores_any_genre_and_one_day() = runTest {
        val source = Source(); var cards = listOf("old")
        val controls = ListFilterController(this, source, "public", {}, { cards = it })
        controls.open(); advanceUntilIdle(); controls.choose("period", "31"); controls.apply(); advanceUntilIdle()
        assertTrue(cards.isEmpty()); assertEquals(1, controls.state.value.activeCount)
        controls.open(); advanceUntilIdle(); controls.reset(); controls.reset(); advanceUntilIdle()
        assertEquals(defaults, source.queries.last()); assertEquals(2, source.queries.size)
        assertEquals(listOf("1"), cards); assertEquals(0, controls.state.value.activeCount); assertFalse(controls.state.value.open)
        controls.open(); advanceUntilIdle(); controls.choose("period", "7"); controls.reset(); advanceUntilIdle()
        assertEquals(2, source.queries.size)
    }

    @Test fun errors_keep_previous_results_and_applied_conditions_and_allow_retry_without_apply() = runTest {
        val source = Source(); val controls = ListFilterController(this, source, "public", {}, {})
        controls.open(); advanceUntilIdle(); controls.choose("period", "7"); controls.apply(); advanceUntilIdle()
        source.fail = true
        controls.open(); advanceUntilIdle(); controls.reset(); advanceUntilIdle()
        assertEquals("7", controls.state.value.values["period"])
        assertEquals(LoadFailure.NETWORK, controls.state.value.applyFailure)
        assertTrue(controls.state.value.open)
        source.fail = false; controls.reset(); advanceUntilIdle()
        assertEquals(defaults, controls.state.value.values); assertFalse(controls.state.value.open)
    }

    @Test fun close_and_context_change_discard_late_results_and_preserve_persisted_public_selection() = runTest {
        for (resetContext in listOf(false, true)) {
            val source = Source(); source.saved["public"] = defaults + ("period" to "7")
            var changes = 0
            val controls = ListFilterController(this, source, "public", {}, { changes++ })
            controls.open(); advanceUntilIdle(); controls.choose("period", "31"); source.slow = true
            controls.apply(); runCurrent()
            if (resetContext) controls.contextChanged() else controls.dismiss()
            advanceUntilIdle()
            assertEquals(0, changes); assertEquals("7", controls.state.value.values["period"])
            assertEquals("7", source.saved["public"]!!["period"]); assertFalse(controls.state.value.open)
        }
    }

    @Test fun failed_options_can_be_retried_and_unchanged_apply_does_not_fetch_results() = runTest {
        val source = Source(); source.failOptions = true
        val controls = ListFilterController(this, source, "public", {}, {})
        controls.open(); advanceUntilIdle(); assertEquals(LoadFailure.NETWORK, controls.state.value.failure)
        controls.apply(); assertTrue(source.queries.isEmpty())
        source.failOptions = false; controls.open(); advanceUntilIdle()
        assertNull(controls.state.value.failure); controls.apply(); assertFalse(controls.state.value.open)
        assertTrue(source.queries.isEmpty())
    }
}
