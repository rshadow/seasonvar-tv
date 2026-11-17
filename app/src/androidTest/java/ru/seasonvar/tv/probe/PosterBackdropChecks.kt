package ru.seasonvar.tv.probe

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.runBlocking
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.images.PosterBackdropCache
import ru.seasonvar.tv.data.images.PosterBackdropSpec
import ru.seasonvar.tv.ui.details.SeriesDetailsPane
import ru.seasonvar.tv.ui.details.PosterBackdropLayer
import ru.seasonvar.tv.ui.details.PosterBackdropPage
import ru.seasonvar.tv.ui.details.PosterBackdropState
import ru.seasonvar.tv.ui.details.PosterBackdropSource
import ru.seasonvar.tv.ui.catalog.CatalogUiState
import ru.seasonvar.tv.ui.settings.*
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import java.time.LocalDate

internal class PosterBackdropChecks(private val ui: BackdropProbeSession) {
    /** Использует собственный кеш: предыдущие UI-сценарии не влияют на проверку вытеснения. */
    fun cache(fixtures: BackdropFixtures) = with(ui) {
        phase = "cache"
        val poster = fixtures.poster
        runBlocking {
            val cache = PosterBackdropCache()
            val spec = PosterBackdropSpec(1920, 1080, Color.rgb(43, 37, 33), 0.72f, 0.22f, 0.14f, blurRadius = 2)
            val prepared = cache.prepare("one", poster, spec)
            check(prepared.width <= PosterBackdropCache.MAX_EDGE && prepared.height <= PosterBackdropCache.MAX_EDGE)
            check(cache.prepare("one", poster, spec) === prepared && poster.width == 320 && !poster.isRecycled)
            val fallback = cache.prepareResource(targetContext, ru.seasonvar.tv.R.drawable.ic_launcher, spec)
            check(fallback.width == prepared.width && fallback.height == prepared.height)
            check(cache.prepareResource(targetContext, ru.seasonvar.tv.R.drawable.ic_launcher, spec) === fallback)
            val base = targetContext.getColor(ru.seasonvar.tv.R.color.seasonvar_background)
            val iconSpec = spec.copy(background = base, dim = 0f, edge = 0f, blurRadius = 0)
            val iconFull = cache.prepareResource(targetContext, ru.seasonvar.tv.R.drawable.ic_launcher, iconSpec)
            val scaledSpec = iconSpec.copy(imageScale = 0.33f)
            val iconSmall = cache.prepareResource(targetContext, ru.seasonvar.tv.R.drawable.ic_launcher, scaledSpec)
            check(cache.prepareResource(targetContext, ru.seasonvar.tv.R.drawable.ic_launcher, scaledSpec) === iconSmall)
            check(iconFull !== iconSmall)
            fun ink(bitmap: Bitmap): Rect {
                val bounds = Rect()
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    if (kotlin.math.abs(Color.red(pixel) - Color.red(base)) > 8 ||
                        kotlin.math.abs(Color.green(pixel) - Color.green(base)) > 8 ||
                        kotlin.math.abs(Color.blue(pixel) - Color.blue(base)) > 8) bounds.union(x, y, x + 1, y + 1)
                }
                return bounds
            }
            val fullBounds = ink(iconFull)
            val smallBounds = ink(iconSmall)
            check(smallBounds.width().toFloat() / fullBounds.width() in 0.25f..0.4f)
            check(smallBounds.height().toFloat() / fullBounds.height() in 0.25f..0.4f)
            check(kotlin.math.abs(smallBounds.exactCenterX() - iconSmall.width / 2f) < 8f)
            check(kotlin.math.abs(smallBounds.exactCenterY() - iconSmall.height / 2f) < 8f)
            val sharp = cache.prepare("one", poster, spec.copy(blurRadius = 0))
            val before = IntArray(prepared.width * prepared.height)
            val after = IntArray(before.size)
            prepared.getPixels(before, 0, prepared.width, 0, 0, prepared.width, prepared.height)
            sharp.getPixels(after, 0, sharp.width, 0, 0, sharp.width, sharp.height)
            check(!before.contentEquals(after))
            val hardware = checkNotNull(poster.copy(Bitmap.Config.HARDWARE, false))
            check(cache.prepare("hardware", hardware, spec).config == Bitmap.Config.ARGB_8888 && !hardware.isRecycled)
            repeat(PosterBackdropCache.MAX_ENTRIES + 1) { cache.prepare("item-$it", poster, spec) }
            check(cache.get("one", spec) == null)
            check(cache.cachedBytes <= PosterBackdropCache.MAX_ENTRIES * PosterBackdropCache.MAX_EDGE * PosterBackdropCache.MAX_EDGE * 4)
            result.putInt("backdrop_cache_bytes", cache.cachedBytes)
        }
        passed("memory_bounded")
    }

    /** Проверяет на одном экране переход Классика → Стекло → Классика с сохранением кнопок и фокуса. */
    fun details(fixtures: BackdropFixtures) = with(ui) {
        phase = "plain"
        val entry = fixtures.entry
        val model = fixtures.model
        val watch = targetContext.getString(ru.seasonvar.tv.R.string.details_watch)
        val appearance = mutableStateOf(InterfaceTheme.CLASSIC)
        val cacheBefore = app.posterBackdrops.cachedBytes
        runOnMainSync { activity.setContent { SeasonvarTheme(appearance.value) {
            val d = SeasonvarTheme.dimensions
            val source = remember { PosterBackdropState() }
            val publish = remember(source) { source::attach }
            val density = LocalDensity.current
            BoxWithConstraints(Modifier.fillMaxSize().background(SeasonvarTheme.pageBackground)) {
                if (SeasonvarTheme.posterBackdropEnabled) PosterBackdropLayer(source,
                    with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) },
                    ((d.screenPaddingHorizontal + d.menuWidth + d.contentGap) / maxWidth).coerceIn(0f, 0.6f))
                Row(Modifier.fillMaxSize().padding(d.screenPaddingHorizontal, d.screenPaddingVertical),
                    horizontalArrangement = Arrangement.spacedBy(d.contentGap)) {
                    Column(Modifier.width(d.menuWidth)) {
                        androidx.tv.material3.Text("Разделы", color = MaterialTheme.colorScheme.onBackground)
                    }
                    Box(Modifier.weight(1f)) {
                        SeriesDetailsPane(entry, model, FocusRequester.Cancel, {}, {}, showActors = false,
                            onBackdropSource = publish)
                    }
                }
            }
        } } }
        await { node(watch) != null && button(watch).isFocused }
        check(app.posterBackdrops.cachedBytes == cacheBefore)
        val playBounds = bounds(watch)
        val reviewBounds = bounds("Рецензии · 0")
        val plainPixel = capture("01-plain")
        phase = "blurred"
        runOnMainSync { appearance.value = InterfaceTheme.GLASS }
        await { app.posterBackdrops.cachedBytes > 0 }
        await { backgroundPixel() != plainPixel }
        finishFade()
        await { button(watch).isFocused && bounds(watch) == playBounds }
        check(bounds("Рецензии · 0") == reviewBounds)
        val blurredPixel = capture("02-blurred")
        check(blurredPixel != plainPixel)
        val screen = checkNotNull(uiAutomation.takeScreenshot())
        val menuPixel = screen.getPixel(screen.width / 8, screen.height / 3)
        check(menuPixel != plainPixel)
        val seam = screen.width / 4
        val beforeSeam = screen.getPixel(seam - 1, screen.height / 4)
        val afterSeam = screen.getPixel(seam + 1, screen.height / 4)
        check(kotlin.math.abs(Color.red(beforeSeam) - Color.red(afterSeam)) <= 2)
        check(kotlin.math.abs(Color.green(beforeSeam) - Color.green(afterSeam)) <= 2)
        check(kotlin.math.abs(Color.blue(beforeSeam) - Color.blue(afterSeam)) <= 2)
        screen.recycle()
        result.putBoolean("background_under_menu", true)
        runOnMainSync { appearance.value = InterfaceTheme.CLASSIC }
        await { backgroundPixel() == plainPixel }
        await { button(watch).isFocused && bounds(watch) == playBounds }
        check(bounds("Рецензии · 0") == reviewBounds)
        check(capture("03-disabled") == plainPixel)
        passed("geometry_stable", "focus_preserved")
    }

    /**
     * Явно прогревает иконку, затем задерживает выдачу постера до проверки однотонной подложки.
     * Быстрая загрузка эмулятора не должна маскировать вспышку запасного изображения.
     */
    fun loading(fixtures: BackdropFixtures) = with(ui) {
        val poster = fixtures.poster
        phase = "centered-fallback"
        runOnMainSync { activity.setContent { SeasonvarTheme(InterfaceTheme.GLASS) { PosterBackdropPage {} } } }
        await { !plainBackground(backgroundPixel(0.55f, 0.5f)) && plainBackground(backgroundPixel()) }
        finishFade()
        capture("12-centered-fallback")
        passed("fallback_background")
        phase = "solid-placeholder"
        val delayedPoster = mutableStateOf<Bitmap?>(null)
        val waitingSource = PosterBackdropState()
        val themedFallback = mutableStateOf(false)
        runOnMainSync { activity.setContent { SeasonvarTheme(InterfaceTheme.GLASS) {
            val density = LocalDensity.current
            BoxWithConstraints(Modifier.fillMaxSize().background(SeasonvarTheme.pageBackground)) {
                PosterBackdropLayer(waitingSource,
                    with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) },
                    useFallbackImage = themedFallback.value)
            }
        } } }
        fun checkSolid() {
            finishFade()
            check(plainBackground(backgroundPixel(0.55f, 0.5f)))
            check(plainBackground(backgroundPixel(0.5f, 0.55f)))
        }
        checkSolid()
        val cacheBeforeWaiting = app.posterBackdrops.cachedBytes
        runOnMainSync { waitingSource.attach(PosterBackdropSource("fixture:delayed-poster", delayedPoster)) }
        checkSolid()
        check(app.posterBackdrops.cachedBytes == cacheBeforeWaiting)
        capture("13-solid-poster-loading")
        runOnMainSync { delayedPoster.value = poster }
        await { !plainBackground(backgroundPixel(0.55f, 0.5f)) }
        finishFade()
        capture("14-delayed-poster-ready")
        runOnMainSync {
            waitingSource.attach(PosterBackdropSource("fixture:next-loading-poster", mutableStateOf(null)))
        }
        checkSolid()
        capture("15-solid-next-poster")
        runOnMainSync { themedFallback.value = true }
        await { !plainBackground(backgroundPixel(0.55f, 0.5f)) }
        finishFade()
        capture("16-system-icon-fallback")
        runOnMainSync { themedFallback.value = false }
        checkSolid()
        result.putBoolean("solid_series_placeholder", true)
        result.putBoolean("delayed_poster_without_icon", true)
        result.putBoolean("system_icon_preserved", true)
    }

    /** Проверяет переходы D-pad между настройками и списком в загрузке и с пустым результатом. */
    fun menuFallback() = with(ui) {
        phase = "main-loading-placeholder"
        val pendingPage = mutableStateOf(CatalogUiState(date = LocalDate.of(2026, 1, 1)))
        val topTitle = targetContext.getString(ru.seasonvar.tv.R.string.top_title)
        val settingsTitle = targetContext.getString(ru.seasonvar.tv.R.string.settings_title)
        runOnMainSync { activity.setContent {
            ru.seasonvar.tv.ui.SeasonvarApp(PublicFeed.entries.associateWith { pendingPage.value }, {},
                interfaceTheme = InterfaceTheme.GLASS, showActors = false,
                startPage = ru.seasonvar.tv.ui.navigation.Section.TOP)
        } }
        await { clickable(topTitle)?.isFocused == true }
        finishFade()
        check(plainBackground(backgroundPixel(0.55f, 0.5f)))
        capture("17-main-list-loading")
        repeat(4) { sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); waitForIdleSync() }
        await { clickable(settingsTitle)?.isFocused == true }
        finishFade()
        capture("18-main-settings-icon")
        repeat(4) { sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP); waitForIdleSync() }
        await { clickable(topTitle)?.isFocused == true }
        finishFade()
        check(plainBackground(backgroundPixel(0.55f, 0.5f)))
        runOnMainSync { pendingPage.value = pendingPage.value.copy(loading = false, loaded = true) }
        finishFade()
        check(plainBackground(backgroundPixel(0.55f, 0.5f)))
        capture("19-main-list-empty")
        result.putBoolean("menu_backdrop_context", true)
    }
}
