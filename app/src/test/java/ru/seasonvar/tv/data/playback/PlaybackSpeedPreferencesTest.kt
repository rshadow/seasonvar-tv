package ru.seasonvar.tv.data.playback

import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.Episode

class PlaybackSpeedPreferencesTest {
    private val values = mutableMapOf<String, String>()
    private fun settings() = PlaybackSpeedPreferences(values::get) { key, value ->
        if (value == null) values.remove(key) else values[key] = value
    }

    @Test fun default_and_series_override_survive_recreation() {
        settings().setDefault(PlaybackSpeed.ONE_QUARTER)
        settings().setOverride("serial-a", PlaybackSpeed.ONE_HALF)
        val restored = settings()
        assertEquals(PlaybackSpeed.ONE_HALF, restored.effective("serial-a"))
        assertEquals(PlaybackSpeed.ONE_QUARTER, restored.effective("serial-b"))
        assertEquals(PlaybackSpeed.ONE_QUARTER, restored.effective(null))
    }

    @Test fun explicit_normal_stays_fixed_but_inheritance_tracks_global_changes() {
        val settings = settings()
        settings.setOverride("serial-a", PlaybackSpeed.NORMAL)
        settings.setDefault(PlaybackSpeed.DOUBLE)
        assertEquals(PlaybackSpeed.NORMAL, settings.effective("serial-a"))
        assertEquals(PlaybackSpeed.DOUBLE, settings.effective("serial-b"))
        settings.setOverride("serial-a", null)
        assertNull(settings.overrideFor("serial-a"))
        assertEquals(PlaybackSpeed.DOUBLE, settings.effective("serial-a"))
        settings.setDefault(PlaybackSpeed.HALF)
        assertEquals(PlaybackSpeed.HALF, settings.effective("serial-a"))
        assertEquals(PlaybackSpeed.HALF, settings().effective("serial-a"))
    }

    @Test fun invalid_saved_values_fall_back_to_normal_or_global() {
        values["playback_speed"] = "NaN"
        values["playback_speed_serial_a"] = "99"
        val settings = settings()
        assertEquals(PlaybackSpeed.NORMAL, settings.effective("a"))
        settings.setDefault(PlaybackSpeed.ONE_HALF)
        assertEquals(PlaybackSpeed.ONE_HALF, settings.effective("a"))
        assertNull(settings.overrideFor(""))
    }

    @Test fun episode_changes_keep_series_identity() {
        val request = PlaybackRequest("season", "page", "voice", "1", "Серия 1", "Сериал",
            episodes = listOf(Episode("1", "Серия 1"), Episode("2", "Серия 2")), serialId = "serial")
        assertEquals("serial", request.adjacent(1)?.serialId)
    }
}
