package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import coil.Coil
import coil.ImageLoader
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.SuccessResult
import kotlinx.coroutines.CompletableDeferred
import ru.seasonvar.tv.data.SeriesDescription
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.ui.SelectedPanel
import ru.seasonvar.tv.ui.catalog.DescriptionState
import ru.seasonvar.tv.ui.settings.InterfaceTheme
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

internal object SelectedPanelProbe {
    fun run(probe: Instrumentation, theme: InterfaceTheme) = with(probe) {
        check(android.os.Build.MODEL.contains("sdk", true))
        checkThemeConfiguration(targetContext)
        val result = Bundle()
        val artifacts = File(checkNotNull(targetContext.getExternalFilesDir("selected-panel-probe")), "run-" + System.currentTimeMillis())
        check(artifacts.mkdirs())
        val color = 0xff26a4d7.toInt()
        fun image(name: String, width: Int, height: Int) = File(artifacts, "$name.png").also { file ->
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawPaint(Paint().apply { this.color = color })
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val small = image("source-small", 80, 120)
        val portrait = image("source-portrait", 600, 1200)
        val wide = image("source-wide", 900, 600)
        val title = "Длинное название сериала для проверки постоянной ширины описания и области постера"
        fun entry(source: String) = SeriesEntry("fixture", "https://seasonvar.ru/serial-1-Fixture.html", title, 1, "", source)
        val selection = mutableStateOf(entry(small.toURI().toString()))
        val appearance = mutableStateOf(theme)
        val description = mutableStateOf(DescriptionState(loading = true))
        val panelWidth = mutableIntStateOf(600)
        val panelHeight = mutableIntStateOf(300)
        val bounds = AtomicReference<Rect>()
        val parameters = AtomicReference<Pair<Float, Float>>()
        val delayed = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val largeFailures = AtomicInteger()
        val cacheHits = AtomicInteger()
        val originalLoader = Coil.imageLoader(targetContext)
        val loader = ImageLoader.Builder(targetContext).diskCache(null).components {
            add(Interceptor { chain ->
                val source = chain.request.data.toString()
                val file = when (source) {
                    "https://cdn.svstat.net/oblojka/large/901.jpg" -> { delayed.await(); portrait }
                    "https://cdn.svstat.net/oblojka/large/903.jpg" -> { cancelled.await(); portrait }
                    "https://cdn.svstat.net/oblojka/large/902.jpg" -> {
                        largeFailures.incrementAndGet()
                        return@Interceptor ErrorResult(null, chain.request, IOException("Fixture missing large image"))
                    }
                    "https://cdn.svstat.net/oblojka/902.jpg" -> small
                    else -> {
                        check(!source.startsWith("http")) { "Unexpected fixture request" }
                        null
                    }
                }
                val request = if (file == null) chain.request else chain.request.newBuilder().data(file).memoryCacheKey(source).build()
                chain.proceed(request).also { response ->
                    if (response is SuccessResult && response.dataSource == coil.decode.DataSource.MEMORY_CACHE) cacheHits.incrementAndGet()
                }
            })
        }.build()
        var activity: MarkMenuPreviewActivity? = null
        var phase = "render"
        fun main(block: () -> Unit) = runOnMainSync(block)
        fun settle() {
            val complete = CountDownLatch(1)
            main { Choreographer.getInstance().postFrameCallback {
                Choreographer.getInstance().postFrameCallback { complete.countDown() }
            } }
            check(complete.await(5, TimeUnit.SECONDS))
            waitForIdleSync()
            uiAutomation.waitForIdle(100, 2000)
            if (android.os.Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache()
        }
        fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString() == text) return node
            for (index in 0 until node.childCount) find(node.getChild(index), text)?.let { return it }
            return null
        }
        fun titleBounds() = Rect().also { checkNotNull(find(uiAutomation.rootInActiveWindow, title)).getBoundsInScreen(it) }
        fun coloredBounds(): Rect? {
            val image = checkNotNull(uiAutomation.takeScreenshot())
            val area = checkNotNull(bounds.get())
            var left = image.width; var right = -1; var top = image.height; var bottom = -1
            for (y in area.top until area.bottom) for (x in area.left until area.right) {
                if (image.getPixel(x, y) == color) {
                    left = minOf(left, x); right = maxOf(right, x)
                    top = minOf(top, y); bottom = maxOf(bottom, y)
                }
            }
            image.recycle()
            return if (right < 0) null else Rect(left, top, right + 1, bottom + 1)
        }
        fun await(predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10000
            do { settle(); if (predicate()) return } while (SystemClock.elapsedRealtime() < deadline)
            error("Fixture state timed out")
        }
        fun expected(width: Int, height: Int): Rect {
            val area = checkNotNull(bounds.get())
            val (ratio, limit) = checkNotNull(parameters.get())
            val slotWidth = min(area.height() * ratio, area.width() * limit).roundToInt()
            val scale = min(1f, min(slotWidth.toFloat() / width, area.height().toFloat() / height))
            val drawnWidth = width * scale; val drawnHeight = height * scale
            val x = (area.right - slotWidth + (slotWidth - drawnWidth) / 2f).roundToInt()
            val y = (area.top + (area.height() - drawnHeight) / 2f).roundToInt()
            return Rect(x, y, x + drawnWidth.roundToInt(), y + drawnHeight.roundToInt())
        }
        fun checkImage(name: String, width: Int, height: Int) {
            phase = name
            await {
                val actual = coloredBounds(); val wanted = expected(width, height)
                actual != null && abs(actual.left - wanted.left) <= 2 && abs(actual.top - wanted.top) <= 2 &&
                    abs(actual.right - wanted.right) <= 2 && abs(actual.bottom - wanted.bottom) <= 2
            }
            val screenshot = checkNotNull(uiAutomation.takeScreenshot())
            File(artifacts, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        }
        try {
            Coil.setImageLoader(loader)
            activity = startActivitySync(Intent(targetContext, MarkMenuPreviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MarkMenuPreviewActivity
            main { activity!!.setContent {
                SeasonvarTheme(appearance.value) {
                    val dimensions = SeasonvarTheme.dimensions
                    SideEffect { parameters.set(dimensions.previewPosterAspectRatio to dimensions.previewPosterMaxWidthFraction) }
                    Box(Modifier.fillMaxSize().background(SeasonvarTheme.pageBackground).padding(20.dp)) {
                        SelectedPanel(selection.value, { description.value }, Modifier.width(panelWidth.intValue.dp)
                            .height(panelHeight.intValue.dp).onGloballyPositioned {
                                val r = it.boundsInWindow()
                                bounds.set(Rect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt()))
                            })
                    }
                }
            } }
            checkImage("small-native", 80, 120)
            main { selection.value = entry(portrait.toURI().toString()) }; checkImage("portrait-fit", 600, 1200)
            main { selection.value = entry(wide.toURI().toString()) }; checkImage("wide-fit", 900, 600)
            main { selection.value = entry(small.toURI().toString()) }; checkImage("small-cached", 80, 120)
            check(cacheHits.get() > 0)
            main { selection.value = entry("https://cdn.svstat.net/oblojka/901.jpg") }; settle()
            val initial = titleBounds(); val initialPanel = Rect(bounds.get())
            main { description.value = DescriptionState(selection.value.key,
                SeriesDescription(listOf("Полученное описание сериала. ".repeat(30)))) }; settle()
            check(titleBounds() == initial && bounds.get() == initialPanel)
            delayed.complete(Unit); checkImage("delayed-image", 600, 1200)
            check(titleBounds() == initial && bounds.get() == initialPanel)
            main { selection.value = entry("https://cdn.svstat.net/oblojka/903.jpg") }; settle()
            main { selection.value = entry(small.toURI().toString()) }; settle()
            cancelled.complete(Unit); checkImage("late-response", 80, 120)
            main { selection.value = entry("https://cdn.svstat.net/oblojka/902.jpg") }
            checkImage("fallback-native", 80, 120)
            check(largeFailures.get() == 1)
            main { panelWidth.intValue = 240 }; checkImage("narrow-window", 80, 120)
            main { panelWidth.intValue = 600; panelHeight.intValue = 180 }
            checkImage("short-window", 80, 120)
            main { appearance.value = ru.seasonvar.tv.ui.settings.loadInterfaceThemes(targetContext, ru.seasonvar.tv.R.xml.theme_config_probe).default }
            settle()
            check(parameters.get() == (0.6f to 0.4f))
            checkImage("custom-theme", 80, 120)
            result.putBoolean("selected_panel", true)
            result.putBoolean("native_size_preserved", true)
            result.putBoolean("poster_centered", true)
            result.putBoolean("geometry_stable", true)
            result.putBoolean("fixture_only", true)
            result.putBoolean("script_success", true)
            result.putString("artifacts", artifacts.absolutePath)
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            File(artifacts, "failure.txt").writeText(error.stackTraceToString())
            uiAutomation.takeScreenshot()?.let { screenshot ->
                File(artifacts, "failure.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
            }
            result.putString("failure_phase", phase); result.putString("failure_type", error.javaClass.simpleName)
            result.putString("artifacts", artifacts.absolutePath)
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            delayed.complete(Unit); cancelled.complete(Unit)
            main { activity?.finish() }
            Coil.setImageLoader(originalLoader)
            loader.shutdown()
        }
    }
}
