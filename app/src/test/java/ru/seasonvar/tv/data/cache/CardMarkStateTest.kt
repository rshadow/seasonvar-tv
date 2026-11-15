package ru.seasonvar.tv.data.cache

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.*
import ru.seasonvar.tv.data.settings.CacheSettings

@OptIn(ExperimentalCoroutinesApi::class)
class CardMarkStateTest {
    @get:Rule val folder = TemporaryFolder()
    private val entry = SeriesEntry("123", "https://seasonvar.ru/serial-123-Test.html", "Тест", 1, "", "")
    private val point = PlaybackMark("123", entry.url, "3", "0", 90)
    private fun TestScope.cache(capacity: Int = CacheSettings.CARD_DATA_CAPACITY) = CardDataCache(
        HtmlResponseCache(folder.newFolder(), now = { 1_800_000_000_000L }), capacity, now = { 1_800_000_000_000L }, workers = backgroundScope)

    @Test fun only_observers_of_the_changed_season_receive_a_new_value() = runTest {
        val cache = cache()
        val first = mutableListOf<CardMarkChange?>(); val second = mutableListOf<CardMarkChange?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { cache.observeMark("123").collect { first += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { cache.observeMark("124").collect { second += it } }
        val change = cache.applyMark(entry, PlaybackMarkSnapshot(mark = point), 1)
        runCurrent()
        assertEquals(listOf(null, change), first)
        assertEquals(listOf<CardMarkChange?>(null), second)
        assertSame(change, cache.mark("123"))
    }

    @Test fun shared_mark_preserves_each_history_records_identity_and_opening_context() = runTest {
        val cache = cache()
        cache.applyMark(entry, PlaybackMarkSnapshot(mark = point), 1)
        for (voice in listOf("0", "1")) {
            val row = entry.copy(sourceKey = "history-$voice", historyVisitedAt = "date-$voice",
                initialSelection = EntrySelection(episodeLabel = "1", translationId = voice))
            val updated = cache.entry(row)
            assertTrue(updated.isWatching)
            assertEquals(row.key, updated.key)
            assertEquals(row.initialSelection, updated.initialSelection)
            assertEquals(row.historyVisitedAt, updated.historyVisitedAt)
        }
    }

    @Test fun removed_and_negative_marks_override_old_list_flags_and_progress() = runTest {
        val cache = cache()
        val old = entry.copy(isWatching = true, accountListMark = AccountListMark(entry.id, entry.url),
            accountMark = AccountMark("Старый прогресс", "Старый статус"))
        for (status in listOf<Int?>(null, -1, -2, -3)) {
            cache.applyMark(entry, PlaybackMarkSnapshot(status = status), 1)
            val updated = cache.entry(old)
            assertFalse(updated.isWatching)
            assertNull(updated.accountMark)
            assertNull(updated.accountProgress)
            assertEquals(status != null, updated.accountListMark != null)
        }
    }

    @Test fun progress_uses_known_total_and_never_guesses_a_non_numeric_episode() = runTest {
        val cache = cache()
        val old = entry.copy(accountProgress = AccountProgress(episodes = SeasonEpisodeProgress(entry.id, ProgressCounter(1, 8))))
        cache.applyMark(entry, PlaybackMarkSnapshot(mark = point), 1)
        assertEquals(ProgressCounter(3, 8), cache.entry(old).accountProgress?.episodes?.counter)
        cache.applyMark(entry, PlaybackMarkSnapshot(mark = point.copy(episode = "Special")), 2)
        assertNull(cache.entry(old).accountProgress)
        cache.applyMark(entry, PlaybackMarkSnapshot(status = -2), 3)
        assertEquals(ProgressCounter(8, 8), cache.entry(old).accountProgress?.episodes?.counter)
    }

    @Test fun logout_clears_live_observers_and_local_overrides() = runTest {
        val cache = cache(); val values = mutableListOf<CardMarkChange?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { cache.observeMark(entry.id).collect { values += it } }
        cache.applyMark(entry, PlaybackMarkSnapshot(mark = point), 1)
        cache.clearPrivate(); runCurrent()
        assertNull(values.last()); assertNull(cache.mark(entry.id)); assertTrue(cache.changesSince(0).isEmpty())
    }

    @Test fun observed_records_survive_eviction_and_revisions_collapse_per_season() = runTest {
        val cache = cache(1)
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { cache.observeMark(entry.id).collect {} }
        cache.applyMark(entry, PlaybackMarkSnapshot(mark = point), 1)
        val latest = cache.applyMark(entry, PlaybackMarkSnapshot(status = -2), 2)
        val other = cache.applyMark(entry.copy(id = "124", url = "https://seasonvar.ru/serial-124-Test.html"), PlaybackMarkSnapshot(), 3)
        assertSame(latest, cache.mark(entry.id)); assertEquals(listOf(latest, other), cache.changesSince(1))
        job.cancel(); runCurrent()
        assertTrue(cache.size <= 1)
    }

    @Test fun an_old_in_flight_html_response_cannot_overwrite_a_confirmed_mark() = runTest {
        val cache = cache(); val disk = HtmlResponseCache(folder.newFolder())
        val gate = CompletableDeferred<Unit>()
        val load = async { cache.load(entry.copy(isWatching = true), disk.capture(), false, {
            gate.await()
            CachedHtml("<div class='pgs-sinfo' data-id-season='123' data-id-serial='99'><div class='pgs-sinfo-info'><p>Описание</p></div></div>", 1_800_000_000_000L)
        }) }
        runCurrent()
        cache.applyMark(entry, PlaybackMarkSnapshot(), 1)
        gate.complete(Unit); load.await()
        assertFalse(cache.entry(entry.copy(isWatching = true)).isWatching)
        assertNotNull(cache.description(entry.id))
    }
    @Test fun a_fresh_server_read_can_replace_local_state_but_an_old_scope_cannot_expose_it() = runTest {
        var scope = CacheScope(true, 1, "12")
        val disk = HtmlResponseCache(folder.newFolder(), { scope }, { scope }, { 1_800_000_000_000L })
        disk.selectPrivateOwner("12")
        val cache = CardDataCache(disk, now = { 1_800_000_000_000L }, workers = backgroundScope)
        cache.applyMark(entry, PlaybackMarkSnapshot(mark = point), 1)
        cache.load(entry, disk.capture(), true, {
            CachedHtml("<div class='pgs-sinfo' data-id-season='123' data-id-serial='99'><div class='pgs-sinfo-info'><p>Описание</p></div></div>" +
                "<script>var mark={\"href\":\"${entry.url}#rewind=-2_seriya\",\"trans\":\"0\"};</script>", 1_800_000_000_000L)
        })
        assertEquals(AccountListStatus.WATCHED, cache.mark(entry.id)?.status)
        scope = CacheScope(true, 2, "13")
        assertNull(cache.mark(entry.id))
        assertTrue(cache.changesSince(0).isEmpty())
        assertFalse(cache.entry(entry).isWatching)
    }

    @Test fun native_eviction_does_not_restore_a_stale_list_badge() = runTest {
        val cache = cache(1); val disk = HtmlResponseCache(folder.newFolder())
        suspend fun load(value: SeriesEntry) = cache.load(value, disk.capture(), false, {
            CachedHtml("<div class='pgs-sinfo' data-id-season='${value.id}' data-id-serial='99'><div class='pgs-sinfo-info'><p>Описание</p></div></div>", 1_800_000_000_000L)
        })
        load(entry)
        cache.applyMark(entry, PlaybackMarkSnapshot(), 1)
        load(entry.copy(id = "124", url = "https://seasonvar.ru/serial-124-Test.html"))
        assertEquals(1, cache.size)
        assertNull(cache.description(entry.id))
        assertNotNull(cache.mark(entry.id))
        assertFalse(cache.entry(entry.copy(isWatching = true)).isWatching)
    }

    @Test fun server_marks_follow_the_native_limit_and_disk_can_restore_them_without_replacing_local_changes() = runTest {
        val scope = CacheScope(true, 1, "12")
        val disk = HtmlResponseCache(folder.newFolder(), { scope }, { scope }, { 1_800_000_000_000L })
        disk.selectPrivateOwner("12")
        val cache = CardDataCache(disk, 1, now = { 1_800_000_000_000L }, workers = backgroundScope)
        suspend fun load(value: SeriesEntry, fromDisk: Boolean) = cache.load(value, disk.capture(), true, {
            CachedHtml("<div class='pgs-sinfo' data-id-season='${value.id}' data-id-serial='99'><div class='pgs-sinfo-info'><p>Описание</p></div></div>" +
                "<script>var mark={\"href\":\"${value.url}#rewind=-2_seriya\",\"trans\":\"0\"};</script>", 1_800_000_000_000L, fromDisk)
        })
        load(entry, false)
        assertEquals(AccountListStatus.WATCHED, cache.mark(entry.id)?.status)
        load(entry.copy(id = "124", url = "https://seasonvar.ru/serial-124-Test.html"), false)
        assertEquals(1, cache.size)
        assertNull(cache.mark(entry.id))
        load(entry, true)
        assertEquals(AccountListStatus.WATCHED, cache.mark(entry.id)?.status)
        cache.applyMark(entry, PlaybackMarkSnapshot(mark = point), 1)
        load(entry, true)
        assertEquals(point, cache.mark(entry.id)?.snapshot?.mark)
    }

}
