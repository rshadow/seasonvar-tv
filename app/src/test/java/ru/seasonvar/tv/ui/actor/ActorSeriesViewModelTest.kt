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

    @Test fun shows_cached_data_while_refreshing_then_replaces_it_with_the_server_response() = runTest(dispatcher) {
        val model = ActorSeriesViewModel({ page("1") }, { delay(1000); page("2") })
        model.refresh(); runCurrent()
        assertEquals("1", model.state.value.entries.single().id)
        assertTrue(model.state.value.cached && model.state.value.loading)
        advanceUntilIdle()
        assertEquals("2", model.state.value.entries.single().id)
        assertFalse(model.state.value.cached || model.state.value.loading)
    }

    @Test fun failed_update_keeps_the_cached_results_and_reports_the_failure() = runTest(dispatcher) {
        val model = ActorSeriesViewModel({ page("1") }, { throw IOException() })
        model.refresh(); advanceUntilIdle()
        assertEquals("1", model.state.value.entries.single().id)
        assertTrue(model.state.value.cached && model.state.value.loaded)
        assertEquals(LoadFailure.NETWORK, model.state.value.failure)
        assertFalse(model.state.value.loading)
    }

    @Test fun distinguishes_http_markup_and_empty_results() = runTest(dispatcher) {
        for ((error, expected) in listOf(SiteHttpException(503) to LoadFailure.HTTP,
            MarkupChangedException() to LoadFailure.MARKUP)) {
            val model = ActorSeriesViewModel({ null }, { throw error })
            model.refresh(); advanceUntilIdle()
            assertEquals(expected, model.state.value.failure)
        }
        val empty = ActorSeriesViewModel({ null }, { page("1").copy(entries = emptyList()) })
        empty.refresh(); advanceUntilIdle()
        assertTrue(empty.state.value.loaded && empty.state.value.entries.isEmpty())
        assertNull(empty.state.value.failure)
    }

    @Test fun obsolete_network_response_cannot_replace_a_later_refresh() = runTest(dispatcher) {
        var calls = 0
        val model = ActorSeriesViewModel({ null }, {
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
        val model = ActorSeriesViewModel({ null }, { calls++; delay(100); page(calls.toString()) })
        model.refresh(); advanceUntilIdle()
        assertEquals("1", model.state.value.entries.single().id)
        model.refresh(clear = true)
        assertTrue(model.state.value.entries.isEmpty())
        assertFalse(model.state.value.loaded)
        advanceUntilIdle()
        assertEquals("2", model.state.value.entries.single().id)
    }
}
