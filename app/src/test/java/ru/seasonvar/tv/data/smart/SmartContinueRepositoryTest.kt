package ru.seasonvar.tv.data.smart

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.*
import java.io.IOException

class SmartContinueRepositoryTest {
    private fun option(id: String, n: Int) = SeasonOption(id, "https://seasonvar.ru/serial-$id-test.html", "Сезон $n", n)
    private val options = listOf(option("30", 3), option("40", 4), option("50", 5))
    private fun entry(id: String, n: Int) = SeriesEntry(id, option(id, n).url, "Тест", n, "", "poster")
    private fun season(id: String, number: Int, episode: String? = "1", time: Long = 0, serial: String = "100",
        status: Int? = null, episodes: List<Episode> = listOf(Episode("a", "1"), Episode("b", "2")),
        choices: List<SeasonOption> = options, voice: String = "0"): MarkedSeason {
        val e = entry(id, number)
        return MarkedSeason(SeriesDetails(e, serial, emptyList(), emptyList(), emptyList(), choices,
            listOf(Translation(voice, "Стандартный", episodes)), voice),
            PlaybackMarkSnapshot(episode?.let { PlaybackMark(id, e.url, it, voice, time) }, status))
    }
    private fun history(vararg entries: SeriesEntry): suspend (String?) -> AccountPage = { page ->
        assertNull(page)
        AccountPage(AccountUser("1", "Тест", null), entries.toList())
    }
    @Test fun latest_marked_season_wins_and_partial_last_episode_is_not_mistaken_for_completed() = runTest {
        val old = season("30", 3, "2")
        val current = season("40", 4, "2", 58)
        val loaded = mapOf("30" to old, "40" to current)
        val reads = ConcurrentLinkedQueue<String>()
        val unrelated = (1000..1199).map { entry(it.toString(), 1) }
        val repository = SmartContinueRepository({ listOf(old.details.entry, current.details.entry) + unrelated },
            { reads += it.id; loaded.getValue(it.id) }, loadHistoryPage = history(old.details.entry))
        val page = repository.load()
        assertFalse(page.incomplete)
        assertEquals(setOf("30", "40"), reads.toSet()); assertEquals(2, reads.size)
        val result = page.entries.single()
        assertEquals("40", result.id)
        assertEquals("b", result.initialSelection.episodeId)
        assertEquals(58_000L, result.initialSelection.positionMillis)
        assertEquals("smart:100", result.key)
    }
    @Test fun watched_episode_advances_in_site_order_without_assuming_numeric_ids() = runTest {
        val current = season("40", 4, "1")
        val result = SmartContinueRepository({ emptyList() }, { error("No extra season needed") }).resolve(current)!!
        assertEquals("b", result.initialSelection.episodeId)
        assertEquals(0L, result.initialSelection.positionMillis)
    }
    @Test fun finished_season_skips_unavailable_future_seasons_and_keeps_stable_series_key() = runTest {
        val current = season("30", 3, "2")
        val empty = season("40", 4, null, episodes = emptyList())
        val next = season("50", 5, null)
        val result = SmartContinueRepository({ emptyList() }, { if (it.id == "40") empty else next }).resolve(current)!!
        assertEquals("50", result.id); assertEquals("a", result.initialSelection.episodeId)
        assertEquals("smart:100", result.key); assertNull(result.accountListMark)
    }
    @Test fun finished_latest_season_is_hidden_but_resolved_for_removal_from_cache() = runTest {
        val last = season("50", 5, "2")
        val result = SmartContinueRepository({ listOf(last.details.entry) }, { last }, loadHistoryPage = history(last.details.entry)).load()
        assertTrue(result.entries.isEmpty()); assertEquals(setOf("smart:100"), result.resolvedKeys)
        assertFalse(result.incomplete)
    }
    @Test fun only_series_with_server_history_are_returned_in_recent_first_order() = runTest {
        val a = season("30", 3, "1", 20, serial = "100", choices = listOf(option("30", 3)))
        val b = season("40", 4, "1", 20, serial = "200", choices = listOf(option("40", 4)))
        val c = season("50", 5, "1", 20, serial = "300", choices = listOf(option("50", 5)))
        val data = listOf(a, b, c).associateBy { it.details.entry.id }
        val result = SmartContinueRepository({ data.values.map { it.details.entry } }, { data.getValue(it.id) },
            loadHistoryPage = history(b.details.entry, a.details.entry)).load()
        assertEquals(listOf("smart:200", "smart:100"), result.entries.map { it.key })
        assertEquals(setOf("smart:100", "smart:200"), result.resolvedKeys)
    }
    @Test fun account_marks_without_server_history_produce_an_empty_list() = runTest {
        val marked = season("40", 4, "1", 20)
        val result = SmartContinueRepository({ listOf(marked.details.entry) }, { error("Do not load seasons without history") }).load()
        assertTrue(result.entries.isEmpty())
        assertFalse(result.incomplete)
        assertTrue(result.resolvedKeys.isEmpty())
    }
    @Test fun failed_latest_season_does_not_fall_back_to_the_finished_older_season() = runTest {
        val old = season("30", 3, "2")
        val result = SmartContinueRepository({ listOf(old.details.entry, entry("40", 4)) }, {
            if (it.id == "40") throw IOException() else old
        }, loadHistoryPage = history(old.details.entry)).load()
        assertTrue(result.incomplete); assertTrue(result.entries.isEmpty()); assertTrue(result.resolvedKeys.isEmpty())
        assertTrue(result.failure is IOException)
    }
    @Test fun explicit_watched_next_season_is_skipped_and_blacklist_is_respected() = runTest {
        val current = season("30", 3, "2")
        val watched = season("40", 4, null, status = -2)
        val next = season("50", 5, null)
        val repository = SmartContinueRepository({ emptyList() }, { if (it.id == "40") watched else next })
        assertEquals("50", repository.resolve(current)!!.id)
        assertNull(SmartContinueRepository({ emptyList() }, { season("40", 4, null, status = -3) }).resolve(current))
    }
    @Test fun history_pages_keep_server_order_until_the_end() = runTest {
        val a = season("40", 4, "1", 20, serial = "200", choices = listOf(option("40", 4)))
        val b = season("31", 3, "1", 20, serial = "100", choices = listOf(option("31", 3)))
        val requests = mutableListOf<String?>()
        val result = SmartContinueRepository({ listOf(a.details.entry, b.details.entry) }, {
            if (it.id == "40") a else b
        }, loadHistoryPage = { url ->
            requests += url
            if (url == null) AccountPage(AccountUser("1", "Тест", null), listOf(a.details.entry, a.details.entry), "next")
            else AccountPage(AccountUser("1", "Тест", null), listOf(b.details.entry))
        }).load()
        assertEquals(listOf("40", "31"), result.entries.map { it.id })
        assertEquals(listOf(null, "next"), requests)
    }
    @Test fun history_pagination_cycle_fails_instead_of_inventing_an_order() = runTest {
        try {
            SmartContinueRepository({ listOf(entry("30", 3)) }, { error("No seasons in empty pages") },
                loadHistoryPage = { AccountPage(AccountUser("1", "Тест", null), emptyList(), "same") }).load()
            fail()
        } catch (_: MarkupChangedException) { }
    }
    @Test fun missing_announced_history_page_fails_instead_of_returning_a_partial_order() = runTest {
        try {
            SmartContinueRepository({ listOf(entry("30", 3)) }, { error("No seasons in empty pages") },
                loadHistoryPage = { next ->
                    if (next == null) AccountPage(AccountUser("1", "Тест", null), emptyList(), "next") else null
                }).load()
            fail()
        } catch (_: MarkupChangedException) { }
    }
    @Test fun failed_history_season_does_not_cancel_other_series() = runTest {
        val healthy = season("40", 4, "1", 20)
        val result = SmartContinueRepository({ listOf(healthy.details.entry) }, {
            if (it.id == "999") throw IOException() else healthy
        }, loadHistoryPage = history(entry("999", 1), healthy.details.entry)).load()
        assertTrue(result.incomplete)
        assertEquals(listOf("40"), result.entries.map { it.id })
        assertEquals(setOf("smart:100"), result.resolvedKeys)
        assertEquals(listOf("999" to "IOException"), result.failedSeasons)
    }
    @Test fun history_pagination_overlaps_processing_of_already_received_seasons() = runTest(timeout = 10.seconds) {
        val current = season("30", 3, "1", 20)
        val seasonStarted = CompletableDeferred<Unit>(); val nextPageStarted = CompletableDeferred<Unit>()
        val result = SmartContinueRepository({ listOf(current.details.entry) }, {
            seasonStarted.complete(Unit); nextPageStarted.await(); current
        }, loadHistoryPage = { next ->
            if (next == null) AccountPage(AccountUser("1", "Тест", null), listOf(current.details.entry), "next")
            else {
                nextPageStarted.complete(Unit); seasonStarted.await()
                AccountPage(AccountUser("1", "Тест", null), emptyList())
            }
        }).load()
        assertEquals("30", result.entries.single().id)
    }
    @Test fun history_and_marks_are_requested_concurrently() = runTest(timeout = 10.seconds) {
        val marksStarted = CompletableDeferred<Unit>()
        val historyStarted = CompletableDeferred<Unit>()
        val repository = SmartContinueRepository({
            marksStarted.complete(Unit); historyStarted.await(); emptyList()
        }, { error("No seasons needed") }, loadHistoryPage = {
            historyStarted.complete(Unit); marksStarted.await(); AccountPage(AccountUser("1", "Тест", null), emptyList())
        })
        assertTrue(repository.load().entries.isEmpty())
    }

