package ru.seasonvar.tv.data.cache

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.settings.CacheSettings

@OptIn(ExperimentalCoroutinesApi::class)
class CardDataCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private var scope = CacheScope(false, 0)
    private var now = 1_800_000_000_000L
    private fun entry(id: Int) = SeriesEntry("$id", "https://seasonvar.ru/serial-$id-Test.html", "Сериал $id", 1, "", "")
    private fun html(id: String) = """<div class='pgs-sinfo' data-id-season='$id' data-id-serial='99'>
        <div class='pgs-sinfo-info'><p>Описание $id</p></div></div>"""
    private fun TestScope.cache(capacity: Int? = null): Pair<HtmlResponseCache, CardDataCache> {
        val disk = HtmlResponseCache(folder.newFolder(), { scope }, { scope }, { now })
        return disk to if (capacity == null) CardDataCache(disk, now = { now }, workers = backgroundScope)
            else CardDataCache(disk, capacity, { now }, backgroundScope)
    }
    private suspend fun CardDataCache.get(entry: SeriesEntry, disk: HtmlResponseCache) =
        load(entry, disk.capture(), false, { CachedHtml(html(entry.id), now) })

    @Test fun shared_native_description_and_details_keep_each_list_records_episode_and_translation_context() = runTest {
        val (disk, cache) = cache()
        val original = entry(1).copy(sourceKey = "history-1", historyVisitedAt = "first",
            initialSelection = EntrySelection(episodeId = "2", translationId = "voice1"))
        cache.get(original, disk)
        val other = original.copy(sourceKey = "history-2", historyVisitedAt = "second",
            initialSelection = EntrySelection(episodeId = "9", translationId = "voice2"))
        val ready = checkNotNull(cache.details(other, disk.capture()))
        assertEquals(other.key, ready.entry.key); assertEquals(other.initialSelection, ready.entry.initialSelection)
        assertEquals(other.historyVisitedAt, ready.entry.historyVisitedAt)
        assertEquals(listOf("Описание 1"), cache.description("1")?.paragraphs)
        assertEquals(1, cache.size)
    }
    @Test fun logout_drops_only_private_data_and_common_descriptions_survive_account_switches() = runTest {
        val (disk, cache) = cache()
        scope = CacheScope(true, 1, "12"); disk.selectPrivateOwner("12")
        val original = entry(1).copy(accountMark = AccountMark("12", "Просмотрено"))
        cache.get(original, disk)
        assertEquals(original.accountMark, cache.details(entry(1), disk.capture())?.entry?.accountMark)
        scope = CacheScope(false, 2); disk.clearPrivate(); cache.clearPrivate()
        assertNotNull(cache.description("1")); assertNull(cache.details(entry(1), disk.capture()))
        scope = CacheScope(true, 3, "13"); disk.selectPrivateOwner("13")
        assertNotNull(cache.description("1")); assertNull(cache.details(entry(1), disk.capture()))
        cache.get(entry(1), disk)
        assertNull(cache.details(entry(1), disk.capture())?.entry?.accountMark)
    }
    @Test fun loading_the_same_season_has_one_worker_and_cancelling_one_reader_keeps_the_other() = runTest {
        val (disk, cache) = cache(); val gate = CompletableDeferred<Unit>(); var calls = 0
        suspend fun load() = cache.load(entry(1), disk.capture(), false, { calls++; gate.await(); CachedHtml(html("1"), now) })
        val first = async { load() }; val second = async { load() }; runCurrent()
        assertEquals(1, calls)
        first.cancel(); runCurrent(); assertFalse(second.isCompleted)
        gate.complete(Unit); assertEquals(listOf("Описание 1"), second.await().description.paragraphs)
        assertEquals(1, calls)
    }
    @Test fun an_old_scope_cannot_read_ready_private_data_during_account_switch() = runTest {
        val (disk, cache) = cache(); scope = CacheScope(true, 1, "12"); disk.selectPrivateOwner("12")
        val old = disk.capture(); cache.get(entry(1).copy(accountMark = AccountMark("5", "Смотрю")), disk)
        scope = CacheScope(true, 2, "13")
        val result = runCatching { cache.load(entry(1), old, false, { error("No request") }) }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertNotNull(cache.description("1"))
    }
    @Test fun last_reader_cancels_the_worker_and_late_private_completion_cannot_refill_the_cache() = runTest {
        val (disk, cache) = cache(); scope = CacheScope(true, 1, "12"); disk.selectPrivateOwner("12")
        val gate = CompletableDeferred<Unit>(); var cancelled = false
        val first = async { cache.load(entry(1), disk.capture(), false, {
            try { withContext(NonCancellable) { gate.await() }; CachedHtml(html("1"), now) }
            finally { cancelled = true }
        }) }
        runCurrent(); first.cancel(); runCurrent()
        scope = CacheScope(false, 2); disk.clearPrivate(); cache.clearPrivate()
        gate.complete(Unit); runCurrent()
        assertTrue(cancelled); assertNull(cache.description("1")); assertEquals(0, cache.size)
    }
    @Test fun lru_evicts_old_entries_but_retains_the_active_window() = runTest {
        val (disk, cache) = cache(2); val owner = Any()
        cache.get(entry(1), disk); cache.retain(owner, setOf("1"))
        cache.get(entry(2), disk); cache.get(entry(3), disk)
        assertEquals(2, cache.size); assertNotNull(cache.description("1")); assertNull(cache.description("2"))
        cache.retain(owner, emptySet()); cache.get(entry(4), disk)
        assertEquals(2, cache.size)
    }
    @Test fun default_capacity_uses_cache_settings_and_evicts_the_oldest() = runTest {
        val (disk, cache) = cache()
        val capacity = CacheSettings.CARD_DATA_CAPACITY
        for (id in 1..(capacity + 1)) cache.get(entry(id), disk)
        assertEquals(capacity, cache.size); assertNull(cache.description("1")); assertNotNull(cache.description("${capacity + 1}"))
    }
    @Test fun native_ttl_does_not_restart_when_an_entry_is_read_again() = runTest {
        val (disk, cache) = cache(); cache.get(entry(1), disk)
        now += HtmlResponseCache.DAY - 1; assertNotNull(cache.description("1"))
        now++; assertNull(cache.description("1")); assertEquals(0, cache.size)
    }
    @Test fun malformed_disk_html_gets_one_network_retry_and_never_becomes_native_data() = runTest {
        val (disk, cache) = cache(); val modes = mutableListOf<Boolean>()
        val loaded = cache.load(entry(1), disk.capture(), false, { network ->
            modes += network
            if (network) CachedHtml(html("1"), now) else CachedHtml("<p>broken</p>", now, true)
        })
        assertEquals(listOf(false, true), modes); assertEquals(listOf("Описание 1"), loaded.description.paragraphs)
    }
    @Test fun failed_load_is_not_cached_and_can_be_retried() = runTest {
        val (disk, cache) = cache()
        assertTrue(runCatching { cache.load(entry(1), disk.capture(), false, { throw java.io.IOException() }) }.isFailure)
        assertEquals(0, cache.size); cache.get(entry(1), disk); assertEquals(1, cache.size)
    }
}
