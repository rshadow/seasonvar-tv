package ru.seasonvar.tv.data.images

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class WindowPrefetcherTest {
    @Test fun runs_three_loads_in_parallel_and_starts_the_next_when_a_slot_is_free() = runTest {
        val gates = (1..8).associateWith { CompletableDeferred<Unit>() }
        val started = mutableListOf<Int>(); var active = 0; var peak = 0
        val queue = WindowPrefetcher(this, Int::toString, { id: Int ->
            started += id; active++; peak = maxOf(peak, active)
            try { gates.getValue(id).await() } finally { active-- }
        })
        queue.update((1..8).toList()); runCurrent()
        assertEquals(listOf(1, 2, 3), started); assertEquals(3, active)
        gates.getValue(1).complete(Unit); runCurrent()
        assertEquals(listOf(1, 2, 3, 4), started)
        queue.clear(); runCurrent()
        assertEquals(3, peak); assertEquals(0, active); assertEquals(4, started.size)
    }
    @Test fun moving_window_keeps_overlapping_requests_and_cancels_the_rest() = runTest {
        val started = mutableListOf<Int>(); val cancelled = mutableListOf<Int>()
        val queue = WindowPrefetcher(this, Int::toString, { id: Int ->
            started += id
            try { awaitCancellation() } finally { cancelled += id }
        })
        queue.update(listOf(1, 2, 3, 4)); runCurrent()
        queue.update(listOf(2, 3, 5, 6)); runCurrent()
        assertEquals(listOf(1, 2, 3, 5), started)
        assertEquals(listOf(1), cancelled)
        queue.clear(); runCurrent()
        assertEquals(setOf(1, 2, 3, 5), cancelled.toSet())
    }
    @Test fun cancellation_cleanup_does_not_exceed_the_global_parallel_limit() = runTest {
        var active = 0; var peak = 0
        val queue = WindowPrefetcher(this, Int::toString, { _: Int ->
            active++; peak = maxOf(peak, active)
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { delay(10); active-- }
            }
        })
        queue.update(listOf(1, 2, 3)); runCurrent()
        queue.update(listOf(4, 5, 6)); runCurrent()
        assertEquals(3, active)
        advanceTimeBy(10); runCurrent(); assertEquals(3, active)
        queue.clear(); advanceUntilIdle()
        assertEquals(0, active); assertEquals(3, peak)
    }
    @Test fun failed_load_does_not_cancel_other_images_or_retry_in_the_same_window() = runTest {
        val started = mutableListOf<Int>()
        val queue = WindowPrefetcher(this, Int::toString, { id: Int -> started += id; if (id == 1) throw IOException() })
        queue.update(listOf(1, 2, 3, 4)); advanceUntilIdle()
        assertEquals(listOf(1, 2, 3, 4), started)
        queue.update(listOf(1, 2, 3, 4)); advanceUntilIdle(); assertEquals(4, started.size)
        queue.clear()
    }
    @Test fun changed_window_reprioritizes_pending_images_without_restarting_completed_ones() = runTest {
        val gate = CompletableDeferred<Unit>(); val started = mutableListOf<Int>()
        val queue = WindowPrefetcher(this, Int::toString, { id: Int -> started += id; if (id == 1) gate.await() }, parallelism = 1)
        queue.update(listOf(1, 2, 3, 4)); runCurrent()
        queue.update(listOf(1, 4, 3)); gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf(1, 4, 3), started)
        queue.update(listOf(1, 3, 4)); advanceUntilIdle(); assertEquals(3, started.size)
        queue.clear()
    }
    @Test fun switching_catalogues_shares_the_limit_with_cancelling_previous_requests() = runTest {
        val permits = kotlinx.coroutines.sync.Semaphore(3)
        var active = 0; var peak = 0
        val load: suspend (Int) -> Unit = {
            active++; peak = maxOf(peak, active)
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { delay(10); active-- }
            }
        }
        val first = WindowPrefetcher(this, Int::toString, load, permits = permits)
        val next = WindowPrefetcher(this, Int::toString, load, permits = permits)
        first.update(listOf(1, 2, 3)); runCurrent()
        first.clear(); next.update(listOf(4, 5, 6)); runCurrent()
        assertEquals(3, active)
        advanceTimeBy(10); runCurrent(); assertEquals(3, active)
        next.clear(); advanceUntilIdle()
        assertEquals(0, active); assertEquals(3, peak)
    }

}
