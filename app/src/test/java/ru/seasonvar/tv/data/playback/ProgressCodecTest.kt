package ru.seasonvar.tv.data.playback

import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.ResumePoint

class ProgressCodecTest {
    @Test fun round_trip_preserves_exact_episode_translation_and_milliseconds_without_video_urls() {
        val point = ResumePoint("1", "298", "7_seriya", 123456)
        val encoded = ProgressCodec.encode(point)
        assertEquals(point, ProgressCodec.decode(encoded, "1"))
        assertFalse(encoded.contains("http"))
        assertNull(ProgressCodec.decode(encoded, "2"))
    }
    @Test fun missing_corrupt_completed_and_negative_positions_do_not_create_a_resume_action() {
        assertNull(ProgressCodec.decode(null, "1"))
        assertNull(ProgressCodec.decode("not-json", "1"))
        assertNull(ProgressCodec.decode("{}", "1"))
        for (position in listOf(0L, -1L)) {
            assertNull(ProgressCodec.decode(ProgressCodec.encode(ResumePoint("1", "0", "1_seriya", position)), "1"))
        }
    }
}
