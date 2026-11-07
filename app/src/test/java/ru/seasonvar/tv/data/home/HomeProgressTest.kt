package ru.seasonvar.tv.data.home

import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.ResumePoint
import ru.seasonvar.tv.data.playback.ProgressCodec

class HomeProgressTest {
    @Test fun fresh_zero_position_keeps_the_selected_episode_and_is_not_completion() {
        val point = ResumePoint("10", "voice", "episode-8", 0)
        assertEquals(LocalPlayback(point, false), ProgressCodec.decodeHome(ProgressCodec.encode(point), "10", false))
    }
    @Test fun completed_episode_is_distinct_from_starting_the_same_episode() {
        val point = ResumePoint("10", "voice", "episode-8", 0)
        assertTrue(ProgressCodec.decodeHome(ProgressCodec.encode(point), "10", true)!!.completed)
    }
    @Test fun legacy_zero_without_completion_flag_is_not_guessed() {
        val point = ResumePoint("10", "voice", "episode-8", 0)
        assertNull(ProgressCodec.decodeHome(ProgressCodec.encode(point), "10", null))
    }
    @Test fun legacy_positive_progress_remains_available() {
        val point = ResumePoint("10", "voice", "episode-8", 35_000)
        assertEquals(LocalPlayback(point, false), ProgressCodec.decodeHome(ProgressCodec.encode(point), "10", null))
    }
    @Test fun corrupt_or_foreign_season_progress_is_ignored() {
        assertNull(ProgressCodec.decodeHome("broken", "10", false))
        assertNull(ProgressCodec.decodeHome(ProgressCodec.encode(ResumePoint("20", "0", "e", 10)), "10", false))
    }
}
