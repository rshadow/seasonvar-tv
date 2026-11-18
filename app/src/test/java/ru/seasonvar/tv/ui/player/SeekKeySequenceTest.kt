package ru.seasonvar.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekKeySequenceTest {
    private val keys = SeekKeySequence(pulseGapMs = 200, pulseDurationMs = 20)

    @Test fun step_doubles_every_two_seconds_and_stops_at_eighty_seconds() {
        val cases = listOf(0L to 10_000L, 1999L to 10_000L, 2000L to 20_000L,
            3999L to 20_000L, 4000L to 40_000L, 5999L to 40_000L, 6000L to 80_000L,
            8000L to 80_000L, Long.MAX_VALUE to 80_000L)
        for ((held, expected) in cases) {
            assertEquals(expected, acceleratedSeekStep(held, 10_000, 2000, 80_000))
        }
    }

    @Test fun remote_repeats_accumulate_until_release() {
        assertEquals(0L, keys.down(22, 1000, 0))
        assertEquals(3000L, keys.down(22, 4000, 12))
        assertEquals(5000L, keys.down(22, 6000, 20))
        keys.up(22, 6001, false)
        assertEquals(0L, keys.down(22, 6010, 0))
    }

    @Test fun emulator_pulses_accumulate_without_android_repeat_count() {
        for (index in 0..72) {
            val elapsed = index * 84L
            assertEquals(elapsed, keys.down(22, 1000 + elapsed, 0))
            keys.up(22, 1001 + elapsed, false)
        }
        assertEquals(0L, keys.down(22, 8000, 0))
    }

    @Test fun remote_down_events_work_even_without_repeat_count() {
        keys.down(22, 1000, 0)
        assertEquals(5000L, keys.down(22, 6000, 0))
        keys.up(22, 6001, false)
        assertEquals(0L, keys.down(22, 6010, 0))
    }

    @Test fun separate_presses_do_not_accumulate() {
        for (index in 0..30) {
            val time = 1000 + index * 300L
            assertEquals(0L, keys.down(22, time, 0))
            keys.up(22, time + 1, false)
        }
    }

    @Test fun ordinary_release_resets_even_before_pulse_gap_expires() {
        keys.down(22, 1000, 0)
        keys.up(22, 1100, false)
        assertEquals(0L, keys.down(22, 1150, 0))
    }

    @Test fun direction_change_cancellation_and_lifecycle_reset_acceleration() {
        keys.down(22, 1000, 0)
        keys.down(22, 6000, 10)
        assertEquals(0L, keys.down(21, 6100, 0))
        keys.up(21, 6101, true)
        assertEquals(0L, keys.down(21, 6110, 0))
        keys.reset()
        assertEquals(0L, keys.down(21, 6120, 0))
    }
}
