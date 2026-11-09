package ru.seasonvar.tv.data.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.SiteHttpException
import ru.seasonvar.tv.data.account.AccountSession
import ru.seasonvar.tv.data.account.AccountUser
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackHistoryWriterTest {
    private val event = PlaybackHistoryEvent("123", "https://seasonvar.ru/serial-123-test.html", "40", "900", "0", "7")
    private fun account(version: Long = 1) = AccountSession(AccountUser("12", "Тест", null), version)

    @Test fun successful_write_is_not_verified_or_retried() = runTest {
        val session = MutableStateFlow(account()); val writes = mutableListOf<PlaybackHistoryEvent>()
        val writer = PlaybackHistoryWriter(backgroundScope, session, { value, version, _ -> assertEquals(1L, version); writes += value })
        writer.enqueue(event, 1); runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf(event), writes)
    }

    @Test fun guest_and_stale_player_events_are_never_queued_for_another_session() = runTest {
        val session = MutableStateFlow(AccountSession()); var calls = 0
        val writer = PlaybackHistoryWriter(backgroundScope, session, { _, _, _ -> calls++ })
        writer.enqueue(event, 0); session.value = account(2); runCurrent()
        writer.enqueue(event, 1); runCurrent(); assertEquals(0, calls)
        writer.enqueue(event, 2); runCurrent(); assertEquals(1, calls)
    }

    @Test fun transient_failures_retry_in_order_and_stop_after_success() = runTest {
        val session = MutableStateFlow(account()); val writes = mutableListOf<String>()
        val failures = listOf(IOException(), SiteHttpException(503), SiteHttpException(429))
        for (failure in failures) {
            var calls = 0
            val writer = PlaybackHistoryWriter(backgroundScope, session, { value, _, _ ->
                calls++; if (calls == 1) throw failure; writes += value.playlistId
            }, retryMillis = 100)
            writer.enqueue(event, 1); writer.enqueue(event.copy(playlistId = "8"), 1)
            runCurrent(); assertEquals(1, calls)
            advanceTimeBy(100); runCurrent(); assertEquals(3, calls)
        }
        assertEquals(listOf("7", "8", "7", "8", "7", "8"), writes)
    }

    @Test fun terminal_errors_do_not_retry_and_transient_errors_have_three_attempts() = runTest {
        val session = MutableStateFlow(account())
        for ((error, expected) in listOf(SiteHttpException(403) to 1, IllegalStateException() to 1, IOException() to 3)) {
            var calls = 0
            val writer = PlaybackHistoryWriter(backgroundScope, session, { _, _, _ -> calls++; throw error }, retryMillis = 100)
            writer.enqueue(event, 1); runCurrent(); advanceTimeBy(10_000); runCurrent()
            assertEquals(expected, calls)
        }
    }

    @Test fun logout_cancels_inflight_write_and_discards_queued_events_even_for_the_same_user() = runTest {
        val session = MutableStateFlow(account()); val calls = mutableListOf<Long>(); var cancelled = false
        val writer = PlaybackHistoryWriter(backgroundScope, session, { _, version, _ ->
            calls += version
            if (version == 1L) try { awaitCancellation() } finally { cancelled = true }
        })
        writer.enqueue(event, 1); writer.enqueue(event.copy(playlistId = "8"), 1); runCurrent()
        session.value = AccountSession(version = 2); runCurrent(); assertTrue(cancelled)
        session.value = account(3); runCurrent(); assertEquals(listOf(1L), calls)
        writer.enqueue(event, 3); runCurrent(); assertEquals(listOf(1L, 3L), calls)
    }

    @Test fun clearing_history_discards_old_retry_and_queue_but_accepts_new_playback() = runTest {
        val session = MutableStateFlow(account()); val reset = MutableStateFlow(0L)
        val calls = mutableListOf<String>()
        val writer = PlaybackHistoryWriter(backgroundScope, session, { value, _, epoch ->
            calls += value.playlistId
            if (epoch == 0L) throw IOException()
        }, retryMillis = 100, reset = reset)
        writer.enqueue(event, 1); writer.enqueue(event.copy(playlistId = "8"), 1); runCurrent()
        reset.value = 1
        writer.enqueue(event.copy(playlistId = "9"), 1)
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf("7", "9"), calls)
    }

    @Test fun queued_events_expire_instead_of_being_replayed_with_a_later_server_date() = runTest {
        val session = MutableStateFlow(account()); val gate = CompletableDeferred<Unit>(); var calls = 0
        val writer = PlaybackHistoryWriter(backgroundScope, session, { _, _, _ -> calls++; gate.await() }, nowMillis = { currentTime })
        writer.enqueue(event, 1); writer.enqueue(event.copy(playlistId = "8"), 1); runCurrent()
        advanceTimeBy(30_001); runCurrent(); gate.complete(Unit); runCurrent()
        assertEquals(1, calls)
    }
}
