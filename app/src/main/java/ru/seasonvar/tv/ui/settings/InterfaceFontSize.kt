package ru.seasonvar.tv.ui.settings

import androidx.annotation.StringRes
import ru.seasonvar.tv.R

internal enum class InterfaceFontSize(val scale: Float, @StringRes val label: Int) {
    NORMAL(1f, R.string.font_size_normal),
    LARGE(1.25f, R.string.font_size_large);
}
