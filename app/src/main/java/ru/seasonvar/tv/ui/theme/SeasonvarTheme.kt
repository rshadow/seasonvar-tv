package ru.seasonvar.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.integerResource
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
    val buttonCornerRadius: Dp,
    val menuWidth: Dp,
    val menuItemHeight: Dp,
    val menuItemSpacing: Dp,
    val menuHeaderSpacing: Dp,
    val navigationButtonHeight: Dp,
    val contentGap: Dp,
    val headerButtonSize: Dp,
    val playerAutoNextWidth: Dp,
    val headerButtonIconSize: Dp,
    val cardWidth: Dp,
    val cardHeight: Dp,
    val thumbnailHeight: Dp,
    val cardRowHeight: Dp,
    val posterWidth: Dp,
    val posterHeight: Dp,
    val cardPadding: Dp,
    val cardCornerRadius: Dp,
    val focusBorderWidth: Dp,
    val badgePaddingHorizontal: Dp,
    val badgeHeight: Dp,
    val watchBadgeSize: Dp,
    val watchBadgeIconSize: Dp,
    val watchBadgeInset: Dp,
    val badgeTextOffsetHorizontal: Dp,
    val badgeTextOffsetVertical: Dp,
    val dialogWidth: Dp,
    val dialogHeight: Dp,
    val episodeWidth: Dp,
)

private val LocalDimensions = staticCompositionLocalOf<SeasonvarDimensions> {
    error("SeasonvarTheme is not provided")
}

private val SeasonvarTypography = Typography()

object SeasonvarTheme {
    val allEpisodesThreshold: Int
        @Composable
        @ReadOnlyComposable
        get() = integerResource(R.integer.seasonvar_all_episodes_threshold)

    val refreshRotationDurationMillis: Int
        @Composable
        @ReadOnlyComposable
        get() = integerResource(R.integer.seasonvar_refresh_rotation_duration_ms)

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
        buttonCornerRadius = dimensionResource(R.dimen.seasonvar_button_corner_radius),
        menuWidth = dimensionResource(R.dimen.seasonvar_menu_width),
        menuItemHeight = dimensionResource(R.dimen.seasonvar_menu_item_height),
        menuItemSpacing = dimensionResource(R.dimen.seasonvar_menu_item_spacing),
        menuHeaderSpacing = dimensionResource(R.dimen.seasonvar_menu_header_spacing),
        navigationButtonHeight = dimensionResource(R.dimen.seasonvar_navigation_button_height),
        contentGap = dimensionResource(R.dimen.seasonvar_content_gap),
        headerButtonSize = dimensionResource(R.dimen.seasonvar_header_button_size),
        playerAutoNextWidth = dimensionResource(R.dimen.seasonvar_player_auto_next_width),
        headerButtonIconSize = dimensionResource(R.dimen.seasonvar_header_button_icon_size),
        cardWidth = dimensionResource(R.dimen.seasonvar_card_width),
        cardHeight = dimensionResource(R.dimen.seasonvar_card_height),
        thumbnailHeight = dimensionResource(R.dimen.seasonvar_thumbnail_height),
        cardRowHeight = dimensionResource(R.dimen.seasonvar_card_row_height),
        posterWidth = dimensionResource(R.dimen.seasonvar_poster_width),
        posterHeight = dimensionResource(R.dimen.seasonvar_poster_height),
        cardPadding = dimensionResource(R.dimen.seasonvar_card_padding),
        cardCornerRadius = dimensionResource(R.dimen.seasonvar_card_corner_radius),
        focusBorderWidth = dimensionResource(R.dimen.seasonvar_focus_border_width),
        badgePaddingHorizontal = dimensionResource(R.dimen.seasonvar_badge_padding_horizontal),
        badgeHeight = dimensionResource(R.dimen.seasonvar_badge_height),
        watchBadgeSize = dimensionResource(R.dimen.seasonvar_watch_badge_size),
        watchBadgeIconSize = dimensionResource(R.dimen.seasonvar_watch_badge_icon_size),
        watchBadgeInset = dimensionResource(R.dimen.seasonvar_watch_badge_inset),
        badgeTextOffsetHorizontal = dimensionResource(R.dimen.seasonvar_badge_text_offset_horizontal),
        badgeTextOffsetVertical = dimensionResource(R.dimen.seasonvar_badge_text_offset_vertical),
        dialogWidth = dimensionResource(R.dimen.seasonvar_dialog_width),
        dialogHeight = dimensionResource(R.dimen.seasonvar_dialog_height),
        episodeWidth = dimensionResource(R.dimen.seasonvar_episode_width),
    )

    CompositionLocalProvider(LocalDimensions provides dimensions) {
        MaterialTheme(
            colorScheme = colors,
            typography = SeasonvarTypography,
            content = content,
        )
    }
}
