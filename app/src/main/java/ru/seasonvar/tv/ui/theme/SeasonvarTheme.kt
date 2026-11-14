package ru.seasonvar.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import ru.seasonvar.tv.R
import ru.seasonvar.tv.ui.settings.InterfaceTheme
import ru.seasonvar.tv.ui.settings.InterfaceThemeCatalog
import ru.seasonvar.tv.ui.settings.loadInterfaceThemes

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
    val menuItemPaddingHorizontal: Dp,
    val menuItemHeight: Dp,
    val menuItemSpacing: Dp,
    val menuHeaderSpacing: Dp,
    val navigationButtonHeight: Dp,
    val contentGap: Dp,
    val headerButtonSize: Dp,
    val headerButtonBadgeSize: Dp,
    val headerButtonBadgeInset: Dp,
    val filterPanelWidth: Dp,
    val playerAutoNextWidth: Dp,
    val playerClockPaddingVertical: Dp,
    val headerButtonIconSize: Dp,
    val accountAvatarSize: Dp,
    val catalogMinCardWidth: Dp,
    val browseCatalogMinCardWidth: Dp,
    val posterWidth: Dp,
    val posterHeight: Dp,
    val actorPhotoSize: Dp,
    val actorTileWidth: Dp,
    val actorNameHeight: Dp,
    val reviewsButtonWidth: Dp,
    val reviewsMetadataHeight: Dp,
    val reviewsStatusHeight: Dp,
    val reviewsListWidthFraction: Float,
    val previewPosterMaxWidthFraction: Float,
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
private val LocalInterfaceTheme = staticCompositionLocalOf<InterfaceThemeSpec?> { null }
private val LocalThemeCatalog = staticCompositionLocalOf<InterfaceThemeCatalog?> { null }
private val LocalPageBackground = staticCompositionLocalOf { Color.Unspecified }

object SeasonvarTheme {
    internal val availableThemes: List<InterfaceTheme>
        @Composable @ReadOnlyComposable get() = checkNotNull(LocalThemeCatalog.current).entries
    internal val appearance: InterfaceThemeSpec
        @Composable @ReadOnlyComposable get() = checkNotNull(LocalInterfaceTheme.current)
    internal val playerAppearance: PlayerAppearance
        @Composable @ReadOnlyComposable get() = appearance.player
    val textButtonOutline: Boolean
        @Composable @ReadOnlyComposable get() = appearance.textButtonOutline
    val menuButtonOutline: Boolean
        @Composable @ReadOnlyComposable get() = appearance.menuButtonOutline
    val iconButtonOutline: Boolean
        @Composable @ReadOnlyComposable get() = appearance.iconButtonOutline
    val cardOutline: Boolean
        @Composable @ReadOnlyComposable get() = appearance.cardOutline
    val statusSuccess: Color
        @Composable @ReadOnlyComposable get() = appearance.statusSuccess
    val statusFailure: Color
        @Composable @ReadOnlyComposable get() = appearance.statusFailure
    val statusPending: Color
        @Composable @ReadOnlyComposable get() = appearance.statusPending
    internal val interfaceTheme: InterfaceTheme
        @Composable @ReadOnlyComposable get() = LocalInterfaceTheme.current?.theme ?: InterfaceTheme.CLASSIC
    val posterBackdropEnabled: Boolean
        @Composable @ReadOnlyComposable get() = LocalInterfaceTheme.current?.poster?.enabled ?: false
    val posterBackdropBlurRadius: Int
        @Composable @ReadOnlyComposable get() = LocalInterfaceTheme.current?.poster?.blurRadius ?: 0
    val posterBackdropFallbackImage: Int
        @Composable @ReadOnlyComposable get() = appearance.poster.fallbackImage
    val posterBackdropFallbackScale: Float
        @Composable @ReadOnlyComposable get() = appearance.poster.fallbackScalePercent / 100f
    val surfaceOutline: Color
        @Composable @ReadOnlyComposable get() = LocalInterfaceTheme.current?.outline ?: Color.Transparent
    val inactiveSurface: Color
        @Composable @ReadOnlyComposable get() = LocalInterfaceTheme.current?.inactiveSurface ?: MaterialTheme.colorScheme.background
    val surfaceOutlineWidth: Dp
        @Composable @ReadOnlyComposable get() = appearance.outlineWidth
    val pageBackground: Color
        @Composable @ReadOnlyComposable get() = LocalPageBackground.current.takeIf { it != Color.Unspecified }
            ?: MaterialTheme.colorScheme.background
    val posterBackdropFadeMillis: Int
        @Composable @ReadOnlyComposable get() = appearance.poster.fadeMillis
    val posterBackdropDimFraction: Float
        @Composable @ReadOnlyComposable get() = (LocalInterfaceTheme.current?.poster?.dimPercent ?: integerResource(R.integer.seasonvar_poster_backdrop_dim_percent)) / 100f
    val posterBackdropEdgeFraction: Float
        @Composable @ReadOnlyComposable get() = appearance.poster.edgePercent / 100f
    val posterBackdropFadeEdgeFraction: Float
        @Composable @ReadOnlyComposable get() = appearance.poster.fadeEdgePercent / 100f
    val allEpisodesThreshold: Int
        @Composable
        @ReadOnlyComposable
        get() = integerResource(R.integer.seasonvar_all_episodes_threshold)

    val refreshRotationDurationMillis: Int
        @Composable
        @ReadOnlyComposable
        get() = appearance.refreshRotationMillis

