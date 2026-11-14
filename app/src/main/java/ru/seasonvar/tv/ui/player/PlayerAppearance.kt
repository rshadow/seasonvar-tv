package ru.seasonvar.tv.ui.player

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import ru.seasonvar.tv.R
import ru.seasonvar.tv.ui.theme.PlayerAppearance

@androidx.annotation.OptIn(UnstableApi::class)
internal fun applyPlayerAppearance(view: PlayerView, appearance: PlayerAppearance) {
    if (view.getTag(R.id.seasonvar_player_appearance) == appearance) return
    view.setTag(R.id.seasonvar_player_appearance, appearance)
    view.setShutterBackgroundColor(appearance.background.toArgb())
    val tint = ColorStateList.valueOf(appearance.foreground.toArgb())
    fun tintControls(control: View) {
        when (control) {
            is ImageView -> control.imageTintList = tint
            is TextView -> control.setTextColor(appearance.foreground.toArgb())
        }
        if (control is ViewGroup) for (index in 0 until control.childCount) tintControls(control.getChildAt(index))
    }
    view.findViewById<View>(androidx.media3.ui.R.id.exo_controller)?.let(::tintControls)
    (view.findViewById<View>(androidx.media3.ui.R.id.exo_progress) as? DefaultTimeBar)?.apply {
        setPlayedColor(appearance.played.toArgb())
        setScrubberColor(appearance.played.toArgb())
        setBufferedColor(appearance.buffered.toArgb())
        setUnplayedColor(appearance.unplayed.toArgb())
    }
    view.findViewById<ProgressBar>(androidx.media3.ui.R.id.exo_buffering)?.indeterminateTintList = tint
}
