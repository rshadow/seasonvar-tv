package ru.seasonvar.tv.probe

import android.graphics.Color
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import ru.seasonvar.tv.R
import ru.seasonvar.tv.ui.settings.*
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import ru.seasonvar.tv.ui.theme.playerInterfaceTheme
import ru.seasonvar.tv.ui.theme.resolveInterfaceTheme
import java.util.concurrent.atomic.AtomicReference

internal class ThemeAppearanceChecks(private val ui: BackdropProbeSession) {
    /** Фиксирует различие тем: отсутствие фоновой обработки в Классике и размытие с прозрачными поверхностями в Стекле. */
    fun contract() = with(ui) {
        phase = "theme-contract"
        checkThemeConfiguration(targetContext)
        val classic = resolveInterfaceTheme(targetContext, InterfaceTheme.CLASSIC)
        val glass = resolveInterfaceTheme(targetContext, InterfaceTheme.GLASS)
        check(!classic.poster.enabled && classic.poster.blurRadius == 0)
        check(classic.surface == androidx.compose.ui.graphics.Color(targetContext.getColor(ru.seasonvar.tv.R.color.seasonvar_surface)))
        check(classic.inactiveSurface == androidx.compose.ui.graphics.Color(targetContext.getColor(ru.seasonvar.tv.R.color.seasonvar_background)))
        check(classic.outline.alpha == 0f)
        check(glass.poster.enabled && glass.poster.blurRadius > 0)
        check(glass.surface.alpha < 1f && glass.inactiveSurface.alpha < 1f && glass.outline.alpha > 0f)
        val catalog = loadInterfaceThemes(targetContext)
        val videoteka = catalog.restore("videoteka")
        check(videoteka.label == R.string.interface_theme_videoteka && catalog.default == InterfaceTheme.CLASSIC)
        val library = resolveInterfaceTheme(targetContext, videoteka)
        val player = resolveInterfaceTheme(targetContext, playerInterfaceTheme(targetContext, videoteka))
        check(!library.poster.enabled && library.cardOutline && library.textButtonOutline)
        check(playerInterfaceTheme(targetContext, InterfaceTheme.CLASSIC) == InterfaceTheme.CLASSIC)
        check(playerInterfaceTheme(targetContext, InterfaceTheme.GLASS) == InterfaceTheme.GLASS)
        check(player.spacing == library.spacing && player.buttonCornerRadius == library.buttonCornerRadius)
        check(player.player.background == player.pageBackground && player.player.played == player.player.foreground)
        check(ColorUtils.calculateContrast(library.text.toArgb(), library.pageBackground.toArgb()) >= 4.5)
        check(ColorUtils.calculateContrast(library.onPrimary.toArgb(), library.primary.toArgb()) >= 4.5)
        check(ColorUtils.calculateContrast(player.text.toArgb(), player.player.background.toArgb()) >= 4.5)
        check(ColorUtils.calculateContrast(player.player.foreground.toArgb(), player.player.background.toArgb()) >= 4.5)
        val neon = resolveInterfaceTheme(targetContext, catalog.restore("neon"))
        check(neon.theme.label == R.string.interface_theme_neon && neon.focusGlow != null)
        check(classic.focusGlow == null && glass.focusGlow == null && library.focusGlow == null)
        check(neon.player.played == neon.primary && neon.player.buffered != neon.player.played)
        check(ColorUtils.calculateContrast(neon.text.toArgb(), neon.pageBackground.toArgb()) >= 4.5)
        check(ColorUtils.calculateContrast(neon.onPrimary.toArgb(), neon.primary.toArgb()) >= 4.5)
        result.putBoolean("theme_contract", true)
    }

    /** Проверяет цвет глифов на снимке, жирность и сохранение геометрии при переходе фокуса. */
    fun configuredColors() = with(ui) {
        phase = "configured-theme"
        val configuredApplied = AtomicReference<Boolean>()
        val textPreview = ThemeTextPreviewState()
        runOnMainSync { activity.setContent { ThemeConfigurationPreview(textPreview) { configuredApplied.set(it) } } }
        await { configuredApplied.get() != null }
        check(configuredApplied.get() == true)
        phase = "configured-window-focus"
        await { activity.hasWindowFocus() && textPreview.buttonBounds.get() != null }
        phase = "configured-button-focus"
        await {
            if (!textPreview.buttonFocused.get()) runOnMainSync { textPreview.buttonFocus.get().requestFocus() }
            textPreview.buttonFocused.get()
        }
        val focusedBounds = textPreview.buttonBounds.get()
        phase = "configured-button-color"
        await {
            runOnMainSync { textPreview.buttonFocus.get().requestFocus() }
            val r = textPreview.buttonBounds.get()
            result.putString("failure_geometry", "${r.left}_${r.top}_${r.width()}_${r.height()}_${textPreview.buttonFocused.get()}_${textPreview.settingFocused.get()}")
            textPreview.buttonFocused.get() && textPixels(textPreview.buttonBounds.get(), Color.rgb(9, 21, 34)) > 20
        }
        result.remove("failure_geometry")
        val boldInk = textPixels(textPreview.buttonBounds.get(), Color.rgb(9, 21, 34))
        capture("00-configured-focused-button")
        phase = "configured-setting-focus"
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        await { textPreview.settingFocused.get() }
        phase = "configured-setting-color"
        // Штатная индикация фокуса слегка тонирует цвет глифов.
        await { textPixels(textPreview.settingBounds.get(), Color.rgb(128, 216, 255)) > 20 }
        phase = "configured-regular-color"
        await { textPixels(textPreview.buttonBounds.get(), Color.rgb(221, 238, 17)) > 20 }
        val regularInk = textPixels(textPreview.buttonBounds.get(), Color.rgb(221, 238, 17))
        phase = "configured-weight-and-bounds"
        check(regularInk > 20 && boldInk > regularInk)
        check(textPreview.buttonBounds.get().left == focusedBounds.left && textPreview.buttonBounds.get().height() == focusedBounds.height())
        capture("00-configured-focused-setting")
    }

