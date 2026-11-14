package ru.seasonvar.tv.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.images.PosterBackdropCache
import ru.seasonvar.tv.ui.settings.InterfaceTheme

internal data class PosterBackgroundStyle(val enabled: Boolean, val blurRadius: Int, val dimPercent: Int,
    val edgePercent: Int, val fadeEdgePercent: Int, val fadeMillis: Int, val fallbackImage: Int,
    val fallbackScalePercent: Int) {
    init {
        require(blurRadius in 0..PosterBackdropCache.MAX_EDGE && dimPercent in 0..100)
        require(edgePercent in 0..100 && fadeEdgePercent in 0..50 && fadeMillis >= 0)
        require(fallbackScalePercent in 1..100)
    }
}

internal data class InterfaceSpacing(
    val screenPaddingHorizontal: Dp,
    val screenPaddingVertical: Dp,
    val spacingSmall: Dp,
    val spacingMedium: Dp,
    val spacingLarge: Dp,
    val spacingExtraLarge: Dp,
    val spacingSection: Dp,
    val menuItemPaddingHorizontal: Dp,
    val menuItemSpacing: Dp,
    val menuHeaderSpacing: Dp,
    val contentGap: Dp,
    val cardPadding: Dp,
    val badgePaddingHorizontal: Dp,
    val headerButtonBadgeInset: Dp,
    val watchBadgeInset: Dp,
    val playerClockPaddingVertical: Dp
) {
    init { require(listOf(screenPaddingHorizontal, screenPaddingVertical, spacingSmall, spacingMedium, spacingLarge, spacingExtraLarge, spacingSection, menuItemPaddingHorizontal, menuItemSpacing, menuHeaderSpacing, contentGap, cardPadding, badgePaddingHorizontal, headerButtonBadgeInset, watchBadgeInset, playerClockPaddingVertical).all { it.value >= 0f }) }
}

internal data class PlayerAppearance(
    val background: Color,
    val clockBackground: Color,
    val foreground: Color,
    val played: Color,
    val buffered: Color,
    val unplayed: Color
) {
    init { require(background.alpha == 1f && foreground.alpha > 0f) }
}

internal data class InterfaceThemeSpec(val theme: InterfaceTheme, val poster: PosterBackgroundStyle,
    val surface: Color, val inactiveSurface: Color, val outline: Color,
    val primary: Color,
    val onPrimary: Color,
    val text: Color,
    val secondaryText: Color,
    val pageBackground: Color,
    val dialogBackground: Color,
    val statusSuccess: Color,
    val statusFailure: Color,
    val statusPending: Color,
    val buttonCornerRadius: Dp,
    val cardCornerRadius: Dp,
    val focusBorderWidth: Dp,
    val outlineWidth: Dp,
    val textButtonOutline: Boolean,
    val menuButtonOutline: Boolean,
    val iconButtonOutline: Boolean,
    val cardOutline: Boolean,
    val refreshRotationMillis: Int, val spacing: InterfaceSpacing, val player: PlayerAppearance) {
    init {
        require(pageBackground.alpha == 1f && primary.alpha == 1f && onPrimary.alpha > 0f && text.alpha > 0f && secondaryText.alpha > 0f)
        require(refreshRotationMillis > 0 && focusBorderWidth.value > 0f)
        require(listOf(buttonCornerRadius, cardCornerRadius, focusBorderWidth, outlineWidth).all { it.value >= 0f })
    }
}

