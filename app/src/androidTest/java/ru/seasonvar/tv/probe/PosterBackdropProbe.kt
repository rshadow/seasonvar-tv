package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.Choreographer
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.runBlocking
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.images.PosterBackdropCache
import ru.seasonvar.tv.data.images.PosterBackdropSpec
import ru.seasonvar.tv.ui.details.SeriesDetailsPane
import ru.seasonvar.tv.ui.details.PosterBackdropLayer
import ru.seasonvar.tv.ui.details.PosterBackdropPage
import ru.seasonvar.tv.ui.details.PosterBackdropState
import ru.seasonvar.tv.ui.details.PosterBackdropPublisher
import ru.seasonvar.tv.ui.details.PosterBackdropSource
import ru.seasonvar.tv.ui.details.SeriesDetailsViewModel
import ru.seasonvar.tv.ui.CatalogPane
import ru.seasonvar.tv.ui.catalog.CatalogLayout
import ru.seasonvar.tv.ui.catalog.CatalogUiState
import ru.seasonvar.tv.ui.catalog.rememberCatalogCardMetrics
import ru.seasonvar.tv.ui.settings.*
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import ru.seasonvar.tv.ui.theme.resolveInterfaceTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.time.LocalDate

object PosterBackdropProbe {
    fun run(probe: Instrumentation) = with(probe) {
        val result = Bundle()
        var activity: MarkMenuPreviewActivity? = null
        val app = targetContext.applicationContext as SeasonvarApplication
        val watch = targetContext.getString(ru.seasonvar.tv.R.string.details_watch)
        val original = app.interfaceSettings.interfaceTheme.value
        val appearance = mutableStateOf(InterfaceTheme.CLASSIC)
        val artifacts = File(checkNotNull(targetContext.getExternalFilesDir("poster-backdrop-probe")), "run-" + System.currentTimeMillis())
        check(artifacts.isDirectory || artifacts.mkdirs())
        val poster = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        Canvas(poster).apply {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, 320f, 480f, intArrayOf(Color.rgb(18, 40, 90), Color.rgb(32, 160, 180), Color.rgb(225, 90, 55)), null, Shader.TileMode.CLAMP)
            }
            drawPaint(paint)
            paint.shader = null; paint.color = Color.rgb(240, 190, 100)
            drawCircle(250f, 150f, 110f, paint)
        }
        val image = File(artifacts, "fixture.png")
        image.outputStream().use { poster.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val entry = SeriesEntry("123", "https://seasonvar.ru/serial-123-Test.html", "Проверка оформления", 1, "", image.toURI().toString())
        val details = SeriesDetails(entry, "12", listOf("Описание тестового сериала. Текст и кнопки сохраняют свои места при смене фона."),
            listOf("Жанр" to "Приключения", "Страна" to "Тест"), emptyList(), listOf(SeasonOption("123", entry.url, "Сезон 1", 1)),
            listOf(Translation("0", "Оригинал", (1..30).map { Episode(it.toString(), it.toString()) })), "0", reviewCount = 0)
        val model = SeriesDetailsViewModel { details }
        var phase = "cache"
        fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            repeat(node.childCount) { find(node.getChild(it), text)?.let { found -> return found } }
            return null
        }
        fun node(text: String) = find(uiAutomation.rootInActiveWindow, text)
        fun clickable(text: String): AccessibilityNodeInfo? {
            fun findClickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                node ?: return null
                if (node.text?.toString() == text || node.contentDescription?.toString() == text) {
                    var item: AccessibilityNodeInfo = node
                    while (!item.isClickable && item.parent != null) item = item.parent
                    if (item.isClickable) return item
                }
                repeat(node.childCount) { findClickable(node.getChild(it))?.let { found -> return found } }
                return null
            }
            return findClickable(uiAutomation.rootInActiveWindow)
        }
        fun button(text: String) = checkNotNull(clickable(text))
        fun bounds(text: String) = Rect().also { button(text).getBoundsInScreen(it) }
        fun await(predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 5000
            do {
                waitForIdleSync(); uiAutomation.waitForIdle(100, 1000)
                if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
                if (predicate()) return
            } while (SystemClock.elapsedRealtime() < end)
            error("Expected fixture state")
        }
        fun capture(name: String): Int {
            uiAutomation.waitForIdle(300, 3000)
            val bitmap = checkNotNull(uiAutomation.takeScreenshot())
            val pixel = bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 4)
            File(artifacts, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            return pixel
        }
        fun backgroundPixel(x: Float = 0.75f, y: Float = 0.25f): Int {
            val bitmap = checkNotNull(uiAutomation.takeScreenshot())
            val pixel = bitmap.getPixel((bitmap.width * x).toInt(), (bitmap.height * y).toInt())
            bitmap.recycle()
            return pixel
        }
        fun finishFade() {
            val complete = CountDownLatch(1)
            val duration = targetContext.resources.getInteger(ru.seasonvar.tv.R.integer.seasonvar_poster_backdrop_fade_ms) * 1_000_000L
            runOnMainSync {
                val clock = Choreographer.getInstance()
                clock.postFrameCallback(object : Choreographer.FrameCallback {
                    var started = 0L
                    override fun doFrame(time: Long) {
                        if (started == 0L) started = time
                        if (time - started >= duration) complete.countDown() else clock.postFrameCallback(this)
                    }
                })
            }
            check(complete.await(5, TimeUnit.SECONDS))
        }
        try {
            check(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.FINGERPRINT.contains("generic"))
            checkThemeConfiguration(targetContext)
            val classic = resolveInterfaceTheme(targetContext, InterfaceTheme.CLASSIC)
            val glass = resolveInterfaceTheme(targetContext, InterfaceTheme.GLASS)
            check(!classic.poster.enabled && classic.poster.blurRadius == 0)
            check(classic.surface == androidx.compose.ui.graphics.Color(targetContext.getColor(ru.seasonvar.tv.R.color.seasonvar_surface)))
            check(classic.inactiveSurface == androidx.compose.ui.graphics.Color(targetContext.getColor(ru.seasonvar.tv.R.color.seasonvar_background)))
            check(classic.outline.alpha == 0f)
            check(glass.poster.enabled && glass.poster.blurRadius > 0)
            check(glass.surface.alpha < 1f && glass.inactiveSurface.alpha < 1f && glass.outline.alpha > 0f)
            result.putBoolean("theme_contract", true)
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
            phase = "plain"
            activity = startActivitySync(Intent(targetContext, MarkMenuPreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MarkMenuPreviewActivity
            phase = "configured-theme"
            val configuredApplied = AtomicReference<Boolean>()
            val textPreview = ThemeTextPreviewState()
            runOnMainSync { checkNotNull(activity).setContent { ThemeConfigurationPreview(textPreview) { configuredApplied.set(it) } } }
            await { configuredApplied.get() != null }
            check(configuredApplied.get() == true)
            phase = "configured-window-focus"
            await { activity?.hasWindowFocus() == true && textPreview.buttonBounds.get() != null }
            phase = "configured-button-focus"
            await {
                if (!textPreview.buttonFocused.get()) runOnMainSync { textPreview.buttonFocus.get().requestFocus() }
                textPreview.buttonFocused.get()
            }
            fun textPixels(region: Rect, color: Int, tolerance: Int = 32): Int {
                val screenshot = checkNotNull(uiAutomation.takeScreenshot())
                try {
                    val inner = Rect(region)
                    val inset = minOf(inner.width() / 8, inner.height() / 4)
                    inner.inset(inset, inset)
                    val pixels = IntArray(inner.width() * inner.height())
                    screenshot.getPixels(pixels, 0, inner.width(), inner.left, inner.top, inner.width(), inner.height())
                    val counts = pixels.toList().groupingBy { it }.eachCount()
                    val colors = counts.filterKeys { pixel -> kotlin.math.abs(Color.red(pixel) - Color.red(color)) <= tolerance &&
                        kotlin.math.abs(Color.green(pixel) - Color.green(color)) <= tolerance &&
                        kotlin.math.abs(Color.blue(pixel) - Color.blue(color)) <= tolerance }
                    return colors.values.maxOrNull() ?: 0
                } finally { screenshot.recycle() }
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
            phase = "text-focus-dependencies"
            val configured = ru.seasonvar.tv.ui.settings.loadInterfaceThemes(targetContext, ru.seasonvar.tv.R.xml.theme_config_probe).default
            for (theme in listOf(InterfaceTheme.CLASSIC, InterfaceTheme.GLASS, configured)) {
                val state = ThemeTextFocusState()
                runOnMainSync { activity!!.setContent { ThemeTextFocusPreview(theme, state) } }
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
            phase = "theme-parameter-cache"
            val parameters = ThemeParameterState()
            runOnMainSync { activity!!.setContent { ThemeParameterPreview(parameters) } }
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
            phase = "plain"
            var nativeColorsApplied = false
            runOnMainSync { nativeColorsApplied = checkNativePlayerTheme(checkNotNull(activity)) }
            check(nativeColorsApplied)
            runOnMainSync { activity!!.setContent { SeasonvarTheme(appearance.value) {
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
            check(app.posterBackdrops.cachedBytes == 0)
            val playBounds = bounds(watch)
            val reviewBounds = bounds("Рецензии · 0")
            val plainPixel = capture("01-plain")
            fun plainBackground(pixel: Int) = kotlin.math.abs(Color.red(pixel) - Color.red(plainPixel)) <= 2 &&
                kotlin.math.abs(Color.green(pixel) - Color.green(plainPixel)) <= 2 &&
                kotlin.math.abs(Color.blue(pixel) - Color.blue(plainPixel)) <= 2
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
            phase = "list"
            val blue = File(artifacts, "blue.png")
            Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(20, 90, 210))
                blue.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
                recycle()
            }
            val rows = listOf(entry.copy(title = "Первый сериал"),
                entry.copy(id = "124", url = "/serial-124-Test.html", title = "Второй сериал", posterUrl = blue.toURI().toString()),
                entry.copy(id = "125", url = "/serial-125-Test.html", title = "Нет постера", posterUrl = File(artifacts, "missing.png").toURI().toString()))
            val selection = mutableStateOf(rows.first())
            val layout = mutableStateOf(CatalogLayout.STRIP)
            val prefetch = mutableStateOf(false)
            val empty = mutableStateOf(false)
            val published = PosterBackdropState()
            val renderedTheme = AtomicReference<InterfaceTheme>()
            val renderedSurface = AtomicReference<androidx.compose.ui.graphics.Color>()
            val renderedBackground = AtomicReference<androidx.compose.ui.graphics.Color>()
            val cards = mutableMapOf<String, FocusRequester>()
            val publish: PosterBackdropPublisher = published::attach
            runOnMainSync { appearance.value = InterfaceTheme.GLASS; activity!!.setContent { SeasonvarTheme(appearance.value) {
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
            fun focusRow(index: Int) {
                val key = "backdrop-fixture|" + rows[index].key
                await { cards.containsKey(key) && clickable(rows[index].title) != null }
                runOnMainSync { cards.getValue(key).requestFocus() }
                await { selection.value.key == rows[index].key && clickable(rows[index].title)?.isFocused == true }
            }
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
            val stripTop = bounds(rows[1].title).top
            phase = "list-grid"; runOnMainSync { layout.value = CatalogLayout.GRID; prefetch.value = true }
            await { clickable(rows[1].title)?.let { item ->
                Rect().also(item::getBoundsInScreen).top < stripTop
            } == true }
            uiAutomation.waitForIdle(300, 3000)
            focusRow(1)
            await { published.value?.url == rows[1].posterUrl && published.value?.bitmap?.value != null }
            capture("07-list-grid")
            focusRow(0); focusRow(1)
            await { published.value?.url == rows[1].posterUrl && button(rows[1].title).isFocused }
            phase = "list-missing"; focusRow(2)
            await { published.value?.url == rows[2].posterUrl && published.value?.bitmap?.value == null &&
                plainBackground(backgroundPixel()) && backgroundPixel() != secondListPixel }
            finishFade()
            val fallbackPixel = capture("10-missing-poster-fallback")
            check(plainBackground(fallbackPixel) && button(rows[2].title).isFocused)
            focusRow(1)
            await { published.value?.bitmap?.value != null && backgroundPixel() != plainPixel }
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
            await { clickable(rows[1].title) != null && bounds(rows[1].title).top >= stripTop }
            uiAutomation.waitForIdle(300, 3000)
            focusRow(1)
            capture("09-glass-strip")
            phase = "list-empty"; runOnMainSync { empty.value = true }
            await { published.value == null && backgroundPixel() == fallbackPixel }
            capture("11-empty-fallback")
            phase = "centered-fallback"
            runOnMainSync { activity!!.setContent { SeasonvarTheme(InterfaceTheme.GLASS) { PosterBackdropPage {} } } }
            await { !plainBackground(backgroundPixel(0.55f, 0.5f)) && plainBackground(backgroundPixel()) }
            finishFade()
            capture("12-centered-fallback")
            phase = "solid-placeholder"
            val delayedPoster = mutableStateOf<Bitmap?>(null)
            val waitingSource = PosterBackdropState()
            val themedFallback = mutableStateOf(false)
            runOnMainSync { activity!!.setContent { SeasonvarTheme(InterfaceTheme.GLASS) {
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
            phase = "main-loading-placeholder"
            val pendingPage = mutableStateOf(CatalogUiState(date = LocalDate.of(2026, 1, 1)))
            val topTitle = targetContext.getString(ru.seasonvar.tv.R.string.top_title)
            val settingsTitle = targetContext.getString(ru.seasonvar.tv.R.string.settings_title)
            runOnMainSync { activity!!.setContent {
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
            result.putBoolean("fallback_background", true)
            result.putBoolean("list_background_tracks_selection", true)
            result.putBoolean("prefetch_toggle", true)
            phase = "setting"
            runOnMainSync { appearance.value = InterfaceTheme.CLASSIC }
            runOnMainSync { activity!!.setContent { SeasonvarTheme(appearance.value) {
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
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            check(InterfaceSettings(targetContext).interfaceTheme.value == InterfaceTheme.GLASS)
            result.putBoolean("geometry_stable", true)
            result.putBoolean("focus_preserved", true)
            result.putBoolean("memory_bounded", true)
            result.putBoolean("fixture_only", true)
            result.putBoolean("script_success", true)
        } catch (error: Throwable) {
            runCatching { capture("failure") }
            result.putBoolean("script_success", false)
            result.putString("failure_type", error.javaClass.simpleName)
            result.putString("failure_phase", phase)
            result.putString("failure_line", error.stackTrace.firstOrNull { it.fileName == "PosterBackdropProbe.kt" }?.lineNumber.toString())
        } finally {
            runOnMainSync { app.interfaceSettings.setInterfaceTheme(original); activity?.finish(); model.close() }
            result.putString("artifacts", artifacts.absolutePath)
            finish(Activity.RESULT_OK, result)
        }
    }
}
