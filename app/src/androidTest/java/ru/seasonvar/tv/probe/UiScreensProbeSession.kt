package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.tv.material3.MaterialTheme
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import ru.seasonvar.tv.ui.theme.resolveInterfaceTheme
import ru.seasonvar.tv.ui.settings.InterfaceTheme
import java.io.File

/** Управляет Activity и снимками; сценарии вызываются с потока Instrumentation. */
internal class UiScreensProbeSession(private val probe: Instrumentation, private val theme: InterfaceTheme) {
    val targetContext get() = probe.targetContext
    val uiAutomation get() = probe.uiAutomation
    val result = Bundle()
    private var activity: MarkMenuPreviewActivity? = null
    private var phase = "start"
    private var revision = 0
    private val artifacts = File(checkNotNull(targetContext.getExternalFilesDir("ui-screens-probe")), "run-" + System.currentTimeMillis())

    /** Закрывает UI и модели при любом исходе; ошибки сценария и очистки возвращает в Bundle. */
    fun execute(checks: (UiScreensFixtures) -> Unit) {
        var fixtures: UiScreensFixtures? = null
        try {
            check(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.FINGERPRINT.contains("generic"))
            fixtures = UiScreensFixtures()
            main { fixtures.attach() }
            activity = probe.startActivitySync(Intent(targetContext, MarkMenuPreviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MarkMenuPreviewActivity
            checks(fixtures)
        } catch (error: Throwable) {
            recordFailure(error)
        } finally {
            val cleanup = runCatching { main { try { activity?.finish() } finally { fixtures?.close() } } }
            cleanup.exceptionOrNull()?.let { if (!result.containsKey("failure_type")) {
                phase = "cleanup"; recordFailure(it)
            } }
            if (artifacts.exists()) result.putString("artifacts", artifacts.absolutePath)
            probe.finish(Activity.RESULT_OK, result)
        }
    }

    fun sendKeyDownUpSync(code: Int) = probe.sendKeyDownUpSync(code)
    fun main(block: () -> Unit) { probe.runOnMainSync(block) }
    fun waitFor(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out" }
            Thread.sleep(50)
            if (android.os.Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache()
        }
    }
    fun find(node: AccessibilityNodeInfo?, label: String, exact: Boolean = false): AccessibilityNodeInfo? {
        node ?: return null
        val textMatches = if (exact) node.text?.toString() == label else node.text?.toString()?.startsWith(label) == true
        if (textMatches || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label, exact)?.let { return it }
        return null
    }
    fun text(id: Int) = targetContext.getString(id)
    fun control(label: String): AccessibilityNodeInfo? {
        var node = find(uiAutomation.rootInActiveWindow, label) ?: return null
        while (!node.isClickable && node.parent != null) node = node.parent
        return node.takeIf { it.isClickable }
    }
    fun settle() { Thread.sleep(500); probe.waitForIdleSync(); if (android.os.Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache() }
    fun click(label: String) {
        if (control(label) == null) {
            probe.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            settle()
            for (direction in listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP)) {
                for (attempt in 0 until 20) {
                    if (control(label) != null) break
                    probe.sendKeyDownUpSync(direction)
                    settle()
                }
                if (control(label) == null && direction == KeyEvent.KEYCODE_DPAD_DOWN) {
                    check(artifacts.isDirectory || artifacts.mkdirs())
                    val image = checkNotNull(uiAutomation.takeScreenshot())
                    File(artifacts, "navigation-bottom.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    image.recycle()
                    val lines = mutableListOf<String>()
                    fun dump(node: AccessibilityNodeInfo?) {
                        node ?: return
                        lines += "${node.className}|${node.isClickable}|${node.isFocused}|${node.text}|${node.contentDescription}"
                        for (i in 0 until node.childCount) dump(node.getChild(i))
                    }
                    dump(uiAutomation.rootInActiveWindow)
                    File(artifacts, "navigation-tree.txt").writeText(lines.joinToString("\n"))
                }
            }
        }
        waitFor { control(label) != null }
        control(label)?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        settle()
        check(checkNotNull(control(label)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        settle()
    }
    fun back() { probe.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle() }
    fun stage(name: String) { phase = name; probe.sendStatus(Activity.RESULT_OK, Bundle().apply { putString("phase", name) }) }
    fun capture(name: String) {
        settle()
        check(artifacts.isDirectory || artifacts.mkdirs())
        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
        File(artifacts, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    fun checkSpoilerFieldContrast(field: AccessibilityNodeInfo) {
        val bounds = Rect().also(field::getBoundsInScreen)
        val screenshot = checkNotNull(uiAutomation.takeScreenshot())
        val background = try {
            screenshot.getPixel(bounds.left + bounds.width() * 3 / 4, bounds.centerY())
        } finally {
            screenshot.recycle()
        }
        val palette = resolveInterfaceTheme(targetContext, theme)
        check(ColorUtils.calculateContrast(palette.text.toArgb(), background) >= 4.5) { "Spoiler text contrast" }
        check(ColorUtils.calculateContrast(palette.secondaryText.toArgb(), background) >= 4.5) { "Spoiler hint contrast" }
    }

    /** Пересоздаёт состояние композиции для нового экрана; открытия диалогов не вызывают render. */
    fun render(content: @Composable () -> Unit) {
        val current = ++revision
        main { checkNotNull(activity).setContent { key(current) { SeasonvarTheme(theme) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
        } } } }
        settle()
    }

    fun centered(vararg labels: String) { labels.forEach { label ->
        waitFor { find(uiAutomation.rootInActiveWindow, label, exact = true) != null }
        ButtonAlignmentChecks.centered(probe, label)
    } }

    private fun recordFailure(error: Throwable) {
        runCatching { capture("failure") }
        result.putString("failure_type", error.javaClass.simpleName); result.putString("failure_phase", phase)
        val frame = error.stackTrace.firstOrNull { it.className.startsWith("ru.seasonvar.tv.probe.UiScreen") }
        result.putString("failure_line", frame?.lineNumber?.toString() ?: "unknown")
        frame?.let { result.putString("failure_detail", "${it.fileName}:${it.lineNumber}") }
        result.putBoolean("script_success", false)
    }
}
