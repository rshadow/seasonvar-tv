package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.graphics.toArgb
import ru.seasonvar.tv.R
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.ui.settings.InterfaceTheme
import ru.seasonvar.tv.ui.theme.resolveInterfaceTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class BackdropProbeSession(private val probe: Instrumentation) {
    val targetContext get() = probe.targetContext
    val app get() = targetContext.applicationContext as SeasonvarApplication
    val uiAutomation get() = probe.uiAutomation
    val result = Bundle()
    val artifacts = File(checkNotNull(targetContext.getExternalFilesDir("poster-backdrop-probe")),
        "run-" + System.currentTimeMillis())
    private var preview: MarkMenuPreviewActivity? = null
    val activity get() = checkNotNull(preview)
    var phase = "setup"

    /**
     * При любом исходе восстанавливает тему и закрывает UI и модель.
     * Ошибки проверок и очистки передаёт через итоговый Bundle, не пробрасывая вызывающему коду.
     */
    fun execute(checks: (BackdropFixtures) -> Unit) {
        var original: InterfaceTheme? = null
        var fixtures: BackdropFixtures? = null
        try {
            check(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.FINGERPRINT.contains("generic"))
            original = app.interfaceSettings.interfaceTheme.value
            check(artifacts.isDirectory || artifacts.mkdirs())
            fixtures = BackdropFixtures(artifacts)
            result.putBoolean("fixture_only", true)
            checks(fixtures)
            result.putBoolean("script_success", true)
        } catch (error: Throwable) {
            recordFailure(error)
        } finally {
            cleanup(original, fixtures)
            result.putString("artifacts", artifacts.absolutePath)
            probe.finish(Activity.RESULT_OK, result)
        }
    }

    private fun cleanup(original: InterfaceTheme?, fixtures: BackdropFixtures?) {
        val uiCleanup = runCatching { runOnMainSync {
            try { original?.let { app.interfaceSettings.setInterfaceTheme(it) } }
            finally { preview?.finish() }
        }; waitForIdleSync() }
        val resourceCleanup = runCatching { runOnMainSync { fixtures?.close() } }
        (uiCleanup.exceptionOrNull() ?: resourceCleanup.exceptionOrNull())?.let { error ->
            if (!result.containsKey("failure_type")) { phase = "cleanup"; recordFailure(error) }
        }
    }

    fun openActivity() {
        phase = "activity"
        preview = probe.startActivitySync(Intent(targetContext, MarkMenuPreviewActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MarkMenuPreviewActivity
    }

    fun passed(vararg names: String) { names.forEach { result.putBoolean(it, true) } }
    fun runOnMainSync(block: () -> Unit) = probe.runOnMainSync(block)
    fun waitForIdleSync() = probe.waitForIdleSync()
    fun sendKeyDownUpSync(code: Int) = probe.sendKeyDownUpSync(code)

    fun node(text: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            repeat(node.childCount) { find(node.getChild(it))?.let { found -> return found } }
            return null
        }
        return find(uiAutomation.rootInActiveWindow)
    }

    fun clickable(text: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) {
                var item: AccessibilityNodeInfo = node
                while (!item.isClickable && item.parent != null) item = item.parent
                if (item.isClickable) return item
            }
            repeat(node.childCount) { find(node.getChild(it))?.let { found -> return found } }
            return null
        }
        return find(uiAutomation.rootInActiveWindow)
    }
    fun button(text: String) = checkNotNull(clickable(text))
    fun bounds(text: String) = Rect().also { button(text).getBoundsInScreen(it) }

    /** Сбрасывает кеш доступности перед чтением, чтобы не принять устаревшее состояние фокуса. */
    fun await(predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 5000
        do {
            waitForIdleSync(); uiAutomation.waitForIdle(100, 1000)
            if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
            if (predicate()) return
        } while (SystemClock.elapsedRealtime() < end)
        error("Expected fixture state")
    }

    /** Сохраняет полный кадр и возвращает контрольный пиксель: ¾ ширины, ¼ высоты. */
    fun capture(name: String): Int {
        uiAutomation.waitForIdle(300, 3000)
        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
        try {
            File(artifacts, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            return bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 4)
        } finally { bitmap.recycle() }
    }

    /** Координаты задаются как доли ширины и высоты снимка. */
    fun backgroundPixel(x: Float = 0.75f, y: Float = 0.25f): Int {
        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
        try { return bitmap.getPixel((bitmap.width * x).toInt(), (bitmap.height * y).toInt()) }
        finally { bitmap.recycle() }
    }

    fun plainBackground(pixel: Int): Boolean {
        val base = resolveInterfaceTheme(targetContext, InterfaceTheme.CLASSIC).pageBackground.toArgb()
        return kotlin.math.abs(Color.red(pixel) - Color.red(base)) <= 2 &&
            kotlin.math.abs(Color.green(pixel) - Color.green(base)) <= 2 &&
            kotlin.math.abs(Color.blue(pixel) - Color.blue(base)) <= 2
    }

    /** Считает пиксели наиболее частого близкого цвета внутри области глифов, исключая края области. */
    fun textPixels(region: Rect, color: Int, tolerance: Int = 32): Int {
        val screenshot = checkNotNull(uiAutomation.takeScreenshot())
        try {
            val inner = Rect(region)
            val inset = minOf(inner.width() / 8, inner.height() / 4)
            inner.inset(inset, inset)
            val pixels = IntArray(inner.width() * inner.height())
            screenshot.getPixels(pixels, 0, inner.width(), inner.left, inner.top, inner.width(), inner.height())
            val counts = pixels.toList().groupingBy { it }.eachCount()
            return counts.filterKeys { pixel -> kotlin.math.abs(Color.red(pixel) - Color.red(color)) <= tolerance &&
                kotlin.math.abs(Color.green(pixel) - Color.green(color)) <= tolerance &&
                kotlin.math.abs(Color.blue(pixel) - Color.blue(color)) <= tolerance }.values.maxOrNull() ?: 0
        } finally { screenshot.recycle() }
    }

    fun finishFade() {
        val complete = CountDownLatch(1)
        val duration = targetContext.resources.getInteger(R.integer.seasonvar_poster_backdrop_fade_ms) * 1_000_000L
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

    private fun recordFailure(error: Throwable) {
        if (preview?.let { !it.isFinishing && !it.isDestroyed } == true) runCatching { capture("failure") }
        result.putBoolean("script_success", false)
        result.putString("failure_type", error.javaClass.simpleName)
        result.putString("failure_phase", phase)
        val frame = error.stackTrace.firstOrNull {
            it.className.startsWith("ru.seasonvar.tv.probe.") && it.fileName != "BackdropProbeSession.kt"
        }
        result.putString("failure_line", frame?.lineNumber.toString())
        frame?.let { result.putString("failure_detail", "${it.fileName}:${it.lineNumber}") }
    }
}
