package ru.seasonvar.tv.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.settings.CacheSettings
import ru.seasonvar.tv.data.work.BackgroundCpu
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BackgroundCpuTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun cancellation_during_parsing_closes_the_unpublished_window() = kotlinx.coroutines.test.runTest {
        val html = "<div class='lside-serial' data-serial='list'>" + (1..600).joinToString("") {
            "<a data-id='$it' href='/serial-$it-Fixture.html'>Сериал $it</a>"
        } + "</div>"
        var closed = false
        var reads = 0
        lateinit var parsing: Deferred<ParsedSeriesEntries>
        val reader = object : java.io.StringReader(html) {
            override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                reads++; parsing.cancel()
                return super.read(buffer, offset, length)
            }
            override fun close() { closed = true; super.close() }
        }
        parsing = async(start = CoroutineStart.LAZY) {
            readCatalogEntries(reader, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        }
        parsing.start()
        runCurrent()
        parsing.join()
        assertTrue(parsing.isCancelled); assertTrue(reads > 0); assertTrue(closed)
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun cancelling_queued_parsing_closes_its_input_without_reading() = kotlinx.coroutines.test.runTest {
        var closed = false
        val reader = object : java.io.StringReader("") {
            override fun read(buffer: CharArray, offset: Int, length: Int): Int = error("Queued input must not be read")
            override fun close() { closed = true; super.close() }
        }
        val parsing = async(start = CoroutineStart.UNDISPATCHED) {
            readCatalogEntries(reader, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        }
        parsing.cancelAndJoin()
        assertTrue(closed)
    }
    @Test fun device_budget_scales_and_supports_single_processor() {
        assertEquals(1, CacheSettings.cpuParallelism(1))
        assertEquals(2, CacheSettings.cpuParallelism(2))
        assertEquals(4, CacheSettings.cpuParallelism(4))
        assertEquals(8, CacheSettings.cpuParallelism(8))
    }

    @Test fun shared_budget_bounds_computation_and_skips_cancelled_queued_work() = runBlocking {
        Executors.newFixedThreadPool(8).asCoroutineDispatcher().use { threads ->
            val dispatcher = BackgroundCpu.createDispatcher(4, threads)
            val entered = CountDownLatch(4)
            val release = CountDownLatch(1)
            val calls = AtomicInteger()
            val jobs = (1..4).map { async(dispatcher) {
                calls.incrementAndGet(); entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            } }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val queued = async(dispatcher) { calls.incrementAndGet() }
                queued.cancel()
                release.countDown()
                jobs.awaitAll(); queued.join()
                assertEquals(4, calls.get())
            } finally { release.countDown() }
        }
    }

    @Test fun suspended_io_does_not_hold_the_only_computation_slot() = runBlocking {
        val dispatcher = BackgroundCpu.createDispatcher(1)
        val waiting = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val first = launch(dispatcher) { waiting.complete(Unit); response.await() }
        try {
            withTimeout(10000) {
                waiting.await()
                async(dispatcher) { response.complete(Unit) }.await()
                first.join()
            }
        } finally { first.cancel(); response.complete(Unit) }
    }
}
