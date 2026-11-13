package ru.seasonvar.tv.ui.reviews

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
import ru.seasonvar.tv.data.reviews.*
import ru.seasonvar.tv.ui.catalog.LoadFailure
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun entry(id: String) = SeriesReview(id, "Автор", null, "Текст $id")
    private fun first() = ReviewsPage(listOf(entry("one")), 3, "41")

    @Test fun loads_only_the_initial_page_until_more_is_requested() = runTest(dispatcher) {
        var calls = 0
        val model = ReviewsViewModel({ first() }, { cursor, _ ->
            assertEquals("41", cursor); calls++; listOf(entry("two"), entry("three"))
        })
        model.refresh(); advanceUntilIdle()
        assertEquals(1, model.state.value.entries.size)
        assertEquals(0, calls)
        model.more(); advanceUntilIdle()
        assertEquals(listOf("one:0", "two:0", "three:0"), model.state.value.entries.map { it.key })
        assertEquals(3, model.state.value.total)
        model.more(); advanceUntilIdle()
        assertEquals(1, calls)
    }

    @Test fun failure_keeps_initial_reviews_and_waits_for_an_explicit_retry() = runTest(dispatcher) {
        var calls = 0
        val model = ReviewsViewModel({ first() }, { _, _ ->
            calls++
            if (calls == 1) throw IOException()
            listOf(entry("two"), entry("three"))
        })
        model.refresh(); advanceUntilIdle(); model.more(); advanceUntilIdle()
        assertEquals(1, model.state.value.entries.size)
        assertEquals(LoadFailure.NETWORK, model.state.value.moreFailure)
        assertFalse(model.state.value.loadingMore)
        model.more(); advanceUntilIdle(); assertEquals(1, calls)
        model.more(retry = true); advanceUntilIdle()
        assertEquals(3, model.state.value.entries.size)
        assertNull(model.state.value.moreFailure)
    }

    @Test fun concurrent_more_requests_do_not_duplicate_the_tail() = runTest(dispatcher) {
        var calls = 0
        val model = ReviewsViewModel({ first() }, { _, _ -> calls++; delay(100); listOf(entry("two")) })
        model.refresh(); advanceUntilIdle()
        model.more(); model.more(); advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(2, model.state.value.entries.size)
    }

    @Test fun refresh_keeps_the_selected_tail_available_after_the_atomic_update() = runTest(dispatcher) {
        val forces = mutableListOf<Boolean>()
        val model = ReviewsViewModel({ first() }, { _, force -> forces += force; listOf(entry("two"), entry("three")) })
        model.refresh(); advanceUntilIdle(); model.more(); advanceUntilIdle()
        val key = model.state.value.entries.last().key
        model.refresh(force = true, preserveKey = key)
        assertEquals(3, model.state.value.entries.size)
        advanceUntilIdle()
        assertTrue(model.state.value.entries.any { it.key == key })
        assertEquals(listOf(false, true), forces)
    }

    @Test fun obsolete_tail_cannot_append_after_a_new_refresh() = runTest(dispatcher) {
        val model = ReviewsViewModel({ first() }, { _, _ ->
            withContext(NonCancellable) { delay(1000) }; listOf(entry("two"))
        })
        model.refresh(); advanceUntilIdle(); model.more(); runCurrent()
        model.refresh(clear = true); advanceUntilIdle()
        assertEquals(listOf("one:0"), model.state.value.entries.map { it.key })
        assertFalse(model.state.value.loadingMore)
    }

    @Test fun unexpected_empty_tail_does_not_masquerade_as_a_complete_list() = runTest(dispatcher) {
        val model = ReviewsViewModel({ first() }, { _, _ -> emptyList() })
        model.refresh(); advanceUntilIdle(); model.more(); advanceUntilIdle()
        assertEquals(LoadFailure.MARKUP, model.state.value.moreFailure)
        assertEquals("41", model.state.value.nextCursor)
        assertEquals(1, model.state.value.entries.size)
    }

    @Test fun incomplete_refresh_preserves_the_previously_loaded_texts() = runTest(dispatcher) {
        var empty = false
        val model = ReviewsViewModel({ first() }, { _, _ -> if (empty) emptyList() else listOf(entry("two"), entry("three")) })
        model.refresh(); advanceUntilIdle(); model.more(); advanceUntilIdle()
        val previous = model.state.value.entries
        empty = true
        model.refresh(force = true, preserveKey = previous.last().key); advanceUntilIdle()
        assertEquals(previous, model.state.value.entries)
        assertEquals(LoadFailure.MARKUP, model.state.value.failure)
        assertFalse(model.state.value.loading)
    }
}