    @Test fun discovery_and_latest_mark_resolution_run_in_parallel_with_one_global_limit() = runTest(timeout = 10.seconds) {
        val seeds = (1..4).map { i -> season((100 + i).toString(), 1, null, serial = (1000 + i).toString(),
            choices = listOf(option((100 + i).toString(), 1), option((200 + i).toString(), 2))) }
        val targets = seeds.mapIndexed { i, old -> season((201 + i).toString(), 2, "1", 20,
            serial = old.details.serialId, choices = old.details.seasons) }
        val pages = (seeds + targets).associateBy { it.details.entry.id }
        val active = AtomicInteger(); val peak = AtomicInteger()
        val seedCount = AtomicInteger(); val targetCount = AtomicInteger()
        val seedGate = CompletableDeferred<Unit>(); val targetGate = CompletableDeferred<Unit>()
        val requests = ConcurrentLinkedQueue<String>()
        val repository = SmartContinueRepository({ targets.map { it.details.entry } }, { entry ->
            requests += entry.id
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try {
                if (entry.id.toInt() < 200) {
                    if (seedCount.incrementAndGet() == 4) seedGate.complete(Unit)
                    seedGate.await()
                } else {
                    if (targetCount.incrementAndGet() == 4) targetGate.complete(Unit)
                    targetGate.await()
                }
                pages.getValue(entry.id)
            } finally { active.decrementAndGet() }
        }, loadHistoryPage = history(*seeds.reversed().map { it.details.entry }.toTypedArray()))
        val result = repository.load()
        assertFalse(result.incomplete)
        assertEquals(targets.reversed().map { it.details.entry.id }, result.entries.map { it.id })
        assertEquals(4, peak.get()); assertEquals(0, active.get())
        assertEquals(8, requests.size); assertEquals(8, requests.toSet().size)
    }

