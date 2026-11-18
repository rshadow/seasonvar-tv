package ru.seasonvar.tv.data.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlaybackSpeed(val multiplier: Float, val storageId: String) {
    HALF(0.5f, "0.5"), THREE_QUARTERS(0.75f, "0.75"), NORMAL(1f, "1"),
    ONE_QUARTER(1.25f, "1.25"), ONE_HALF(1.5f, "1.5"), ONE_THREE_QUARTERS(1.75f, "1.75"), DOUBLE(2f, "2");

    companion object {
        fun fromStorage(value: String?): PlaybackSpeed? = entries.firstOrNull { it.storageId == value }
    }
}

internal class PlaybackSpeedPreferences(
    private val read: (String) -> String?,
    private val write: (String, String?) -> Unit,
) {
    private val mutableDefault = MutableStateFlow(PlaybackSpeed.fromStorage(read("playback_speed")) ?: PlaybackSpeed.NORMAL)
    val defaultSpeed = mutableDefault.asStateFlow()

    fun setDefault(speed: PlaybackSpeed) {
        write("playback_speed", speed.storageId)
        mutableDefault.value = speed
    }

    fun overrideFor(serialId: String?): PlaybackSpeed? = serialId?.takeIf { it.isNotBlank() }?.let {
        PlaybackSpeed.fromStorage(read("playback_speed_serial_$it"))
    }

    fun effective(serialId: String?): PlaybackSpeed = overrideFor(serialId) ?: defaultSpeed.value

    fun setOverride(serialId: String, speed: PlaybackSpeed?) {
        require(serialId.isNotBlank())
        write("playback_speed_serial_$serialId", speed?.storageId)
    }
}
