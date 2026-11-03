package ru.seasonvar.tv.data.playback

import org.junit.Assert.*
import org.junit.Test

class AccountMarkPolicyTest {
    private val request = PlaybackRequest("123", "https://seasonvar.ru/serial-123-Test.html", "0", "7_seriya", "7", "Тест")
    private fun value(position: Long, now: Long, playing: Boolean = true, duration: Long = 1_200_000) =
        PlaybackSample(position, duration, playing, now)

    @Test fun periodic_save_uses_current_time_after_a_minute_of_actual_playback() {
        val policy = AccountMarkPolicy(request)
        assertNull(policy.sample(value(1_000, 0)))
        assertNull(policy.sample(value(31_000, 30_000)))
        val mark = policy.sample(value(61_000, 60_000))!!
        assertEquals("7", mark.episode)
        assertEquals(61L, mark.positionSeconds)
        assertNull(policy.sample(value(62_000, 61_000)))
    }

    @Test fun pause_exit_and_episode_switch_can_flush_before_the_periodic_interval() {
        for (event in listOf(MarkEvent.PAUSE, MarkEvent.STOP)) {
            val policy = AccountMarkPolicy(request)
            policy.sample(value(1_000, 0))
            val mark = policy.sample(value(36_000, 35_000, playing = false), event)!!
            assertEquals(36L, mark.positionSeconds)
            assertNull(policy.sample(value(36_000, 35_001, playing = false), event))
        }
    }

    @Test fun ninety_percent_marks_the_episode_watched_once_and_later_pauses_cannot_downgrade_it() {
        val policy = AccountMarkPolicy(request)
        policy.sample(value(1_000, 0))
        assertNull(policy.sample(value(1_079_999, 30_000)))
        val mark = policy.sample(value(1_080_000, 30_001))!!
        assertEquals(0L, mark.positionSeconds)
        assertEquals("7", mark.form()["seria"])
        assertNull(policy.sample(value(1_100_000, 60_000), MarkEvent.PAUSE))
        assertNull(policy.sample(value(1_200_000, 90_000, false), MarkEvent.ENDED))
    }

    @Test fun pause_at_the_threshold_marks_completed_even_between_periodic_samples() {
        val policy = AccountMarkPolicy(request)
        policy.sample(value(1_000, 0))
        assertEquals(0L, policy.sample(value(1_080_000, 35_000, false), MarkEvent.PAUSE)!!.positionSeconds)
    }

    @Test fun seeking_to_the_end_without_thirty_seconds_of_real_play_does_not_mark_the_episode() {
        val policy = AccountMarkPolicy(request)
        policy.sample(value(1_100_000, 0))
        assertNull(policy.sample(value(1_150_000, 5_000), MarkEvent.PAUSE))
        assertNull(policy.sample(value(1_200_000, 6_000, false), MarkEvent.ENDED))
    }

    @Test fun buffering_and_paused_time_do_not_count_as_playback() {
        val policy = AccountMarkPolicy(request)
        policy.sample(value(1_000, 0))
        policy.sample(value(11_000, 10_000, false))
        assertNull(policy.sample(value(11_000, 100_000, false), MarkEvent.PAUSE))
        assertNull(policy.sample(value(11_000, 100_001, true)))
        assertNull(policy.sample(value(21_000, 110_001, false), MarkEvent.PAUSE))
        policy.sample(value(21_000, 110_002, true))
        assertNotNull(policy.sample(value(31_000, 120_002, false), MarkEvent.PAUSE))
    }

    @Test fun natural_end_is_saved_with_unknown_duration_but_percentage_is_not_guessed() {
        val policy = AccountMarkPolicy(request)
        policy.sample(value(1_000, 0, duration = -1))
        val progress = policy.sample(value(65_000, 64_000, duration = -1))!!
        assertEquals(65L, progress.positionSeconds)
        val ended = policy.sample(value(75_000, 74_000, false, -1), MarkEvent.ENDED)!!
        assertEquals(0L, ended.positionSeconds)
    }

    @Test fun short_episodes_can_finish_before_thirty_seconds_but_a_zero_position_is_never_sent_as_watched() {
        val policy = AccountMarkPolicy(request)
        policy.sample(value(1, 0, duration = 20_000))
        assertEquals(0L, policy.sample(value(18_000, 18_000, duration = 20_000))!!.positionSeconds)
        val zero = AccountMarkPolicy(request)
        zero.sample(value(1_000, 0))
        assertNull(zero.sample(value(0, 60_000, false), MarkEvent.PAUSE))
    }

    @Test fun trailers_never_change_account_lists() {
        val policy = AccountMarkPolicy(request.copy(translationId = "68"))
        policy.sample(value(1_000, 0))
        assertNull(policy.sample(value(1_100_000, 60_000)))
        assertNull(policy.sample(value(1_200_000, 90_000, false), MarkEvent.ENDED))
    }
}
