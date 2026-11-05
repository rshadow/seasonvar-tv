package ru.seasonvar.tv.data.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import ru.seasonvar.tv.data.account.AccountListMark
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.account.AccountSession
import ru.seasonvar.tv.data.account.AccountUser
import ru.seasonvar.tv.data.account.MarkVerificationException
import ru.seasonvar.tv.data.account.PlaybackMark
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AccountMarkWriterTest {
    private val mark = PlaybackMark("123", "https://seasonvar.ru/serial-123-Test.html", "7", "0", 61)
    private fun account(id: String = "12", version: Long = 1) = AccountSession(AccountUser(id, "Тест", null), version)
    private class MemoryOutbox : MarkOutbox {
        private val items = linkedMapOf<Pair<String, String>, PendingMark>()
        override fun all() = items.values.toList()
        override fun put(value: PendingMark) { items[value.owner to value.mark.seasonId] = value }
        override fun remove(value: PendingMark) {
            val key = value.owner to value.mark.seasonId
            if (items[key] == value) items.remove(key)
        }
    }

    @Test fun newer_pending_completion_survives_acknowledgement_of_an_older_in_flight_position() = runTest {
        val session = MutableStateFlow(account())
        val outbox = MemoryOutbox()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<PlaybackMark>()
        val writer = AccountMarkWriter(backgroundScope, session, outbox, { value, _ ->
            writes += value
            if (writes.size == 1) { started.complete(Unit); release.await() }
        })
        runCurrent()
        writer.enqueue(mark, 1); runCurrent()
        assertTrue(started.isCompleted)
        writer.enqueue(mark.copy(positionSeconds = 80), 1); runCurrent()
        writer.enqueue(mark.copy(positionSeconds = 0), 1); runCurrent()
        writer.enqueue(mark.copy(positionSeconds = 90), 1); runCurrent()
        release.complete(Unit); runCurrent()
        assertEquals(listOf(61L, 0L), writes.map { it.positionSeconds })
        assertTrue(outbox.all().isEmpty())
    }

    @Test fun offline_position_is_retained_and_retried_without_blocking_the_producer() = runTest {
        val session = MutableStateFlow(account())
        val outbox = MemoryOutbox()
        var online = false
        var calls = 0
        val writer = AccountMarkWriter(backgroundScope, session, outbox, { _, _ -> calls++; if (!online) throw IOException() }, 1_000)
        runCurrent()
        writer.enqueue(mark, 1); runCurrent()
        assertEquals(MarkSyncPhase.RETRYING, writer.state.value.phase)
        assertEquals(mark, outbox.all().single().mark)
        online = true
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, calls)
        assertTrue(outbox.all().isEmpty())
        assertEquals(MarkSyncPhase.IDLE, writer.state.value.phase)
    }

    @Test fun restored_outbox_waits_for_verified_account_and_never_sends_another_users_record() = runTest {
        val outbox = MemoryOutbox()
        outbox.put(PendingMark(markOwner(account().user)!!, mark, 1))
        outbox.put(PendingMark(markOwner(account("13").user)!!, mark.copy(episode = "8"), 2))
        val session = MutableStateFlow(AccountSession())
        val writes = mutableListOf<PlaybackMark>()
        AccountMarkWriter(backgroundScope, session, outbox, { value, _ -> writes += value })
        runCurrent()
        assertTrue(writes.isEmpty())
        assertEquals(2, outbox.all().size)
        session.value = account(); runCurrent()
        assertEquals(listOf(mark), writes)
        assertTrue(outbox.all().isEmpty())
    }

    @Test fun expired_session_on_startup_discards_restored_outbox_before_login() = runTest {
        val outbox = MemoryOutbox()
        outbox.put(PendingMark(markOwner(account().user)!!, mark, 1))
        val session = MutableStateFlow(AccountSession())
        val writes = mutableListOf<PlaybackMark>()
        AccountMarkWriter(backgroundScope, session, outbox, { value, _ -> writes += value })
        runCurrent()
        assertEquals(1, outbox.all().size)
        session.value = AccountSession(version = 1, expired = true); runCurrent()
        assertTrue(outbox.all().isEmpty())
        session.value = account(version = 2); runCurrent()
        assertTrue(writes.isEmpty())
    }

    @Test fun logout_discards_unsent_records_and_stale_producer_events() = runTest {
        val session = MutableStateFlow(account())
        val outbox = MemoryOutbox()
        var calls = 0
        val writer = AccountMarkWriter(backgroundScope, session, outbox, { _, _ -> calls++; throw IOException() }, 1_000)
        runCurrent()
        writer.enqueue(mark, 1); runCurrent()
        session.value = AccountSession(version = 2); runCurrent()
        writer.enqueue(mark, 1); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertTrue(outbox.all().isEmpty())
        assertEquals(1, calls)
    }

    @Test fun a_late_acknowledgement_cannot_remove_a_different_accounts_pending_record() = runTest {
        val session = MutableStateFlow(account())
        val outbox = MemoryOutbox()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<Pair<String, Long>>()
        val writer = AccountMarkWriter(backgroundScope, session, outbox, { value, version ->
            calls += value.episode to version
            if (version == 1L) release.await()
        })
        runCurrent()
        writer.enqueue(mark, 1); runCurrent()
        session.value = account("13", 2); runCurrent()
        writer.enqueue(mark.copy(episode = "8"), 2); runCurrent()
        release.complete(Unit); runCurrent()
        assertEquals(listOf("7" to 1L, "8" to 2L), calls)
        assertEquals(markOwner(session.value.user), writer.state.value.owner)
        assertTrue(outbox.all().isEmpty())
    }

    @Test fun protocol_failure_does_not_spin_and_can_be_retried_on_a_new_wakeup() = runTest {
        val session = MutableStateFlow(account())
        val outbox = MemoryOutbox()
        var calls = 0
        var fail = true
        val writer = AccountMarkWriter(backgroundScope, session, outbox, { _, _ ->
            calls++; if (fail) throw MarkVerificationException()
        }, 1_000)
        runCurrent()
        writer.enqueue(mark, 1); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, calls)
        assertEquals(MarkSyncPhase.FAILED, writer.state.value.phase)
        fail = false
        writer.retry(); runCurrent()
        assertEquals(2, calls)
        assertTrue(outbox.all().isEmpty())
    }

    @Test fun old_episode_events_cannot_replace_a_newer_pending_season_pointer() = runTest {
        val session = MutableStateFlow(account())
        val outbox = MemoryOutbox()
        val writer = AccountMarkWriter(backgroundScope, session, outbox, { _, _ -> throw IOException() }, 1_000)
        runCurrent()
        writer.enqueue(mark.copy(episode = "10"), 1); runCurrent()
        writer.enqueue(mark.copy(episode = "2"), 1); runCurrent()
        assertEquals("10", outbox.all().single().mark.episode)
    }

    @Test fun stale_account_cancellation_does_not_kill_the_background_writer() = runTest {
        val session = MutableStateFlow(account())
        val outbox = MemoryOutbox()
        var calls = 0
        val writer = AccountMarkWriter(backgroundScope, session, outbox, { _, _ ->
            calls++; if (calls == 1) throw CancellationException("Account changed")
        })
        runCurrent()
        writer.enqueue(mark, 1); runCurrent()
        writer.retry(); runCurrent()
        assertEquals(2, calls)
        assertTrue(outbox.all().isEmpty())
    }

    @Test fun outbox_round_trip_contains_only_mark_metadata_and_rejects_corrupt_or_foreign_targets() {
        val point = PendingMark(markOwner(account().user)!!, mark, 1)
        val encoded = MarkOutboxCodec.encode(point)
        assertEquals(point, MarkOutboxCodec.decode(encoded))
        assertFalse(encoded.contains("svid1"))
        assertFalse(encoded.contains("mp4"))
        assertNull(MarkOutboxCodec.decode(encoded.replace("seasonvar.ru", "foreign.example.test")))
        assertNull(MarkOutboxCodec.decode("{}"))
        assertNull(markOwner(AccountUser("session", "Аккаунт", null)))
    }

    @Test fun removing_discards_offline_pending_marks_but_new_playback_may_create_a_mark() = runTest {
        val session = MutableStateFlow(account())
        val outbox = MemoryOutbox()
        var writes = 0
        var deletes = 0
        val writer = AccountMarkWriter(backgroundScope, session, outbox, { _, _ -> writes++; throw IOException() },
            retryMillis = 1000, remove = { _, _ -> deletes++ })
        runCurrent()
        writer.enqueue(mark, 1); runCurrent()
        writer.removeMark(AccountListMark(mark.seasonId, mark.pageUrl), 1)
        advanceTimeBy(2000); runCurrent()
        assertEquals(1, deletes)
        assertEquals(1, writes)
        assertTrue(outbox.all().isEmpty())
        writer.enqueue(mark.copy(positionSeconds = 90), 1); runCurrent()
        assertEquals(2, writes)
    }

    @Test fun deletion_waits_for_in_flight_save_then_removes_its_newer_pending_successor() = runTest {
        val session = MutableStateFlow(account())
        val outbox = MemoryOutbox()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val writer = AccountMarkWriter(backgroundScope, session, outbox, { _, _ -> calls += "save"; release.await() },
            remove = { _, _ -> calls += "remove" })
        runCurrent()
        writer.enqueue(mark, 1); runCurrent()
        val deletion = launch { writer.removeMark(AccountListMark(mark.seasonId, mark.pageUrl), 1) }
        runCurrent()
        writer.enqueue(mark.copy(positionSeconds = 80), 1); runCurrent()
        assertEquals(listOf("save"), calls)
        release.complete(Unit); runCurrent(); deletion.join()
        assertEquals(listOf("save", "remove"), calls)
        assertTrue(outbox.all().isEmpty())
    }

    @Test fun enqueue_scheduled_before_removal_cannot_resurrect_a_deleted_mark() = runTest {
        val session = MutableStateFlow(account())
        val outbox = MemoryOutbox()
        val writes = mutableListOf<PlaybackMark>()
        val writer = AccountMarkWriter(backgroundScope, session, outbox, { value, _ -> writes += value }, remove = { _, _ -> })
        runCurrent()
        writer.enqueue(mark, 1)
        writer.removeMark(AccountListMark(mark.seasonId, mark.pageUrl), 1)
        runCurrent()
        assertTrue(writes.isEmpty())
        assertTrue(outbox.all().isEmpty())
    }
    @Test fun status_change_waits_for_a_running_save_and_discards_older_pending_events() = runTest {
        val session = MutableStateFlow(account()); val outbox = MemoryOutbox(); val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val writer = AccountMarkWriter(backgroundScope, session, outbox, { _, _ -> calls += "save"; release.await() },
            changeStatus = { _, _, _ -> calls += "status"; ru.seasonvar.tv.data.account.PlaybackMarkSnapshot(status = -1) })
        runCurrent(); writer.enqueue(mark, 1); runCurrent()
        val change = launch { writer.setListStatus(ru.seasonvar.tv.data.SeriesEntry(mark.seasonId, mark.pageUrl, "Тест", 1, "", ""),
            ru.seasonvar.tv.data.account.AccountListStatus.WANT_TO_WATCH, 1) }
        runCurrent(); writer.enqueue(mark.copy(positionSeconds = 80), 1); runCurrent()
        assertEquals(listOf("save"), calls); release.complete(Unit); runCurrent(); change.join()
        assertEquals(listOf("save", "status"), calls); assertTrue(outbox.all().isEmpty())
    }
    @Test fun a_scheduled_playback_event_cannot_override_an_explicit_list_status() = runTest {
        val session = MutableStateFlow(account()); val outbox = MemoryOutbox(); var writes = 0
        val writer = AccountMarkWriter(backgroundScope, session, outbox, { _, _ -> writes++ },
            changeStatus = { _, _, _ -> ru.seasonvar.tv.data.account.PlaybackMarkSnapshot(status = -2) })
        runCurrent(); writer.enqueue(mark, 1)
        writer.setListStatus(ru.seasonvar.tv.data.SeriesEntry(mark.seasonId, mark.pageUrl, "Тест", 1, "", ""),
            ru.seasonvar.tv.data.account.AccountListStatus.WATCHED, 1)
        runCurrent(); assertEquals(0, writes); assertTrue(outbox.all().isEmpty())
    }

}
