package ru.seasonvar.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import ru.seasonvar.tv.R

@Immutable
data class SeasonvarDimensions(
    val screenPaddingHorizontal: Dp,
    val screenPaddingVertical: Dp,
    val spacingSmall: Dp,
    val spacingMedium: Dp,
    val spacingLarge: Dp,
    val spacingExtraLarge: Dp,
    val spacingSection: Dp,
    val buttonWidth: Dp,
    val buttonHeight: Dp,
)

private val LocalDimensions = staticCompositionLocalOf<SeasonvarDimensions> {
    error("SeasonvarTheme is not provided")
}

private val SeasonvarTypography = Typography()

object SeasonvarTheme {
    val dimensions: SeasonvarDimensions
        @Composable
        @ReadOnlyComposable
        get() = LocalDimensions.current
}

@Composable
fun SeasonvarTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = colorResource(R.color.seasonvar_accent),
        onPrimary = colorResource(R.color.seasonvar_on_accent),
        background = colorResource(R.color.seasonvar_background),
        onBackground = colorResource(R.color.seasonvar_text),
        surface = colorResource(R.color.seasonvar_surface),
        onSurface = colorResource(R.color.seasonvar_text),
        onSurfaceVariant = colorResource(R.color.seasonvar_secondary_text),
    )
    val dimensions = SeasonvarDimensions(
        screenPaddingHorizontal = dimensionResource(R.dimen.seasonvar_screen_padding_horizontal),
        screenPaddingVertical = dimensionResource(R.dimen.seasonvar_screen_padding_vertical),
        spacingSmall = dimensionResource(R.dimen.seasonvar_spacing_small),
        spacingMedium = dimensionResource(R.dimen.seasonvar_spacing_medium),
        spacingLarge = dimensionResource(R.dimen.seasonvar_spacing_large),
        spacingExtraLarge = dimensionResource(R.dimen.seasonvar_spacing_extra_large),
        spacingSection = dimensionResource(R.dimen.seasonvar_spacing_section),
        buttonWidth = dimensionResource(R.dimen.seasonvar_button_width),
        buttonHeight = dimensionResource(R.dimen.seasonvar_button_height),
    )

    CompositionLocalProvider(LocalDimensions provides dimensions) {
        MaterialTheme(
            colorScheme = colors,
            typography = SeasonvarTypography,
            content = content,
        )
    }
}
