package ru.seasonvar.tv.ui.settings

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
import ru.seasonvar.tv.data.MarkupChangedException
import ru.seasonvar.tv.data.SiteHttpException
import ru.seasonvar.tv.ui.catalog.LoadFailure
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    @Test fun every_add_dialog_loads_current_choices_without_saving_catalog() = runTest(dispatcher) {
        var calls = 0
        val writes = mutableListOf<List<String>>()
        val preferred = MutableStateFlow(listOf("AlexFilm"))
        val model = TranslationSettingsViewModel(preferred, { writes += it; preferred.value = it }) {
            calls++; listOf(if (calls == 1) "AlexFilm" else "AniDub")
        }
        model.openChoices(); advanceUntilIdle(); model.closeChoices()
        assertTrue(model.state.value.options.isEmpty())
        model.openChoices(); advanceUntilIdle()
        assertEquals(listOf("AniDub"), model.state.value.options)
        assertEquals(2, calls)
        assertTrue(writes.isEmpty())
        assertEquals(listOf("AlexFilm"), preferred.value)
    }
    @Test fun additions_append_only_available_new_names_without_duplicates() = runTest(dispatcher) {
        val preferred = MutableStateFlow(emptyList<String>())
        val model = TranslationSettingsViewModel(preferred, { preferred.value = it }) { listOf("AlexFilm", "AniDub") }
        model.openChoices(); advanceUntilIdle()
        assertTrue(preferred.value.isEmpty())
        model.add("invented"); assertTrue(preferred.value.isEmpty())
        model.add(" alexfilm "); model.add("ALEXFILM"); model.add("AniDub")
        assertEquals(listOf("AlexFilm", "AniDub"), preferred.value)
    }
    @Test fun moving_swaps_adjacent_values_and_saves_immediately() {
        val preferred = MutableStateFlow(listOf("A", "B", "C"))
        val model = TranslationSettingsViewModel(preferred, { preferred.value = it }) { error("Network should not be needed") }
        assertEquals(listOf("B", "A", "C"), model.move("B", -1))
        assertEquals(listOf("A", "B", "C"), model.move("B", 1))
        assertEquals(listOf("A", "C", "B"), model.move("B", 1))
        assertEquals(listOf("A", "C", "B"), preferred.value)
    }
    @Test fun unavailable_move_directions_and_unknown_values_do_not_write() {
        val preferred = MutableStateFlow(listOf("A", "B"))
        val writes = mutableListOf<List<String>>()
        val model = TranslationSettingsViewModel(preferred, { writes += it; preferred.value = it }) { emptyList() }
        model.move("A", -1); model.move("B", 1); model.move("A", 2); model.move("missing", 1); model.remove("missing")
        assertTrue(writes.isEmpty())
        assertEquals(listOf("A", "B"), preferred.value)
    }
    @Test fun removing_last_value_leaves_an_empty_persisted_list() {
        val preferred = MutableStateFlow(listOf("A", "B"))
        val model = TranslationSettingsViewModel(preferred, { preferred.value = it }) { error("Network should not be needed") }
        assertEquals(listOf("B"), model.remove("A"))
        assertEquals(emptyList<String>(), model.remove("B"))
        assertTrue(preferred.value.isEmpty())
    }
    @Test fun errors_preserve_order_and_retry_loads_again() = runTest(dispatcher) {
        val preferred = MutableStateFlow(listOf("Saved A", "Saved B"))
        var error: Exception? = null
        val model = TranslationSettingsViewModel(preferred, { preferred.value = it }) { error?.let { throw it }; listOf("New") }
        listOf(IOException() to LoadFailure.NETWORK, SiteHttpException(503) to LoadFailure.HTTP,
            MarkupChangedException() to LoadFailure.MARKUP).forEach { (failure, expected) ->
            error = failure; model.openChoices(); advanceUntilIdle()
            assertEquals(expected, model.state.value.failure)
            assertEquals(listOf("Saved A", "Saved B"), preferred.value)
            assertTrue(model.state.value.options.isEmpty())
        }
        error = null; model.openChoices(); advanceUntilIdle()
        assertEquals(listOf("New"), model.state.value.options)
        assertNull(model.state.value.failure)
    }
    @Test fun closing_cancels_load_and_obsolete_response_cannot_replace_next_open() = runTest(dispatcher) {
        var calls = 0
        val model = TranslationSettingsViewModel(MutableStateFlow(emptyList()), {}) {
            calls++
            if (calls == 1) { withContext(NonCancellable) { delay(1000) }; listOf("Old") } else listOf("New")
        }
        model.openChoices(); runCurrent(); model.closeChoices(); model.openChoices(); advanceUntilIdle()
        assertEquals(listOf("New"), model.state.value.options)
        assertFalse(model.state.value.loading)
    }
}
