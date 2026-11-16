package ru.seasonvar.tv.probe

import android.content.Context
import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ru.seasonvar.tv.R
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.settings.InterfaceThemeSetting
import ru.seasonvar.tv.ui.settings.loadInterfaceThemes
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import ru.seasonvar.tv.ui.theme.resolveInterfaceTheme
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

internal class ThemeTextPreviewState {
    val buttonFocused = AtomicBoolean(false)
    val settingFocused = AtomicBoolean(false)
    val buttonBounds = AtomicReference<Rect>()
    val settingBounds = AtomicReference<Rect>()
    val buttonFocus = AtomicReference<FocusRequester>()
}

internal fun checkThemeConfiguration(context: Context) {
    val shipped = loadInterfaceThemes(context)
    shipped.entries.forEach { resolveInterfaceTheme(context, it) }
    check(resolveInterfaceTheme(context, ru.seasonvar.tv.ui.settings.InterfaceTheme.CLASSIC).poster.fallbackImage == 0)
    check(resolveInterfaceTheme(context, ru.seasonvar.tv.ui.settings.InterfaceTheme.GLASS).poster.fallbackImage == R.drawable.ic_launcher)
    check(!resolveInterfaceTheme(context, ru.seasonvar.tv.ui.settings.InterfaceTheme.CLASSIC).menuButtonOutline)
    val glass = resolveInterfaceTheme(context, ru.seasonvar.tv.ui.settings.InterfaceTheme.GLASS)
    check(glass.previewPosterAspectPercent == 75 && glass.previewPosterMaxWidthPercent == 50)
    check(glass.poster.fallbackScalePercent == 33)
    check(!glass.menuButtonOutline && glass.textButtonOutline && !glass.iconButtonOutline && glass.cardOutline)
    val configured = loadInterfaceThemes(context, R.xml.theme_config_probe)
    check(configured.default.storageId == "probe")
    check(resolveInterfaceTheme(context, configured.default).previewPosterAspectPercent == 60)
    check(resolveInterfaceTheme(context, configured.default).previewPosterMaxWidthPercent == 40)
    check(configured.restore("probe") == configured.default)
    val spec = resolveInterfaceTheme(context, configured.default)
    check(spec.primary == Color(0xFF11AA33) && spec.text == Color(0xFFDDEE11))
    check(spec.focusedText == Color(0xFF091522) && spec.focusedOutlineText == Color(0xFF80D8FF))
    check(spec.focusedTextFontWeight == androidx.compose.ui.text.font.FontWeight.Bold)
    check(spec.pageBackground == Color(0xFF102030) && spec.dialogBackground == Color(0xEE223344))
    check(spec.statusSuccess == Color(0xFF123456))
    check(spec.poster.enabled && spec.poster.blurRadius == 3 && spec.poster.dimPercent == 60)
    check(spec.poster.fallbackScalePercent == 45)
    check(spec.poster.edgePercent == 20 && spec.poster.fadeEdgePercent == 15 && spec.poster.fadeMillis == 0)
    check(spec.refreshRotationMillis == 777)
    check(spec.buttonCornerRadius == 11.dp && spec.focusBorderWidth == 3.dp && spec.outlineWidth == 0.5.dp)
    check(spec.spacing.spacingSmall == 9.dp && spec.spacing.contentGap == 25.dp)
    check(spec.iconButtonOutline && spec.menuButtonOutline && !spec.textButtonOutline && !spec.cardOutline)
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
internal fun ThemeConfigurationPreview(preview: ThemeTextPreviewState, onReady: (Boolean) -> Unit) {
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
            dimensions.previewPosterAspectRatio == 0.6f && dimensions.previewPosterMaxWidthFraction == 0.4f &&
            ru.seasonvar.tv.ui.theme.SeasonvarTheme.posterBackdropFadeMillis == 0 &&
            ru.seasonvar.tv.ui.theme.SeasonvarTheme.refreshRotationDurationMillis == 777 &&
            ru.seasonvar.tv.ui.theme.SeasonvarTheme.iconButtonOutline
        val button = remember { FocusRequester() }
        val setting = remember { FocusRequester() }
        val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
        Column(Modifier.fillMaxSize().background(SeasonvarTheme.pageBackground).padding(dimensions.spacingLarge),
            verticalArrangement = Arrangement.spacedBy(dimensions.spacingMedium)) {
            androidx.tv.material3.Text("Обычный текст", color = colors.onBackground)
            ThemeButton("Текст в фокусе", {}, Modifier.focusRequester(button).onFocusChanged {
                preview.buttonFocused.set(it.isFocused)
            }.onGloballyPositioned {
                val r = it.boundsInWindow()
                preview.buttonBounds.set(Rect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt()))
            }.focusProperties {
                down = setting; up = FocusRequester.Cancel; left = FocusRequester.Cancel; right = FocusRequester.Cancel
            })
            Box(Modifier.onFocusChanged { preview.settingFocused.set(it.hasFocus) }.focusGroup().onGloballyPositioned {
                val r = it.boundsInWindow()
                preview.settingBounds.set(Rect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt()))
            }) {
                InterfaceThemeSetting(configured.default, {}, setting, FocusRequester.Cancel, button,
                    FocusRequester.Cancel, {}, {}, {})
            }
        }
        LaunchedEffect(Unit) {
            inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
            button.requestFocus()
        }
        androidx.compose.runtime.SideEffect { preview.buttonFocus.set(button); onReady(applied) }
    }
}
