package ru.seasonvar.tv.data.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackHistoryStartTest {
    @Test fun waits_for_both_a_rendered_frame_and_playing_in_either_callback_order() {
        val playingFirst = PlaybackHistoryStart()
        assertFalse(playingFirst.playing(true)); assertTrue(playingFirst.firstFrame(true))
        val frameFirst = PlaybackHistoryStart()
        assertFalse(frameFirst.firstFrame(false)); assertTrue(frameFirst.playing(true))
    }
    @Test fun pause_seek_rebuffer_surface_recreation_and_activity_restore_do_not_duplicate_the_event() {
        val gate = PlaybackHistoryStart()
        assertTrue(gate.firstFrame(true))
        repeat(3) {
            assertFalse(gate.playing(false)); assertFalse(gate.playing(true))
            gate.resetSurface(); assertFalse(gate.firstFrame(true))
        }
        assertFalse(PlaybackHistoryStart(gate.submitted).firstFrame(true))
        assertTrue(PlaybackHistoryStart().firstFrame(true))
    }
    @Test fun paused_first_frame_and_an_aborted_player_do_not_record_a_view() {
        val gate = PlaybackHistoryStart()
        assertFalse(gate.firstFrame(false)); gate.resetSurface(); assertFalse(gate.playing(true))
    }
}
