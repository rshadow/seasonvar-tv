package ru.seasonvar.tv.data.home

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.*

class HomePlaybackResolverTest {
    private val seasons = listOf(SeasonOption("10", "https://seasonvar.ru/serial-10-first.html", "1", 1),
        SeasonOption("20", "https://seasonvar.ru/serial-20-second.html", "2", 2))
    private fun entry(id: String = "10") = SeriesEntry(id, seasons.single { it.id == id }.url, "Сериал", if (id == "10") 1 else 2, "", "poster")
    private fun details(e: SeriesEntry, unavailable: Boolean = false) = SeriesDetails(e, "100", emptyList(), emptyList(), emptyList(), seasons,
        listOf(Translation("0", "Стандартная", listOf(Episode("a", "1"), Episode("b", "2"))),
            Translation("voice", "Озвучка", listOf(Episode("v1", "1"), Episode("v2", "2")))), "0",
        if (unavailable) "Заблокирован" else null)
    private fun resolver(local: LocalPlayback? = null, target: AccountContinuation? = null,
        preferences: List<String> = emptyList(), read: suspend (SeriesEntry) -> SeriesDetails = { details(it) }) =
        HomePlaybackResolver(read, { local }, { target }, { preferences })

    @Test fun all_three_feeds_resume_the_same_saved_episode_and_voice_ignoring_card_hints() = runTest {
        val saved = LocalPlayback(ResumePoint("10", "voice", "v2", 53_000), false)
        val requests = HomeChannel.entries.map { feed -> resolver(saved).resolve(entry("20").copy(
            sourceKey = feed.storageId, initialSelection = EntrySelection("a", "1", "0", positionMillis = 0))) }
        assertEquals(1, requests.distinct().size)
        assertEquals("10", requests.first().seasonId); assertEquals("v2", requests.first().episodeId)
        assertEquals("voice", requests.first().translationId); assertEquals(53_000L, requests.first().resumePositionMillis)
    }
    @Test fun unseen_series_starts_with_first_season_even_when_newest_card_points_to_second() = runTest {
        val request = resolver().resolve(entry("20"))
        assertEquals("10", request.seasonId); assertEquals("a", request.episodeId); assertEquals(0L, request.resumePositionMillis)
    }
    @Test fun first_episode_uses_preferred_translation() = runTest {
        val request = resolver(preferences = listOf("Озвучка")).resolve(entry())
        assertEquals("voice", request.translationId); assertEquals("v1", request.episodeId)
    }
    @Test fun newer_account_mark_wins_over_stale_local_progress_in_every_feed() = runTest {
        val local = LocalPlayback(ResumePoint("10", "0", "a", 40_000), false)
        val target = AccountContinuation(entry("20").copy(initialSelection = EntrySelection("v2", "2", "voice", positionMillis = 15_000)))
        for (feed in HomeChannel.entries) {
            val request = resolver(local, target).resolve(entry().copy(sourceKey = feed.storageId))
            assertEquals("20", request.seasonId); assertEquals("v2", request.episodeId); assertEquals(15_000L, request.resumePositionMillis)
        }
    }
    @Test fun completed_local_episode_advances_and_does_not_restart() = runTest {
        val request = resolver(LocalPlayback(ResumePoint("10", "voice", "v1", 0), true)).resolve(entry("20"))
        assertEquals("v2", request.episodeId); assertEquals("10", request.seasonId); assertEquals(0L, request.resumePositionMillis)
    }
    @Test fun completed_season_moves_to_next_and_preserves_voice() = runTest {
        val request = resolver(LocalPlayback(ResumePoint("10", "voice", "v2", 0), true)).resolve(entry())
        assertEquals("20", request.seasonId); assertEquals("v1", request.episodeId); assertEquals("voice", request.translationId)
    }
    @Test fun account_selected_next_episode_does_not_inherit_local_time() = runTest {
        val target = AccountContinuation(entry().copy(initialSelection = EntrySelection("b", "2", "0", positionMillis = 0)))
        assertEquals(0L, resolver(LocalPlayback(ResumePoint("10", "0", "a", 100_000), false), target).resolve(entry()).resumePositionMillis)
    }
    @Test fun removed_saved_episode_fails_instead_of_starting_first() = runTest {
        try { resolver(LocalPlayback(ResumePoint("10", "0", "missing", 500), false)).resolve(entry()); fail() }
        catch (_: HomePlaybackUnavailable) { }
    }
    @Test fun unavailable_saved_season_does_not_fall_back_to_another_season() = runTest {
        try {
            resolver(LocalPlayback(ResumePoint("10", "0", "a", 500), false), read = { details(it, it.id == "10") }).resolve(entry("20")); fail()
        } catch (_: HomePlaybackUnavailable) { }
    }
    @Test fun finished_series_without_continuation_does_not_restart() = runTest {
        try { resolver(target = AccountContinuation(null)).resolve(entry()); fail() }
        catch (_: HomePlaybackUnavailable) { }
    }
    @Test fun completed_last_season_does_not_restart_first_season() = runTest {
        try { resolver(LocalPlayback(ResumePoint("20", "0", "b", 0), true)).resolve(entry()); fail() }
        catch (_: HomePlaybackUnavailable) { }
    }
    @Test fun unrelated_season_in_account_continuation_is_rejected() = runTest {
        try { resolver(target = AccountContinuation(entry().copy(id = "999"))).resolve(entry()); fail() }
        catch (_: HomePlaybackUnavailable) { }
    }
    @Test fun invalid_account_episode_is_not_replaced_by_first() = runTest {
        try { resolver(target = AccountContinuation(entry().copy(initialSelection = EntrySelection("gone", translationId = "0")))).resolve(entry()); fail() }
        catch (_: HomePlaybackUnavailable) { }
    }
    @Test fun network_failure_does_not_silently_start_another_season() = runTest {
        try { resolver(read = { throw java.io.IOException() }).resolve(entry()); fail() }
        catch (_: java.io.IOException) { }
    }
    @Test fun malformed_tokens_and_foreign_schemes_are_rejected() {
        val token = HomeLink.token(HomeChannel.NEWEST, "10")
        assertEquals(token, HomeLink.parse(HomeLink.uri(token)))
        assertNull(HomeLink.parse("https://home/play/$token"))
        assertNull(HomeLink.parse(HomeLink.uri(token) + "?url=https://example.com"))
        assertNull(HomeLink.parse(HomeLink.uri(token) + "#position"))
        assertNull(HomeLink.parse("seasonvar://home/play/../$token"))
        assertNull(HomeLink.parse("seasonvar://user@home/play/$token"))
        assertNull(HomeLink.parse(null))
        assertEquals(HomeLink.token(HomeChannel.NEWEST, "10"), token)
        assertNotEquals(HomeLink.token(HomeChannel.WANT, "10"), token)
    }
    @Test fun settings_ignore_unknown_channels_and_start_disabled() {
        assertTrue(HomeChannel.decode(emptySet()).isEmpty())
        assertEquals(setOf(HomeChannel.NEWEST), HomeChannel.decode(setOf("newest", "unknown")))
    }
    @Test fun an_empty_standard_voice_does_not_hide_an_available_episode() = runTest {
        val result = resolver(read = { e -> details(e).let { d -> d.copy(translations = d.translations.map {
            if (it.id == "0") it.copy(episodes = emptyList()) else it
        }) } }).resolve(entry())
        assertEquals("voice", result.translationId)
    }
    @Test fun a_trailer_is_not_used_as_the_first_episode() = runTest {
        try {
            resolver(read = { details(it).copy(translations = listOf(Translation("68", "Трейлер", listOf(Episode("t", "1"))))) }).resolve(entry()); fail()
        } catch (_: HomePlaybackUnavailable) { }
    }
    @Test fun season_links_from_another_series_do_not_start_unrelated_video() = runTest {
        try {
            resolver(LocalPlayback(ResumePoint("20", "0", "a", 1000), false), read = {
                details(it).copy(serialId = if (it.id == "20") "999" else "100")
            }).resolve(entry()); fail()
        } catch (_: HomePlaybackUnavailable) { }
    }
}
