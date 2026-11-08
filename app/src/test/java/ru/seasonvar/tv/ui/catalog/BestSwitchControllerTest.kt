package ru.seasonvar.tv.ui.catalog

import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.R
import ru.seasonvar.tv.ui.filters.*

@OptIn(ExperimentalCoroutinesApi::class)
class BestSwitchControllerTest {
    private class Source : ListFilterSource<String> {
        override val fields = listOf(
            ListFilterField("fhd", R.string.best_filter_fhd, defaultValue = "false", kind = ListFilterKind.TOGGLE),
            ListFilterField("eng", R.string.best_filter_russian, defaultValue = "true", kind = ListFilterKind.TOGGLE, activeTitle = R.string.best_filter_foreign))
        var calls = 0
        var saved = emptyMap<String, String>()
        override suspend fun options() = fields
        override suspend fun load(values: Map<String, String>): FilteredListPage<String> { calls++; delay(100); return FilteredListPage("cards", fields, values) }
        override fun restore(owner: String) = saved
        override fun save(owner: String, values: Map<String, String>) { saved = values }
    }

    @Test fun toggles_use_draft_and_unchanged_reopening_does_not_query_or_lose_selection() = runTest {
        val source = Source()
        val controls = ListFilterController(this, source, "public", {}, {})
        controls.open(); advanceUntilIdle()
        controls.choose("fhd", "true"); controls.choose("eng", "false")
        assertEquals(0, source.calls); assertEquals(0, controls.state.value.activeCount)
        controls.apply(); advanceUntilIdle()
        assertEquals(2, controls.state.value.activeCount)
        assertEquals(R.string.best_filter_foreign, controls.state.value.activeFields.last().activeTitle)
        controls.open(); advanceUntilIdle()
        assertEquals("true", controls.state.value.draft["fhd"]); assertEquals("false", controls.state.value.draft["eng"])
        controls.apply(); advanceUntilIdle(); assertEquals(1, source.calls)
        controls.open(); advanceUntilIdle(); controls.reset(); advanceUntilIdle()
        assertEquals("false", source.saved["fhd"]); assertEquals("true", source.saved["eng"])
        assertEquals(0, controls.state.value.activeCount); assertEquals(2, source.calls)
    }

    @Test fun cancel_discards_checkbox_edits_and_invalid_values_are_not_applied() = runTest {
        val source = Source(); val controls = ListFilterController(this, source, "public", {}, {})
        controls.open(); advanceUntilIdle(); controls.choose("fhd", "yes")
        assertEquals("false", controls.state.value.draft["fhd"])
        controls.choose("fhd", "true"); controls.dismiss()
        assertEquals("false", controls.state.value.draft["fhd"])
        assertEquals(0, source.calls)
    }
    @Test fun account_change_drops_result_before_context_reset_is_observed_by_ui() = runTest {
        val source = Source(); var context = "one"; var applied = false
        val controls = ListFilterController(this, source, "public", {}, { applied = true }, context = { context })
        controls.open(); advanceUntilIdle(); controls.choose("fhd", "true"); controls.apply(); runCurrent()
        context = "two"
        advanceUntilIdle()
        assertFalse(applied); assertTrue(source.saved.isEmpty()); assertEquals(0, controls.state.value.activeCount)
        controls.contextChanged(); assertFalse(controls.state.value.open)
    }

}
