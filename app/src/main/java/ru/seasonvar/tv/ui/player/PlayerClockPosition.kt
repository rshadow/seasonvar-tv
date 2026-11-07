package ru.seasonvar.tv.ui.player

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class PlayerClockPosition(val storageId: String) {
    OFF("off"), LEFT("left"), CENTER("center"), RIGHT("right");

    companion object {
        fun fromStorage(value: String?): PlayerClockPosition = entries.firstOrNull { it.storageId == value } ?: OFF
    }
}

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

internal fun formatPlayerClock(epochMillis: Long, zone: ZoneId): String =
    clockFormat.format(Instant.ofEpochMilli(epochMillis).atZone(zone))
