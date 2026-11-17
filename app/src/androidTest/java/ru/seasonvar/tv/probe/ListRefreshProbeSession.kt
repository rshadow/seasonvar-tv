package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import ru.seasonvar.tv.ui.navigation.Section
import ru.seasonvar.tv.ui.sectionTitle
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Владеет Activity и диагностикой; методы сценариев выполняются с потока Instrumentation. */
internal class ListRefreshProbeSession(private val probe: Instrumentation) {
    val targetContext get() = probe.targetContext
    val uiAutomation get() = probe.uiAutomation
    val result = Bundle()
    val artifacts = File(checkNotNull(targetContext.getExternalFilesDir("list-refresh-probe")), "run-" + System.currentTimeMillis())
    private var preview: MarkMenuPreviewActivity? = null
    val activity get() = checkNotNull(preview)
    var cover: Activity? = null
    var phase = "start"

    fun execute(checks: (ListRefreshFixtures) -> Unit) {
        var fixtures: ListRefreshFixtures? = null
        try {
            check(android.os.Build.MODEL.contains("sdk", true))
            fixtures = ListRefreshFixtures()
            main { fixtures.attach() }
            preview = startActivitySync(Intent(targetContext, MarkMenuPreviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MarkMenuPreviewActivity
            checks(fixtures)
        } catch (error: Throwable) {
            recordFailure(error)
        } finally {
            val cleanup = runCatching { main {
                try { cover?.finish(); preview?.finish() }
                finally { fixtures?.close() }
            } }
            cleanup.exceptionOrNull()?.let { if (!result.containsKey("failure_type")) {
                phase = "cleanup"; recordFailure(it)
            } }
            result.putString("artifacts", artifacts.absolutePath)
            probe.finish(if (result.containsKey("failure_type")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
        }
    }

    fun startActivitySync(intent: Intent): Activity = probe.startActivitySync(intent)
    fun sendKeyDownUpSync(code: Int) = probe.sendKeyDownUpSync(code)
    fun main(block: () -> Unit) = probe.runOnMainSync(block)
    fun settle() {
        val frames = CountDownLatch(1)
        main {
            // Вход в список ждёт четыре кадра; проверка фокуса следует после его завершения.
            var remaining = 6
            val callback = object : android.view.Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (--remaining == 0) frames.countDown()
                    else android.view.Choreographer.getInstance().postFrameCallback(this)
                }
            }
            android.view.Choreographer.getInstance().postFrameCallback(callback)
        }
        check(frames.await(5, TimeUnit.SECONDS))
        probe.waitForIdleSync(); uiAutomation.waitForIdle(250, 5000)
        if (android.os.Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache()
    }
    fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        node ?: return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    fun card(title: String) = find(uiAutomation.rootInActiveWindow) { node ->
        node.isClickable && find(node) { it.text?.toString() == title } != null
    }
    fun focusedCard() = find(uiAutomation.rootInActiveWindow) { node ->
        node.isClickable && node.isFocused && find(node) { it.text?.toString()?.startsWith("Карточка ") == true } != null
    }
    fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    fun settledCardBounds(title: String): Rect {
        var previous: Rect? = null
        var stable = 0
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            settle()
            val current = card(title)?.let(::bounds)
            stable = if (current != null && current == previous) stable + 1 else 0
            if (stable >= 2) return checkNotNull(current)
            previous = current
        }
        error("Card bounds did not settle")
    }
    fun capture(name: String) {
        check(artifacts.isDirectory || artifacts.mkdirs())
        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
        File(artifacts, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    fun menu(section: Section) {
        phase = "menu_" + section.storageId
        val label = targetContext.getString(sectionTitle(section))
        fun control() = find(uiAutomation.rootInActiveWindow) { node ->
            node.isClickable && bounds(node).right <= bounds(checkNotNull(uiAutomation.rootInActiveWindow)).width() / 3 &&
                find(node) { it.text?.toString() == label } != null
        }
        var node = control()
        if (node == null) {
            val labels = Section.startPages(true).map { targetContext.getString(sectionTitle(it)) }.toSet()
            val anchor = find(uiAutomation.rootInActiveWindow) { candidate ->
                candidate.isClickable && bounds(candidate).right <= bounds(checkNotNull(uiAutomation.rootInActiveWindow)).width() / 3 &&
                    find(candidate) { it.text?.toString() in labels } != null
            }
            check(checkNotNull(anchor).performAction(AccessibilityNodeInfo.ACTION_FOCUS)); settle()
            for (direction in listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP)) {
                repeat(20) {
                    if (node == null) {
                        probe.sendKeyDownUpSync(direction); settle()
                        node = control()
                    }
                }
            }
        }
        node?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id); settle()
        node = control()
        check(checkNotNull(node) { label }.performAction(AccessibilityNodeInfo.ACTION_FOCUS)); settle()
    }

    fun describe(node: AccessibilityNodeInfo?, depth: Int = 0): String {
        node ?: return ""
        return " ".repeat(depth) + listOf(node.className, node.text, node.contentDescription, node.isFocused, node.isClickable)
            .joinToString("|") + "\n" + (0 until node.childCount).joinToString("") { describe(node.getChild(it), depth + 1) }
    }

    private fun recordFailure(error: Throwable) {
        result.putBoolean("script_success", false)
        runCatching { capture("failure") }
        runCatching { File(artifacts, "failure.txt").writeText(error.stackTraceToString()) }
        runCatching { File(artifacts, "accessibility.txt").writeText(describe(uiAutomation.rootInActiveWindow)) }
        result.putString("failure_phase", phase); result.putString("failure_type", error.javaClass.simpleName)
        result.putString("failure_detail", error.message)
        val frame = error.stackTrace.firstOrNull { it.className.startsWith("ru.seasonvar.tv.probe.List") }
        result.putInt("failure_line", frame?.lineNumber ?: -1)
        frame?.let { result.putString("failure_detail", "${it.fileName}:${it.lineNumber}: ${error.message}") }
    }
}