internal fun resolveInterfaceTheme(context: Context, theme: InterfaceTheme): InterfaceThemeSpec {
    val values = context.obtainStyledAttributes(theme.styleRes, R.styleable.SeasonvarInterfaceTheme)
    fun color(attribute: Int, fallback: Int) = Color(values.getColor(attribute, context.getColor(fallback)))
    fun dimension(attribute: Int, fallback: Int) =
        (values.getDimension(attribute, context.resources.getDimension(fallback)) / context.resources.displayMetrics.density).dp
    fun integer(attribute: Int, fallback: Int) = values.getInt(attribute, context.resources.getInteger(fallback))
    try {
        return InterfaceThemeSpec(
            theme = theme,
            poster = PosterBackgroundStyle(
                values.getBoolean(R.styleable.SeasonvarInterfaceTheme_seasonvarPosterBackgroundEnabled, false),
                values.getInt(R.styleable.SeasonvarInterfaceTheme_seasonvarPosterBlurRadius, 0),
                integer(R.styleable.SeasonvarInterfaceTheme_seasonvarPosterDimPercent, R.integer.seasonvar_poster_backdrop_dim_percent),
                integer(R.styleable.SeasonvarInterfaceTheme_seasonvarPosterEdgePercent, R.integer.seasonvar_poster_backdrop_edge_percent),
                integer(R.styleable.SeasonvarInterfaceTheme_seasonvarPosterFadeEdgePercent, R.integer.seasonvar_poster_backdrop_fade_edge_percent),
                integer(R.styleable.SeasonvarInterfaceTheme_seasonvarPosterFadeMillis, R.integer.seasonvar_poster_backdrop_fade_ms),
                values.getResourceId(R.styleable.SeasonvarInterfaceTheme_seasonvarPosterFallbackImage, 0),
                integer(R.styleable.SeasonvarInterfaceTheme_seasonvarPosterFallbackScalePercent, R.integer.seasonvar_poster_fallback_scale_percent)),
            surface = color(R.styleable.SeasonvarInterfaceTheme_seasonvarSurfaceColor, R.color.seasonvar_surface),
            inactiveSurface = color(R.styleable.SeasonvarInterfaceTheme_seasonvarInactiveSurfaceColor, R.color.seasonvar_background),
            outline = Color(values.getColor(R.styleable.SeasonvarInterfaceTheme_seasonvarSurfaceOutlineColor, android.graphics.Color.TRANSPARENT)),
            primary = color(R.styleable.SeasonvarInterfaceTheme_seasonvarAccentColor, R.color.seasonvar_accent),
            onPrimary = color(R.styleable.SeasonvarInterfaceTheme_seasonvarOnAccentColor, R.color.seasonvar_on_accent),
            text = color(R.styleable.SeasonvarInterfaceTheme_seasonvarTextColor, R.color.seasonvar_text),
            secondaryText = color(R.styleable.SeasonvarInterfaceTheme_seasonvarSecondaryTextColor, R.color.seasonvar_secondary_text),
            pageBackground = color(R.styleable.SeasonvarInterfaceTheme_seasonvarPageBackgroundColor, R.color.seasonvar_background),
            dialogBackground = color(R.styleable.SeasonvarInterfaceTheme_seasonvarDialogBackgroundColor, R.color.seasonvar_background),
            statusSuccess = color(R.styleable.SeasonvarInterfaceTheme_seasonvarStatusSuccessColor, R.color.seasonvar_status_success),
            statusFailure = color(R.styleable.SeasonvarInterfaceTheme_seasonvarStatusFailureColor, R.color.seasonvar_status_failure),
            statusPending = color(R.styleable.SeasonvarInterfaceTheme_seasonvarStatusPendingColor, R.color.seasonvar_status_pending),
            buttonCornerRadius = dimension(R.styleable.SeasonvarInterfaceTheme_seasonvarButtonCornerRadius, R.dimen.seasonvar_button_corner_radius),
            cardCornerRadius = dimension(R.styleable.SeasonvarInterfaceTheme_seasonvarCardCornerRadius, R.dimen.seasonvar_card_corner_radius),
            focusBorderWidth = dimension(R.styleable.SeasonvarInterfaceTheme_seasonvarFocusBorderWidth, R.dimen.seasonvar_focus_border_width),
            outlineWidth = dimension(R.styleable.SeasonvarInterfaceTheme_seasonvarSurfaceOutlineWidth, R.dimen.seasonvar_surface_outline_width),
            textButtonOutline = values.getBoolean(R.styleable.SeasonvarInterfaceTheme_seasonvarTextButtonOutline, false),
            menuButtonOutline = values.getBoolean(R.styleable.SeasonvarInterfaceTheme_seasonvarMenuButtonOutline,
                values.getBoolean(R.styleable.SeasonvarInterfaceTheme_seasonvarTextButtonOutline, false)),
            iconButtonOutline = values.getBoolean(R.styleable.SeasonvarInterfaceTheme_seasonvarIconButtonOutline, false),
            cardOutline = values.getBoolean(R.styleable.SeasonvarInterfaceTheme_seasonvarCardOutline, false),
            refreshRotationMillis = integer(R.styleable.SeasonvarInterfaceTheme_seasonvarRefreshRotationMillis, R.integer.seasonvar_refresh_rotation_duration_ms),
            spacing = InterfaceSpacing(
                screenPaddingHorizontal = dimension(R.styleable.SeasonvarInterfaceTheme_seasonvarScreenPaddingHorizontal, R.dimen.seasonvar_screen_padding_horizontal),
                screenPaddingVertical = dimension(R.styleable.SeasonvarInterfaceTheme_seasonvarScreenPaddingVertical, R.dimen.seasonvar_screen_padding_vertical),
                spacingSmall = dimension(R.styleable.SeasonvarInterfaceTheme_seasonvarSpacingSmall, R.dimen.seasonvar_spacing_small),
                spacingMedium = dimension(R.styleable.SeasonvarInterfaceTheme_seasonvarSpacingMedium, R.dimen.seasonvar_spacing_medium),
                spacingLarge = dimension(R.styleable.SeasonvarInterfaceTheme_seasonvarSpacingLarge, R.dimen.seasonvar_spacing_large),
                spacingExtraLarge = dimension(R.styleable.SeasonvarInterfaceTheme_seasonvarSpacingExtraLarge, R.dimen.seasonvar_spacing_extra_large),
                spacingSection = dimension(R.styleable.SeasonvarInterfaceTheme_seasonvarSpacingSection, R.dimen.seasonvar_spacing_section),
                menuItemPaddingHorizontal = dimension(R.styleable.SeasonvarInterfaceTheme_seasonvarMenuItemPaddingHorizontal, R.dimen.seasonvar_menu_item_padding_horizontal),
                menuItemSpacing = dimension(R.styleable.SeasonvarInterfaceTheme_seasonvarMenuItemSpacing, R.dimen.seasonvar_menu_item_spacing),
                menuHeaderSpacing = dimension(R.styleable.SeasonvarInterfaceTheme_seasonvarMenuHeaderSpacing, R.dimen.seasonvar_menu_header_spacing),
                contentGap = dimension(R.styleable.SeasonvarInterfaceTheme_seasonvarContentGap, R.dimen.seasonvar_content_gap),
                cardPadding = dimension(R.styleable.SeasonvarInterfaceTheme_seasonvarCardPadding, R.dimen.seasonvar_card_padding),
                badgePaddingHorizontal = dimension(R.styleable.SeasonvarInterfaceTheme_seasonvarBadgePaddingHorizontal, R.dimen.seasonvar_badge_padding_horizontal),
                headerButtonBadgeInset = dimension(R.styleable.SeasonvarInterfaceTheme_seasonvarHeaderButtonBadgeInset, R.dimen.seasonvar_header_button_badge_inset),
                watchBadgeInset = dimension(R.styleable.SeasonvarInterfaceTheme_seasonvarWatchBadgeInset, R.dimen.seasonvar_watch_badge_inset),
                playerClockPaddingVertical = dimension(R.styleable.SeasonvarInterfaceTheme_seasonvarPlayerClockPaddingVertical, R.dimen.seasonvar_player_clock_padding_vertical)),
            player = PlayerAppearance(
                background = color(R.styleable.SeasonvarInterfaceTheme_seasonvarPlayerBackgroundColor, R.color.seasonvar_player_background),
                clockBackground = color(R.styleable.SeasonvarInterfaceTheme_seasonvarPlayerClockBackgroundColor, R.color.seasonvar_player_clock_background),
                foreground = color(R.styleable.SeasonvarInterfaceTheme_seasonvarPlayerControlsColor, R.color.seasonvar_player_controls),
                played = color(R.styleable.SeasonvarInterfaceTheme_seasonvarPlayerPlayedColor, R.color.seasonvar_player_played),
                buffered = color(R.styleable.SeasonvarInterfaceTheme_seasonvarPlayerBufferedColor, R.color.seasonvar_player_buffered),
                unplayed = color(R.styleable.SeasonvarInterfaceTheme_seasonvarPlayerUnplayedColor, R.color.seasonvar_player_unplayed)))
    } finally { values.recycle() }
}
