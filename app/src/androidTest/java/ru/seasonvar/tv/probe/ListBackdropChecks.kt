package ru.seasonvar.tv.probe

import android.graphics.Color
import android.graphics.Rect
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.tv.material3.MaterialTheme
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.ui.details.PosterBackdropLayer
import ru.seasonvar.tv.ui.details.PosterBackdropState
import ru.seasonvar.tv.ui.details.PosterBackdropPublisher
import ru.seasonvar.tv.ui.CatalogPane
import ru.seasonvar.tv.ui.catalog.CatalogLayout
import ru.seasonvar.tv.ui.catalog.CatalogUiState
import ru.seasonvar.tv.ui.catalog.rememberCatalogCardMetrics
import ru.seasonvar.tv.ui.settings.*
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import ru.seasonvar.tv.ui.theme.resolveInterfaceTheme
import java.util.concurrent.atomic.AtomicReference
import java.time.LocalDate

/** Сохраняет один экран между лентой, плиткой и сменой темы для проверки позиции и фокуса. */
internal class ListBackdropChecks(private val ui: BackdropProbeSession, private val rows: List<SeriesEntry>) {
    private data class StripReference(val pixel: Int, val titleTop: Int)
    private val appearance = mutableStateOf(InterfaceTheme.GLASS)
    private val selection = mutableStateOf(rows.first())
    private val layout = mutableStateOf(CatalogLayout.STRIP)
    private val prefetch = mutableStateOf(false)
    private val empty = mutableStateOf(false)
    private val published = PosterBackdropState()
    private val renderedTheme = AtomicReference<InterfaceTheme>()
    private val renderedSurface = AtomicReference<androidx.compose.ui.graphics.Color>()
    private val renderedBackground = AtomicReference<androidx.compose.ui.graphics.Color>()
    private val cards = mutableMapOf<String, FocusRequester>()
    private val plainPixel = resolveInterfaceTheme(ui.targetContext, InterfaceTheme.CLASSIC).pageBackground.toArgb()

    fun run() {
        render()
        val reference = strip()
        grid(reference)
        val fallbackPixel = missingPoster(reference.pixel)
        themeAndLayout(reference)
        emptyResult(fallbackPixel)
    }

