package ru.seasonvar.tv.probe

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ru.seasonvar.tv.R
import ru.seasonvar.tv.ui.settings.loadInterfaceThemes
import ru.seasonvar.tv.ui.theme.resolveInterfaceTheme

internal fun checkThemeConfiguration(context: Context) {
    val shipped = loadInterfaceThemes(context)
    shipped.entries.forEach { resolveInterfaceTheme(context, it) }
    val configured = loadInterfaceThemes(context, R.xml.theme_config_probe)
    check(configured.default.storageId == "probe")
    check(configured.restore("probe") == configured.default)
    val spec = resolveInterfaceTheme(context, configured.default)
    check(spec.primary == Color(0xFF11AA33) && spec.text == Color(0xFFDDEE11))
    check(spec.pageBackground == Color(0xFF102030) && spec.dialogBackground == Color(0xEE223344))
    check(spec.statusSuccess == Color(0xFF123456))
    check(spec.poster.enabled && spec.poster.blurRadius == 3 && spec.poster.dimPercent == 60)
    check(spec.poster.edgePercent == 20 && spec.poster.fadeEdgePercent == 15 && spec.poster.fadeMillis == 0)
    check(spec.refreshRotationMillis == 777)
    check(spec.buttonCornerRadius == 11.dp && spec.focusBorderWidth == 3.dp && spec.outlineWidth == 0.5.dp)
    check(spec.spacing.spacingSmall == 9.dp && spec.spacing.contentGap == 25.dp)
    check(spec.iconButtonOutline && !spec.textButtonOutline && !spec.cardOutline)
    check(spec.player.background == Color(0xFF090807) && spec.player.foreground == Color(0xFFFEDCBA))
    check(spec.player.played == Color(0xFFABCDEF))
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun checkNativePlayerTheme(context: Context): Boolean {
    val configured = loadInterfaceThemes(context, R.xml.theme_config_probe)
    val colors = resolveInterfaceTheme(context, configured.default).player
    val native = androidx.media3.ui.PlayerView(context)
    ru.seasonvar.tv.ui.player.applyPlayerAppearance(native, colors)
    val label = native.findViewById<android.widget.TextView>(androidx.media3.ui.R.id.exo_position)
    val icon = native.findViewById<android.widget.ImageView>(androidx.media3.ui.R.id.exo_play_pause)
    return label?.currentTextColor == 0xFFFEDCBA.toInt() && icon?.imageTintList?.defaultColor == 0xFFFEDCBA.toInt()
}

@androidx.compose.runtime.Composable
internal fun ThemeConfigurationPreview(onReady: (Boolean) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val configured = androidx.compose.runtime.remember(context) { loadInterfaceThemes(context, R.xml.theme_config_probe) }
    ru.seasonvar.tv.ui.theme.SeasonvarTheme(configured.default) {
        val colors = androidx.tv.material3.MaterialTheme.colorScheme
        val dimensions = ru.seasonvar.tv.ui.theme.SeasonvarTheme.dimensions
        val expectedWidth = androidx.compose.ui.res.dimensionResource(R.dimen.seasonvar_button_width)
        val text = androidx.tv.material3.MaterialTheme.typography.labelMedium
        val originalText = androidx.tv.material3.Typography().labelMedium
        val applied = colors.primary == Color(0xFF11AA33) && colors.onBackground == Color(0xFFDDEE11) &&
            colors.background == Color(0xEE223344) && dimensions.spacingSmall == 9.dp && dimensions.contentGap == 25.dp &&
            dimensions.buttonCornerRadius == 11.dp && dimensions.focusBorderWidth == 3.dp &&
            dimensions.buttonWidth == expectedWidth && text == originalText &&
            ru.seasonvar.tv.ui.theme.SeasonvarTheme.posterBackdropFadeMillis == 0 &&
            ru.seasonvar.tv.ui.theme.SeasonvarTheme.refreshRotationDurationMillis == 777 &&
            ru.seasonvar.tv.ui.theme.SeasonvarTheme.iconButtonOutline
        androidx.compose.runtime.SideEffect { onReady(applied) }
    }
}
