package ru.seasonvar.tv.ui.player

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PlayerClockTest {
    @Test fun missing_or_unknown_saved_position_keeps_clock_off() {
        listOf(null, "", "unknown").forEach { assertEquals(PlayerClockPosition.OFF, PlayerClockPosition.fromStorage(it)) }
    }

    @Test fun all_positions_have_distinct_stable_storage_values() {
        assertEquals(listOf("off", "left", "center", "right"), PlayerClockPosition.entries.map { it.storageId })
        PlayerClockPosition.entries.forEach { assertEquals(it, PlayerClockPosition.fromStorage(it.storageId)) }
    }

    @Test fun clock_uses_24_hour_time_and_omits_seconds() {
        val zone = ZoneId.of("UTC")
        assertEquals("00:05", formatPlayerClock(Instant.parse("2026-11-07T00:05:59Z").toEpochMilli(), zone))
        assertEquals("21:45", formatPlayerClock(Instant.parse("2026-11-07T21:45:01Z").toEpochMilli(), zone))
        assertEquals("22:00", formatPlayerClock(Instant.parse("2026-11-07T22:00:00Z").toEpochMilli(), zone))
    }

    @Test fun time_zone_change_can_move_time_to_the_next_day() {
        val instant = Instant.parse("2026-11-07T22:45:00Z").toEpochMilli()
        assertEquals("22:45", formatPlayerClock(instant, ZoneId.of("UTC")))
        assertEquals("01:45", formatPlayerClock(instant, ZoneId.of("Europe/Moscow")))
        assertEquals("04:15", formatPlayerClock(instant, ZoneId.of("Asia/Kolkata")))
    }

    @Test fun clock_follows_the_zones_daylight_saving_transition() {
        val zone = ZoneId.of("Europe/Berlin")
        assertEquals("01:59", formatPlayerClock(Instant.parse("2026-03-29T00:59:59Z").toEpochMilli(), zone))
        assertEquals("03:00", formatPlayerClock(Instant.parse("2026-03-29T01:00:00Z").toEpochMilli(), zone))
    }
}
