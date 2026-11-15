package ru.seasonvar.tv.data.smart

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SharedSmartContinueSourceTest {
    private val initial = SmartContinueKey("12", 1, 0, 0, 0, emptyList())

    @Test fun screen_and_launcher_share_one_calculation_and_ready_entries_without_sharing_cancellation() = runTest(timeout = 10.seconds) {
        val started = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        val calculator = SmartContinueRepository({ calls.incrementAndGet(); started.complete(Unit); gate.await(); emptyList() }, { error("No seasons") })
        val source = SharedSmartContinueSource({ initial }, calculator, backgroundScope)
        val home = async { source.load() }; runCurrent(); started.await()
        val screen = async { source.load(foreground = true) }; runCurrent()
        assertTrue(source.foreground); assertEquals(1, calls.get())
        screen.cancelAndJoin(); assertFalse(source.foreground); assertFalse(home.isCompleted)
        gate.complete(Unit); val result = home.await()
        assertSame(result.entries, source.cached("12")); assertNull(source.cached("13"))
        source.load(); assertEquals(2, calls.get())
    }

    @Test fun network_preparation_finishes_before_any_shared_calculation_starts() = runTest(timeout = 10.seconds) {
        val gate = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        val source = SharedSmartContinueSource({ initial }, SmartContinueRepository({ calls.incrementAndGet(); emptyList() },
            { error("No seasons") }), backgroundScope, prepare = { gate.await() })
        val a = async { source.load() }; val b = async { source.load(true) }; runCurrent()
        assertEquals(0, calls.get()); gate.complete(Unit)
        assertSame(a.await(), b.await()); assertEquals(1, calls.get())
    }

    @Test fun joined_consumers_receive_the_same_result_instance() = runTest(timeout = 10.seconds) {
        val started = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        val source = SharedSmartContinueSource({ initial }, SmartContinueRepository({
            calls.incrementAndGet(); started.complete(Unit); gate.await(); emptyList()
        }, { error("No seasons") }), backgroundScope)
        val home = async { source.load() }; runCurrent(); started.await()
        val screen = async { source.load(true) }; runCurrent(); gate.complete(Unit)
        assertSame(home.await(), screen.await()); assertEquals(1, calls.get())
    }

    @Test fun every_input_revision_rejects_late_old_work_and_removes_the_previous_ready_result() = runTest(timeout = 10.seconds) {
        val keys = listOf(initial.copy(owner = "13", session = 2), initial.copy(marks = 1), initial.copy(history = 1),
            initial.copy(generation = 1), initial.copy(voices = listOf("Voice")), initial.copy(historyReset = 1))
        for (changed in keys) {
            val key = AtomicReference(initial); val started = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
            val source = SharedSmartContinueSource({ key.get() }, SmartContinueRepository({
                started.complete(Unit); withContext(NonCancellable) { gate.await() }; emptyList()
            }, { error("No seasons") }), backgroundScope)
            val old = async { source.load() }; runCurrent(); started.await()
            key.set(changed); source.invalidateIfChanged(); gate.complete(Unit); old.join()
            assertTrue(old.isCancelled); assertNull(source.cached("12")); assertNull(source.cached("13"))
        }
    }
}
