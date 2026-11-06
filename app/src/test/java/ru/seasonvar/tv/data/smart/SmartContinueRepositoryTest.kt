package ru.seasonvar.tv.data.smart

import kotlinx.coroutines.test.runTest
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
    @Test fun latest_marked_season_wins_and_partial_last_episode_is_not_mistaken_for_completed() = runTest {
        val old = season("30", 3, "2")
        val current = season("40", 4, "2", 58)
        val loaded = mapOf("30" to old, "40" to current)
        val repository = SmartContinueRepository({ listOf(old.details.entry, current.details.entry) }, { loaded.getValue(it.id) },
            historyOrder = { listOf("smart:100") })
        val result = repository.load().entries.single()
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
        val result = SmartContinueRepository({ listOf(last.details.entry) }, { last }).load()
        assertTrue(result.entries.isEmpty()); assertEquals(setOf("smart:100"), result.resolvedKeys)
        assertFalse(result.incomplete)
    }
    @Test fun only_series_with_server_history_are_returned_in_recent_first_order() = runTest {
        val a = season("30", 3, "1", 20, serial = "100", choices = listOf(option("30", 3)))
        val b = season("40", 4, "1", 20, serial = "200", choices = listOf(option("40", 4)))
        val c = season("50", 5, "1", 20, serial = "300", choices = listOf(option("50", 5)))
        val data = listOf(a, b, c).associateBy { it.details.entry.id }
        val result = SmartContinueRepository({ data.values.map { it.details.entry } }, { data.getValue(it.id) },
            historyOrder = { listOf("smart:200", "smart:100") }).load()
        assertEquals(listOf("smart:200", "smart:100"), result.entries.map { it.key })
        assertTrue("Missing-history entries must also be removed from old cached results", "smart:300" in result.resolvedKeys)
    }
    @Test fun account_marks_without_server_history_produce_an_empty_list() = runTest {
        val marked = season("40", 4, "1", 20)
        val result = SmartContinueRepository({ listOf(marked.details.entry) }, { marked }).load()
        assertTrue(result.entries.isEmpty())
        assertFalse(result.incomplete)
        assertEquals(setOf("smart:100"), result.resolvedKeys)
    }
    @Test fun failed_latest_season_does_not_fall_back_to_the_finished_older_season() = runTest {
        val old = season("30", 3, "2")
        val result = SmartContinueRepository({ listOf(old.details.entry, entry("40", 4)) }, {
            if (it.id == "40") throw IOException() else old
        }).load()
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
    @Test fun history_is_paginated_until_each_series_is_found_and_duplicates_keep_the_newest_rank() = runTest {
        val user = AccountUser("1", "Тест", null)
        val requests = mutableListOf<String?>()
        val order = smartHistoryOrder(mapOf("a" to setOf("30", "31"), "b" to setOf("40"))) { url ->
            requests += url
            if (url == null) AccountPage(user, listOf(entry("40", 4), entry("40", 4)), "next")
            else AccountPage(user, listOf(entry("31", 3)), "unused")
        }
        assertEquals(listOf("b", "a"), order)
        assertEquals(listOf(null, "next"), requests)
    }
    @Test fun history_pagination_cycle_fails_instead_of_inventing_an_order() = runTest {
        try {
            smartHistoryOrder(mapOf("a" to setOf("30"))) { AccountPage(AccountUser("1", "Тест", null), emptyList(), "same") }
            fail()
        } catch (_: MarkupChangedException) { }
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
        val result = SmartContinueRepository({ listOf(missing.details.entry) }, { missing }).load()
        assertTrue(result.entries.isEmpty());assertFalse(result.incomplete)
        assertEquals(setOf("smart:100"), result.resolvedKeys)
    }

}
