package ru.seasonvar.tv.data.playback

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.*
import java.io.IOException

class NextSeasonResolverTest {
    private fun entry(id: String) = SeriesEntry(id, "https://seasonvar.ru/serial-$id-test.html", "Тест", id.toInt(), "", "")
    private val seasons = listOf("1", "2", "3").map { SeasonOption(it, entry(it).url, "Сезон $it", it.toInt()) }
    private fun voice(id: String, label: String) = Translation(id, label, listOf(Episode("x", "Первая"), Episode("z", "Финал")))
    private fun details(id: String, voices: List<Translation> = listOf(voice("0", "Стандартный"), voice("8", "Студия"))) =
        SeriesDetails(entry(id), "100", emptyList(), emptyList(), emptyList(), seasons, voices, "0")
    private val request = PlaybackRequest("1", entry("1").url, "8", "z", "Финал", "Тест")

    @Test fun next_season_starts_first_episode_with_same_voice_by_name_and_zero_position() = runTest {
        val pages = listOf(details("1"), details("2", listOf(voice("0", "Стандартный"), voice("19", "Студия")))).associateBy { it.entry.id }
        val reads = mutableListOf<String>()
        val next = NextSeasonResolver({ reads += it.id; pages.getValue(it.id) }).next(request)!!
        assertEquals(listOf("1", "2"), reads)
        assertEquals("2", next.seasonId); assertEquals(2, next.seasonNumber); assertEquals(entry("2").url, next.pageUrl)
        assertEquals("19", next.translationId); assertEquals("x", next.episodeId)
        assertEquals(0L, next.resumePositionMillis); assertEquals(2, next.episodes.size)
    }
    @Test fun missing_current_voice_uses_user_priorities_then_standard() = runTest {
        val current = details("1")
        val next = details("2", listOf(voice("0", "Стандартный"), voice("8", "Другой")))
        suspend fun resolve(preferences: List<String>) = NextSeasonResolver({ if (it.id == "1") current else next }, { preferences }).next(request)!!
        assertEquals("8", resolve(listOf("Другой")).translationId)
        assertEquals("0", resolve(listOf("Отсутствует")).translationId)
    }
    @Test fun no_next_season_and_not_the_actual_last_episode_do_not_transition() = runTest {
        val resolver = NextSeasonResolver({ details(it.id) })
        assertNull(resolver.next(request.copy(episodeId = "x")))
        assertNull(resolver.next(request.copy(seasonId = "3", pageUrl = entry("3").url)))
    }
    @Test fun unavailable_season_is_not_skipped_and_trailers_are_not_played() = runTest {
        for (next in listOf(details("2").copy(playbackUnavailableReason = "Недоступно"),
            details("2", listOf(voice("68", "Трейлеры"))), details("2", listOf(Translation("0", "Стандартный", emptyList()))))) {
            val reads = mutableListOf<String>()
            assertNull(NextSeasonResolver({ reads += it.id; if (it.id == "1") details("1") else next }).next(request))
            assertEquals(listOf("1", "2"), reads)
        }
    }
    @Test fun network_and_invalid_playlist_fail_instead_of_reporting_no_next_season() = runTest {
        try { NextSeasonResolver({ throw IOException() }).next(request); fail() } catch (_: IOException) { }
        for (next in listOf(details("2").copy(serialId = "200"),
            details("2", listOf(Translation("0", "Стандартный", null))))) {
            try { NextSeasonResolver({ if (it.id == "1") details("1") else next }).next(request); fail() }
            catch (_: MarkupChangedException) { }
        }
    }
}