    private fun render() = with(ui) {
        phase = "list"
        val publish: PosterBackdropPublisher = published::attach
        runOnMainSync { appearance.value = InterfaceTheme.GLASS; activity.setContent { SeasonvarTheme(appearance.value) {
            val activeTheme = SeasonvarTheme.interfaceTheme
            val activeSurface = MaterialTheme.colorScheme.surface
            val activeBackground = MaterialTheme.colorScheme.background
            SideEffect {
                renderedTheme.set(activeTheme); renderedSurface.set(activeSurface)
                renderedBackground.set(activeBackground)
            }
            val d = SeasonvarTheme.dimensions
            val density = LocalDensity.current
            val list = rememberLazyListState()
            val grid = rememberLazyGridState()
            val header = remember { FocusRequester() }
            BoxWithConstraints(Modifier.fillMaxSize().background(SeasonvarTheme.pageBackground)) {
                if (SeasonvarTheme.posterBackdropEnabled) PosterBackdropLayer(published,
                    with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) },
                    ((d.screenPaddingHorizontal + d.menuWidth + d.contentGap) / maxWidth).coerceIn(0f, 0.6f))
                Row(Modifier.fillMaxSize().padding(d.screenPaddingHorizontal, d.screenPaddingVertical),
                    horizontalArrangement = Arrangement.spacedBy(d.contentGap)) {
                    Column(Modifier.width(d.menuWidth)) {
                        androidx.tv.material3.Text("Разделы", color = MaterialTheme.colorScheme.onBackground)
                    }
                    BoxWithConstraints(Modifier.weight(1f)) {
                        CatalogPane(CatalogUiState(date = LocalDate.of(2026, 1, 1), entries = if (empty.value) emptyList() else rows,
                            loaded = true, loading = false), if (empty.value) null else selection.value,
                            list, cards, PublicFeed.TODAY, FocusRequester.Cancel, header, FocusRequester.Cancel, {},
                            onFocused = { selection.value = it }, onOpen = { error("Unexpected playback") },
                            catalogKey = "backdrop-fixture", cardMetrics = rememberCatalogCardMetrics(maxWidth),
                            imagePrefetchEnabled = prefetch.value, layout = layout.value, gridState = grid, layoutFocus = header,
                            onLayoutChange = { layout.value = layout.value.other() },
                            onBackdropSource = publish)
                    }
                }
            }
        } } }
    }

    private fun focusRow(index: Int) = with(ui) {
        val key = "backdrop-fixture|" + rows[index].key
        await { cards.containsKey(key) && clickable(rows[index].title) != null }
        runOnMainSync { cards.getValue(key).requestFocus() }
        await { selection.value.key == rows[index].key && clickable(rows[index].title)?.isFocused == true }
    }

    private fun strip() = with(ui) {
        phase = "list-first"
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        focusRow(0)
        await { published.value?.url == rows[0].posterUrl && published.value?.bitmap?.value != null && backgroundPixel() != plainPixel }
        finishFade()
        val firstListPixel = capture("05-list-first")
        phase = "list-second"; sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        await { selection.value.key == rows[1].key && button(rows[1].title).isFocused && published.value?.url == rows[1].posterUrl && published.value?.bitmap?.value != null }
        finishFade()
        val secondListPixel = capture("06-list-second")
        check(secondListPixel != firstListPixel)
        return@with StripReference(secondListPixel, bounds(rows[1].title).top)
    }

    private fun grid(reference: StripReference) = with(ui) {
        phase = "list-grid"; runOnMainSync { layout.value = CatalogLayout.GRID; prefetch.value = true }
        await { clickable(rows[1].title)?.let { item ->
            Rect().also(item::getBoundsInScreen).top < reference.titleTop
        } == true }
        uiAutomation.waitForIdle(300, 3000)
        focusRow(1)
        await { published.value?.url == rows[1].posterUrl && published.value?.bitmap?.value != null }
        capture("07-list-grid")
        focusRow(0); focusRow(1)
        await { published.value?.url == rows[1].posterUrl && button(rows[1].title).isFocused }
    }

    private fun missingPoster(previousPixel: Int) = with(ui) {
        phase = "list-missing"; focusRow(2)
        await { published.value?.url == rows[2].posterUrl && published.value?.bitmap?.value == null &&
            plainBackground(backgroundPixel()) && backgroundPixel() != previousPixel }
        finishFade()
        val fallbackPixel = capture("10-missing-poster-fallback")
        check(plainBackground(fallbackPixel) && button(rows[2].title).isFocused)
        focusRow(1)
        await { published.value?.bitmap?.value != null && backgroundPixel() != plainPixel }
        return@with fallbackPixel
    }

    private fun themeAndLayout(reference: StripReference) = with(ui) {
        val glass = resolveInterfaceTheme(targetContext, InterfaceTheme.GLASS)
        phase = "list-disabled"; val selectedBounds = bounds(rows[1].title)
        runOnMainSync { appearance.value = InterfaceTheme.CLASSIC }
        await { published.value == null && backgroundPixel() == plainPixel && button(rows[1].title).isFocused }
        check(bounds(rows[1].title) == selectedBounds)
        runOnMainSync { appearance.value = InterfaceTheme.GLASS }
        await { published.value?.url == rows[1].posterUrl && published.value?.bitmap?.value != null && backgroundPixel() != plainPixel }
        phase = "glass"
        runOnMainSync { appearance.value = InterfaceTheme.GLASS }
        await { renderedTheme.get() == InterfaceTheme.GLASS && renderedSurface.get() == glass.surface &&
            published.value?.bitmap?.value != null && clickable(rows[1].title)?.isFocused == true }
        check(renderedBackground.get().alpha == 1f)
        finishFade()
        check(bounds(rows[1].title) == selectedBounds)
        capture("08-glass-grid")
        runOnMainSync { layout.value = CatalogLayout.STRIP }
        await { clickable(rows[1].title) != null && bounds(rows[1].title).top >= reference.titleTop }
        uiAutomation.waitForIdle(300, 3000)
        focusRow(1)
        capture("09-glass-strip")
    }

    private fun emptyResult(fallbackPixel: Int) = with(ui) {
        phase = "list-empty"; runOnMainSync { empty.value = true }
        await { published.value == null && backgroundPixel() == fallbackPixel }
        capture("11-empty-fallback")
        passed("list_background_tracks_selection", "prefetch_toggle")
    }
}