    val dimensions: SeasonvarDimensions
        @Composable
        @ReadOnlyComposable
        get() = LocalDimensions.current
}

@Composable
internal fun SeasonvarTheme(interfaceTheme: InterfaceTheme = InterfaceTheme.CLASSIC, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val catalog = remember(context.resources) { loadInterfaceThemes(context) }
    val appearance = remember(context, interfaceTheme) { resolveInterfaceTheme(context, interfaceTheme) }
    val pageBackground = appearance.pageBackground
    val colors = darkColorScheme(
        primary = appearance.primary,
        onPrimary = appearance.onPrimary,
        background = appearance.dialogBackground,
        onBackground = appearance.text,
        surface = appearance.surface,
        onSurface = appearance.text,
        onSurfaceVariant = appearance.secondaryText,
    )
    val dimensions = SeasonvarDimensions(
        screenPaddingHorizontal = appearance.spacing.screenPaddingHorizontal,
        screenPaddingVertical = appearance.spacing.screenPaddingVertical,
        spacingSmall = appearance.spacing.spacingSmall,
        spacingMedium = appearance.spacing.spacingMedium,
        spacingLarge = appearance.spacing.spacingLarge,
        spacingExtraLarge = appearance.spacing.spacingExtraLarge,
        spacingSection = appearance.spacing.spacingSection,
        buttonWidth = dimensionResource(R.dimen.seasonvar_button_width),
        buttonHeight = dimensionResource(R.dimen.seasonvar_button_height),
        buttonCornerRadius = appearance.buttonCornerRadius,
        menuWidth = dimensionResource(R.dimen.seasonvar_menu_width),
        menuItemPaddingHorizontal = appearance.spacing.menuItemPaddingHorizontal,
        menuItemHeight = dimensionResource(R.dimen.seasonvar_menu_item_height),
        menuItemSpacing = appearance.spacing.menuItemSpacing,
        menuHeaderSpacing = appearance.spacing.menuHeaderSpacing,
        navigationButtonHeight = dimensionResource(R.dimen.seasonvar_navigation_button_height),
        contentGap = appearance.spacing.contentGap,
        headerButtonSize = dimensionResource(R.dimen.seasonvar_header_button_size),
        headerButtonBadgeSize = dimensionResource(R.dimen.seasonvar_header_button_badge_size),
        headerButtonBadgeInset = appearance.spacing.headerButtonBadgeInset,
        filterPanelWidth = dimensionResource(R.dimen.seasonvar_filter_panel_width),
        playerAutoNextWidth = dimensionResource(R.dimen.seasonvar_player_auto_next_width),
        playerClockPaddingVertical = appearance.spacing.playerClockPaddingVertical,
        headerButtonIconSize = dimensionResource(R.dimen.seasonvar_header_button_icon_size),
        accountAvatarSize = dimensionResource(R.dimen.seasonvar_account_avatar_size),
        catalogMinCardWidth = dimensionResource(R.dimen.seasonvar_catalog_min_card_width),
        browseCatalogMinCardWidth = dimensionResource(R.dimen.seasonvar_browse_catalog_min_card_width),
        posterWidth = dimensionResource(R.dimen.seasonvar_poster_width),
        posterHeight = dimensionResource(R.dimen.seasonvar_poster_height),
        actorPhotoSize = dimensionResource(R.dimen.seasonvar_actor_photo_size),
        actorTileWidth = dimensionResource(R.dimen.seasonvar_actor_tile_width),
        actorNameHeight = dimensionResource(R.dimen.seasonvar_actor_name_height),
        reviewsButtonWidth = dimensionResource(R.dimen.seasonvar_reviews_button_width),
        reviewsMetadataHeight = dimensionResource(R.dimen.seasonvar_reviews_metadata_height),
        reviewsStatusHeight = dimensionResource(R.dimen.seasonvar_reviews_status_height),
        reviewsListWidthFraction = integerResource(R.integer.seasonvar_reviews_list_width_percent) / 100f,
        previewPosterMaxWidthFraction = integerResource(R.integer.seasonvar_preview_poster_max_width_percent) / 100f,
        cardPadding = appearance.spacing.cardPadding,
        cardCornerRadius = appearance.cardCornerRadius,
        focusBorderWidth = appearance.focusBorderWidth,
        badgePaddingHorizontal = appearance.spacing.badgePaddingHorizontal,
        badgeHeight = dimensionResource(R.dimen.seasonvar_badge_height),
        watchBadgeSize = dimensionResource(R.dimen.seasonvar_watch_badge_size),
        watchBadgeIconSize = dimensionResource(R.dimen.seasonvar_watch_badge_icon_size),
        watchBadgeInset = appearance.spacing.watchBadgeInset,
        badgeTextOffsetHorizontal = dimensionResource(R.dimen.seasonvar_badge_text_offset_horizontal),
        badgeTextOffsetVertical = dimensionResource(R.dimen.seasonvar_badge_text_offset_vertical),
        dialogWidth = dimensionResource(R.dimen.seasonvar_dialog_width),
        dialogHeight = dimensionResource(R.dimen.seasonvar_dialog_height),
        episodeWidth = dimensionResource(R.dimen.seasonvar_episode_width),
    )

    CompositionLocalProvider(LocalDimensions provides dimensions, LocalInterfaceTheme provides appearance,
        LocalPageBackground provides pageBackground, LocalThemeCatalog provides catalog) {
        MaterialTheme(
            colorScheme = colors,
            typography = SeasonvarTypography,
            content = content,
        )
    }
}