    /** Стандартным темам смена фокуса не требует композиции; настраиваемые цвета допускают её. */
    fun focusDependencies() = with(ui) {
        phase = "text-focus-dependencies"
        val configured = ru.seasonvar.tv.ui.settings.loadInterfaceThemes(targetContext, ru.seasonvar.tv.R.xml.theme_config_probe).default
        for (theme in listOf(InterfaceTheme.CLASSIC, InterfaceTheme.GLASS, configured)) {
            val state = ThemeTextFocusState()
            runOnMainSync { activity.setContent { ThemeTextFocusPreview(theme, state) } }
            await { state.renderedFocus.get() == false && state.compositions.get() > 0 }
            val initial = state.compositions.get()
            runOnMainSync { state.focused.value = true }
            await { state.renderedFocus.get() == true }
            if (theme == configured) check(state.compositions.get() > initial)
            else check(state.compositions.get() == initial)
            runOnMainSync { state.focused.value = false }
            await { state.renderedFocus.get() == false }
            if (theme != configured) check(state.compositions.get() == initial)
        }
        result.putBoolean("text_focus_dependencies", true)
    }

    /** Проверяет переиспользование размеров и их пересчёт при смене плотности или конфигурации темы. */
    fun parameterCache() = with(ui) {
        val configured = ru.seasonvar.tv.ui.settings.loadInterfaceThemes(targetContext, ru.seasonvar.tv.R.xml.theme_config_probe).default
        phase = "theme-parameter-cache"
        val parameters = ThemeParameterState()
        runOnMainSync { activity.setContent { ThemeParameterPreview(parameters) } }
        await { parameters.snapshot.get() != null }
        val initialDimensions = parameters.snapshot.get().dimensions
        runOnMainSync { parameters.refresh.value = true }
        await { parameters.snapshot.get().refresh }
        check(parameters.snapshot.get().dimensions === initialDimensions)
        runOnMainSync { parameters.density.value = 3f }
        await { parameters.snapshot.get().density == 3f }
        check(parameters.snapshot.get().dimensions !== initialDimensions)
        val expectedButtonWidth = targetContext.resources.getDimension(ru.seasonvar.tv.R.dimen.seasonvar_button_width) / 3f
        check(kotlin.math.abs(parameters.snapshot.get().dimensions.buttonWidth.value - expectedButtonWidth) < 0.01f)
        runOnMainSync { parameters.theme.value = configured }
        await { parameters.snapshot.get().theme == configured }
        check(parameters.snapshot.get().dimensions.buttonCornerRadius.value == 11f)
        result.putBoolean("theme_parameters_cached", true)
    }

    /** Сверяет цвета нативного PlayerView с темой без запуска воспроизведения. */
    fun nativePlayerColors() = with(ui) {
        phase = "native-player-theme"
        var nativeColorsApplied = false
        runOnMainSync { nativeColorsApplied = checkNativePlayerTheme(activity) }
        check(nativeColorsApplied)
    }

    /** Меняет сохранённую настройку темы; исходное значение восстанавливает BackdropProbeSession. */
    fun selection() = with(ui) {
        val appearance = mutableStateOf(InterfaceTheme.CLASSIC)
        phase = "setting"
        runOnMainSync { appearance.value = InterfaceTheme.CLASSIC }
        runOnMainSync { activity.setContent { SeasonvarTheme(appearance.value) {
            val d = SeasonvarTheme.dimensions
            val focus = remember { FocusRequester() }
            Column(Modifier.fillMaxSize().background(SeasonvarTheme.pageBackground).padding(d.spacingLarge)) {
                InterfaceThemeSetting(appearance.value, { appearance.value = it; app.interfaceSettings.setInterfaceTheme(it) },
                    focus, FocusRequester.Cancel, FocusRequester.Cancel, FocusRequester.Cancel, {}, {}, {})
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        } } }
        await { node(targetContext.getString(ru.seasonvar.tv.R.string.interface_theme_title)) != null }
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        await { node(targetContext.getString(ru.seasonvar.tv.R.string.interface_theme_glass)) != null }
        capture("04-options")
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        await { appearance.value == InterfaceTheme.GLASS }
        check(InterfaceSettings(targetContext).interfaceTheme.value == InterfaceTheme.GLASS)
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        await { node(targetContext.getString(ru.seasonvar.tv.R.string.interface_theme_glass)) != null }
        capture("10-theme-choices")
        await { node(targetContext.getString(R.string.interface_theme_videoteka)) != null }
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        await { appearance.value.storageId == "videoteka" }
        check(InterfaceSettings(targetContext).interfaceTheme.value.storageId == "videoteka")
        capture("11-videoteka-setting")
        phase = "neon-setting"
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        await { node(targetContext.getString(R.string.interface_theme_neon)) != null }
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        await { appearance.value.storageId == "neon" }
        check(InterfaceSettings(targetContext).interfaceTheme.value.storageId == "neon")
        capture("12-neon-setting")
    }
}
