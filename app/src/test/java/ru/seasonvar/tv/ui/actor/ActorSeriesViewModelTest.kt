package ru.seasonvar.tv.ui.actor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.ui.catalog.LoadFailure
import java.io.IOException
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class ActorSeriesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun page(id: String) = CatalogPage(LocalDate.of(2026, 11, 13), listOf(
        SeriesEntry(id, "https://seasonvar.ru/serial-$id-test.html", id, null, "", "")))

    @Test fun visibility_is_independent_of_loading_and_first_opening_waits_for_network_preparation() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>(); var calls = 0
        val model = ActorSeriesViewModel({ calls++; page("2") }, { gate.await() })
        model.setVisible(true); model.refresh(); runCurrent()
        assertTrue(model.isVisible && model.state.value.loading)
        assertTrue(model.state.value.entries.isEmpty())
        assertEquals(0, calls)
        model.setVisible(false); gate.complete(Unit); runCurrent()
        assertFalse(model.isVisible); assertEquals(1, calls)
        assertEquals("2", model.state.value.entries.single().id)
    }

    @Test fun refresh_keeps_the_current_rows_until_the_next_server_response() = runTest(dispatcher) {
        var calls = 0
        val model = ActorSeriesViewModel(load = { delay(1000); page((++calls).toString()) })
        model.refresh(); advanceUntilIdle()
        val current = model.state.value.entries
        model.refresh(); runCurrent()
        assertSame(current, model.state.value.entries)
        assertTrue(model.state.value.loaded && model.state.value.loading)
        advanceUntilIdle()
        assertEquals("2", model.state.value.entries.single().id)
        assertFalse(model.state.value.cached || model.state.value.loading)
    }

    @Test fun failed_update_keeps_the_current_rows_and_reports_the_failure() = runTest(dispatcher) {
        var fail = false
        val model = ActorSeriesViewModel(load = { if (fail) throw IOException() else page("1") })
        model.refresh(); advanceUntilIdle()
        fail = true
        model.refresh(); advanceUntilIdle()
        assertEquals("1", model.state.value.entries.single().id)
        assertTrue(model.state.value.loaded)
        assertEquals(LoadFailure.NETWORK, model.state.value.failure)
        assertFalse(model.state.value.loading)
    }

    @Test fun distinguishes_http_markup_and_empty_results() = runTest(dispatcher) {
        for ((error, expected) in listOf(SiteHttpException(503) to LoadFailure.HTTP,
            MarkupChangedException() to LoadFailure.MARKUP)) {
            val model = ActorSeriesViewModel(load = { throw error })
            model.refresh(); advanceUntilIdle()
            assertEquals(expected, model.state.value.failure)
        }
        val empty = ActorSeriesViewModel(load = { page("1").copy(entries = emptyList()) })
        empty.refresh(); advanceUntilIdle()
        assertTrue(empty.state.value.loaded && empty.state.value.entries.isEmpty())
        assertNull(empty.state.value.failure)
    }

    @Test fun obsolete_network_response_cannot_replace_a_later_refresh() = runTest(dispatcher) {
        var calls = 0
        val model = ActorSeriesViewModel(load = {
            val call = ++calls
            if (call == 1) withContext(NonCancellable) { delay(1000) }
            page(call.toString())
        })
        model.refresh(); runCurrent(); model.refresh(); runCurrent()
        assertEquals("2", model.state.value.entries.single().id)
        advanceUntilIdle()
        assertEquals("2", model.state.value.entries.single().id)
    }

    @Test fun account_change_clears_visible_data_before_loading_the_new_scope() = runTest(dispatcher) {
        var calls = 0
        val model = ActorSeriesViewModel(load = { calls++; delay(100); page(calls.toString()) })
        model.refresh(); advanceUntilIdle()
        assertEquals("1", model.state.value.entries.single().id)
        model.refresh(clear = true)
        assertTrue(model.state.value.entries.isEmpty())
        assertFalse(model.state.value.loaded)
        advanceUntilIdle()
        assertEquals("2", model.state.value.entries.single().id)
    }
}
