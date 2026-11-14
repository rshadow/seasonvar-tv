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
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.runBlocking
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.images.PosterBackdropCache
import ru.seasonvar.tv.data.images.PosterBackdropSpec
import ru.seasonvar.tv.ui.details.SeriesDetailsPane
import ru.seasonvar.tv.ui.details.SeriesDetailsViewModel
import ru.seasonvar.tv.ui.settings.*
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import java.io.File

object PosterBackdropProbe {
    fun run(probe: Instrumentation) = with(probe) {
        val result = Bundle()
        var activity: MarkMenuPreviewActivity? = null
        val app = targetContext.applicationContext as SeasonvarApplication
        val watch = targetContext.getString(ru.seasonvar.tv.R.string.details_watch)
        val original = app.interfaceSettings.detailsAppearance.value
        val appearance = mutableStateOf(DetailsAppearance.PLAIN)
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
        fun button(text: String): AccessibilityNodeInfo {
            var item = checkNotNull(node(text))
            while (!item.isClickable && item.parent != null) item = item.parent
            return item
        }
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
        fun backgroundPixel(): Int {
            val bitmap = checkNotNull(uiAutomation.takeScreenshot())
            val pixel = bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 4)
            bitmap.recycle()
            return pixel
        }
        try {
            check(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.FINGERPRINT.contains("generic"))
            runBlocking {
                val cache = PosterBackdropCache()
                val spec = PosterBackdropSpec(1920, 1080, Color.rgb(43, 37, 33), 0.72f, 0.22f, 0.14f)
                val prepared = cache.prepare("one", poster, spec)
                check(prepared.width <= PosterBackdropCache.MAX_EDGE && prepared.height <= PosterBackdropCache.MAX_EDGE)
                check(cache.prepare("one", poster, spec) === prepared && poster.width == 320 && !poster.isRecycled)
                val hardware = checkNotNull(poster.copy(Bitmap.Config.HARDWARE, false))
                check(cache.prepare("hardware", hardware, spec).config == Bitmap.Config.ARGB_8888 && !hardware.isRecycled)
                repeat(PosterBackdropCache.MAX_ENTRIES + 1) { cache.prepare("item-$it", poster, spec) }
                check(cache.get("one", spec) == null)
                check(cache.cachedBytes <= PosterBackdropCache.MAX_ENTRIES * PosterBackdropCache.MAX_EDGE * PosterBackdropCache.MAX_EDGE * 4)
                result.putInt("backdrop_cache_bytes", cache.cachedBytes)
            }
            phase = "plain"
            activity = startActivitySync(Intent(targetContext, MarkMenuPreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MarkMenuPreviewActivity
            runOnMainSync { activity!!.setContent { SeasonvarTheme {
                val d = SeasonvarTheme.dimensions
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(d.screenPaddingHorizontal, d.screenPaddingVertical)) {
                    SeriesDetailsPane(entry, model, FocusRequester.Cancel, {}, {}, showActors = false, appearance = appearance.value)
                }
            } } }
            await { node(watch) != null && button(watch).isFocused }
            check(app.posterBackdrops.cachedBytes == 0)
            val playBounds = bounds(watch)
            val reviewBounds = bounds("Рецензии · 0")
            val plainPixel = capture("01-plain")
            phase = "blurred"
            runOnMainSync { appearance.value = DetailsAppearance.BLURRED_POSTER }
            await { app.posterBackdrops.cachedBytes > 0 }
            await { backgroundPixel() != plainPixel }
            await { button(watch).isFocused && bounds(watch) == playBounds }
            check(bounds("Рецензии · 0") == reviewBounds)
            val blurredPixel = capture("02-blurred")
            check(blurredPixel != plainPixel)
            runOnMainSync { appearance.value = DetailsAppearance.PLAIN }
            await { backgroundPixel() == plainPixel }
            await { button(watch).isFocused && bounds(watch) == playBounds }
            check(bounds("Рецензии · 0") == reviewBounds)
            check(capture("03-disabled") == plainPixel)
            phase = "setting"
            runOnMainSync { activity!!.setContent { SeasonvarTheme {
                val d = SeasonvarTheme.dimensions
                val focus = remember { FocusRequester() }
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(d.spacingLarge)) {
                    DetailsAppearanceSetting(appearance.value, { appearance.value = it; app.interfaceSettings.setDetailsAppearance(it) },
                        focus, FocusRequester.Cancel, FocusRequester.Cancel, FocusRequester.Cancel, {}, {}, {})
                }
                LaunchedEffect(Unit) { focus.requestFocus() }
            } } }
            await { node("Оформление карточки") != null }
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await { node("Размытый постер") != null }
            capture("04-options")
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await { appearance.value == DetailsAppearance.BLURRED_POSTER }
            check(InterfaceSettings(targetContext).detailsAppearance.value == DetailsAppearance.BLURRED_POSTER)
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
            runOnMainSync { app.interfaceSettings.setDetailsAppearance(original); activity?.finish(); model.close() }
            result.putString("artifacts", artifacts.absolutePath)
            finish(Activity.RESULT_OK, result)
        }
    }
}
