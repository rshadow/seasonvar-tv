package ru.seasonvar.tv.data.playback

import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.Episode

class PlaybackRequestTest {
    private val episodes = listOf(Episode("bonus", "Спецвыпуск"), Episode("ten", "10"), Episode("two", "2"))
    private fun request() = PlaybackRequest("season", "https://seasonvar.ru/serial-1-test.html", "voice", "ten", "10", "Тест", episodes)

    @Test fun navigation_uses_site_order_instead_of_adding_to_numbers_or_ids() {
        assertEquals("bonus", request().adjacent(-1)!!.episodeId)
        assertEquals("two", request().adjacent(1)!!.episodeId)
        assertEquals("2", request().adjacent(1)!!.episodeLabel)
    }

    @Test fun boundaries_do_not_wrap_and_invalid_direction_does_not_skip_episodes() {
        assertNull(request().selectEpisode("bonus")!!.adjacent(-1))
        assertNull(request().selectEpisode("two")!!.adjacent(1))
        assertNull(request().adjacent(0))
        assertNull(request().adjacent(2))
    }

    @Test fun missing_single_or_ambiguous_current_episode_has_no_neighbours() {
        assertNull(request().copy(episodes = emptyList()).adjacent(1))
        assertNull(request().copy(episodes = listOf(episodes[1])).adjacent(-1))
        assertNull(request().copy(episodeId = "missing").adjacent(1))
        assertNull(request().copy(episodes = episodes + episodes[1]).adjacent(1))
        assertNull(request().selectEpisode("missing"))
    }

    @Test fun large_seasons_keep_the_whole_queue_and_allow_reverse_navigation() {
        val queue = (1..200).map { Episode("id-$it", "$it") }
        val selected = request().copy(episodes = queue, episodeId = "id-199", episodeLabel = "199")
        val next = selected.adjacent(1)!!
        assertEquals("id-200", next.episodeId)
        assertEquals(selected, next.adjacent(-1))
        assertNull(next.adjacent(1))
    }

    @Test fun switching_keeps_season_page_translation_title_and_original_queue() {
        val original = request().copy(seasonNumber = 4)
        assertEquals(original.copy(episodeId = "two", episodeLabel = "2"), original.adjacent(1))
        assertEquals("ten", original.episodeId)
    }

    @Test fun malformed_parallel_arrays_do_not_create_a_guessed_queue() {
        assertTrue(playbackEpisodes(null, listOf("1")).isEmpty())
        assertTrue(playbackEpisodes(listOf("a"), null).isEmpty())
        assertTrue(playbackEpisodes(listOf("a", "b"), listOf("1")).isEmpty())
        assertTrue(playbackEpisodes(listOf("a", "a"), listOf("1", "2")).isEmpty())
        assertTrue(playbackEpisodes(listOf(""), listOf("1")).isEmpty())
        assertTrue(playbackEpisodes(listOf("a"), listOf(" ")).isEmpty())
    }

    @Test fun transport_preserves_ids_and_labels_without_treating_labels_as_ids() {
        assertEquals(episodes, playbackEpisodes(episodes.map { it.id }, episodes.map { it.label }))
    }
    @Test fun explicit_resume_is_preserved_for_the_same_episode_and_cleared_on_navigation() {
        val initial = request().copy(resumePositionMillis = 58_000)
        assertEquals(58_000L, initial.selectEpisode(initial.episodeId)!!.resumePositionMillis)
        assertNull(initial.adjacent(1)!!.resumePositionMillis)
        assertNull(initial.adjacent(-1)!!.resumePositionMillis)
    }

}
