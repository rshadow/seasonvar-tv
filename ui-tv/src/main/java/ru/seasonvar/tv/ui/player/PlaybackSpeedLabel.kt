package ru.seasonvar.tv.ui.player

import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.playback.PlaybackSpeed

internal fun playbackSpeedLabel(speed: PlaybackSpeed): Int = when (speed) {
    PlaybackSpeed.HALF -> R.string.playback_speed_half
    PlaybackSpeed.THREE_QUARTERS -> R.string.playback_speed_three_quarters
    PlaybackSpeed.NORMAL -> R.string.playback_speed_normal
    PlaybackSpeed.ONE_QUARTER -> R.string.playback_speed_one_quarter
    PlaybackSpeed.ONE_HALF -> R.string.playback_speed_one_half
    PlaybackSpeed.ONE_THREE_QUARTERS -> R.string.playback_speed_one_three_quarters
    PlaybackSpeed.DOUBLE -> R.string.playback_speed_double
}