    @Test fun duplicate_history_and_next_season_share_the_same_in_flight_page_request() = runTest {
        val old = season("30", 3, "2")
        val next = season("40", 4, null)
        val reads = ConcurrentLinkedQueue<String>()
        val result = SmartContinueRepository({ listOf(old.details.entry) }, {
            reads += it.id; if (it.id == "30") old else next
        }, loadHistoryPage = history(old.details.entry, old.details.entry, next.details.entry)).load()
        assertFalse(result.incomplete)
        assertEquals("40", result.entries.single().id)
        assertEquals(2, reads.size); assertEquals(setOf("30", "40"), reads.toSet())
    }

    @Test fun history_series_without_any_account_mark_is_hidden_without_loading_unrelated_marks() = runTest {
        val old = season("30", 3, null)
        val result = SmartContinueRepository({ listOf(entry("999", 1)) }, {
            assertEquals("30", it.id); old
        }, loadHistoryPage = history(old.details.entry)).load()
        assertTrue(result.entries.isEmpty()); assertFalse(result.incomplete)
        assertEquals(setOf("smart:100"), result.resolvedKeys)
    }

    @Test fun cancelling_refresh_cancels_all_shared_page_requests() = runTest(timeout = 10.seconds) {
        val started = CompletableDeferred<Unit>(); val active = AtomicInteger()
        val repository = SmartContinueRepository({ listOf(entry("30", 3)) }, {
            active.incrementAndGet(); started.complete(Unit)
            try { awaitCancellation() } finally { active.decrementAndGet() }
        }, loadHistoryPage = history(entry("30", 3)))
        val job = async { repository.load() }
        started.await(); job.cancelAndJoin()
        assertEquals(0, active.get())
    }

