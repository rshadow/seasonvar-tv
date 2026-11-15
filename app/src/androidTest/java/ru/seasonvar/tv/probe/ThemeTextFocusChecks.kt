package ru.seasonvar.tv.probe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ru.seasonvar.tv.ui.settings.InterfaceTheme
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import ru.seasonvar.tv.ui.theme.SeasonvarDimensions
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class ThemeTextFocusState {
    val focused = mutableStateOf(false)
    val renderedFocus = AtomicReference<Boolean>()
    val compositions = AtomicInteger()
}

@Composable
internal fun ThemeTextFocusPreview(theme: InterfaceTheme, state: ThemeTextFocusState) {
    SeasonvarTheme(theme) {
        val focused = state.focused.value
        val c = MaterialTheme.colorScheme
        Box(Modifier.fillMaxSize().background(if (focused) c.surface else c.background)) {
            FocusCaption(state)
        }
        SideEffect { state.renderedFocus.set(focused) }
    }
}

@Composable
private fun FocusCaption(state: ThemeTextFocusState) {
    val c = MaterialTheme.colorScheme
    val outline = SeasonvarTheme.textColor({ state.focused.value }, c.onSurface)
    val filled = SeasonvarTheme.buttonTextColor({ state.focused.value }, c.onSurface)
    val weight = SeasonvarTheme.textWeight { state.focused.value }
    Text("Подпись без лишней зависимости", color = outline, fontWeight = weight)
    Text("Подпись кнопки", color = filled, fontWeight = weight)
    SideEffect { state.compositions.incrementAndGet() }
}

internal data class ThemeParameterSnapshot(val refresh: Boolean, val density: Float, val theme: InterfaceTheme,
    val dimensions: SeasonvarDimensions)

internal class ThemeParameterState {
    val refresh = mutableStateOf(false)
    val density = mutableStateOf(2f)
    val theme = mutableStateOf(InterfaceTheme.CLASSIC)
    val snapshot = AtomicReference<ThemeParameterSnapshot>()
}

@Composable
internal fun ThemeParameterPreview(state: ThemeParameterState) {
    val refresh = state.refresh.value
    val density = state.density.value
    val theme = state.theme.value
    CompositionLocalProvider(LocalDensity provides Density(density)) {
        SeasonvarTheme(theme) {
            val dimensions = SeasonvarTheme.dimensions
            Text(if (refresh) "Обновление содержимого" else "Исходное содержимое")
            SideEffect { state.snapshot.set(ThemeParameterSnapshot(refresh, density, theme, dimensions)) }
        }
    }
}