    @Test fun cache_roundtrip_preserves_zero_seek_and_rejects_external_or_mismatched_pages() = runTest {
        val item = SmartContinueRepository({ emptyList() }, { error("Unexpected") }).resolve(season("40", 4))!!
        assertEquals(item.initialSelection, SmartContinueCodec.decode(SmartContinueCodec.encode(listOf(item)))!!.single().initialSelection)
        assertNull(SmartContinueCodec.decode(SmartContinueCodec.encode(listOf(item)).replace("seasonvar.ru", "external.example")))
        assertNull(SmartContinueCodec.decode("broken"))
        val legacy = org.json.JSONObject(SmartContinueCodec.encode(listOf(item))).getJSONArray("entries").toString()
        assertNull("Old unfiltered cache must not appear before refresh", SmartContinueCodec.decode(legacy))
        assertNull(SmartContinueCodec.decode(SmartContinueCodec.encode(listOf(item)).replace("\"historyOnly\":true", "\"historyOnly\":false")))
    }
    @Test fun symbolic_episode_ids_are_resolved_without_requiring_the_visible_label_to_match() = runTest {
        val current = season("50", 5, "Osnovnoj", 22, episodes = listOf(Episode("Osnovnoj_seriya", "Основной")))
        val result = SmartContinueRepository({ emptyList() }, { error("No extra request") }).resolve(current)!!
        assertEquals("Osnovnoj_seriya", result.initialSelection.episodeId)
        assertEquals(22_000L, result.initialSelection.positionMillis)
    }
    @Test fun blocked_partial_playback_is_hidden_instead_of_reported_as_broken_markup() = runTest {
        val current = season("50", 5, "1", 20)
        val blocked = current.copy(details = current.details.copy(playbackUnavailableReason = "Недоступно", translations = emptyList()))
        assertNull(SmartContinueRepository({ emptyList() }, { error("No extra request") }).resolve(blocked))
    }

    @Test fun removed_episode_is_hidden_without_replaying_the_first_or_an_older_season() = runTest {
        val missing = season("50", 5, "13", 30)
        val result = SmartContinueRepository({ listOf(missing.details.entry) }, { missing }, loadHistoryPage = history(missing.details.entry)).load()
        assertTrue(result.entries.isEmpty());assertFalse(result.incomplete)
        assertEquals(setOf("smart:100"), result.resolvedKeys)
    }

}
