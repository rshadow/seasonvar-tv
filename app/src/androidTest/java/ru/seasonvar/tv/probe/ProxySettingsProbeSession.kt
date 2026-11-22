package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.seasonvar.tv.R
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.data.account.DeviceSessionStore
import ru.seasonvar.tv.data.network.ProxyConfiguration
import ru.seasonvar.tv.ui.SeasonvarApp
import ru.seasonvar.tv.ui.navigation.Section
import java.io.File

/** Владеет Activity и диагностикой; сценарии выполняются на потоке Instrumentation. */
internal class ProxySettingsProbeSession(private val probe: Instrumentation) {
    val targetContext get() = probe.targetContext
    val app get() = targetContext.applicationContext as SeasonvarApplication
    val uiAutomation get() = probe.uiAutomation
    val result = Bundle()
    private val artifacts = File(targetContext.getExternalFilesDir(null), "proxy-settings-probe/run-${SystemClock.uptimeMillis()}")
    private var preview: MarkMenuPreviewActivity? = null
    private val activity get() = checkNotNull(preview)
    var phase = "start"

    /** Останавливает фоновые задачи, сохраняя настройки приложения; после пробы нужен обычный перезапуск приложения. */
    fun execute(checks: (ProxySettingsFixtures) -> Unit) {
        var fixture: ProxySettingsFixtures? = null
        try {
            check(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.FINGERPRINT.contains("generic"))
            DeviceSessionStore(targetContext).read()
            result.putBoolean("session_readable", true)
            kotlinx.coroutines.runBlocking { app.applicationScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin() }
            fixture = ProxySettingsFixtures(targetContext)
            preview = probe.startActivitySync(Intent(targetContext, MarkMenuPreviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MarkMenuPreviewActivity
            checks(fixture)
        } catch (error: Throwable) {
            recordFailure(error)
        } finally {
            val cleanup = runCatching { try { fixture?.close() } finally { main { preview?.finish() } } }
            cleanup.exceptionOrNull()?.let { if (!result.containsKey("failure_type")) {
                phase = "cleanup"; recordFailure(it)
            } }
            if (artifacts.exists()) result.putString("artifacts", artifacts.absolutePath)
            probe.finish(Activity.RESULT_OK, result)
        }
    }

    fun main(block: () -> Unit) = probe.runOnMainSync(block)
    fun centered(id: Int) = ButtonAlignmentChecks.centered(probe, text(id))
    fun pacPreview() { phase = "pac_preview"; PacRowChecks.run(probe, activity, artifacts) }
    fun render(fixture: ProxySettingsFixtures, identity: Any) = with(fixture) {
        main { activity.setContent {} }
        settle()
        saves = 0
        main { proxy.value = ProxyConfiguration.Disabled; activity.setContent { key(identity) {
            SeasonvarApp(ru.seasonvar.tv.data.PublicFeed.entries.associateWith { ru.seasonvar.tv.ui.catalog.CatalogUiState(loading = false) }, {}, startPage = Section.SETTINGS, imagePrefetchEnabled = false,
                proxy = proxy.value, onProxyChange = {
                    withContext(Dispatchers.IO) { settings.setProxy(it) }
                    saves++; proxy.value = it; true
                })
        } } }
        settle()
    }

    fun settle() { Thread.sleep(500); probe.waitForIdleSync(); if (android.os.Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache() }
    fun key(code: Int) { probe.sendKeyDownUpSync(code); settle() }
    fun text(id: Int) = targetContext.getString(id)
    fun find(node: AccessibilityNodeInfo?, label: String, editable: Boolean = false): AccessibilityNodeInfo? {
        node ?: return null
        if (!editable && (node.text?.toString() == label || node.contentDescription?.toString() == label)) return node
        if (editable && node.contentDescription?.toString() == label) {
            var field: AccessibilityNodeInfo = node
            while (!field.isEditable && field.parent != null) field = field.parent
            if (field.isEditable) return field
        }
        for (i in 0 until node.childCount) find(node.getChild(i), label, editable)?.let { return it }
        return null
    }
    fun scrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        for (i in (0 until node.childCount).reversed()) scrollable(node.getChild(i))?.let { return it }
        return node.takeIf { it.isScrollable }
    }
    fun node(id: Int, editable: Boolean = false): AccessibilityNodeInfo {
        repeat(10) {
            find(uiAutomation.rootInActiveWindow, text(id), editable)?.let { return it }
            scrollable(uiAutomation.rootInActiveWindow)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            settle()
        }
        error("Missing fixture control")
    }
    fun click(id: Int) {
        var target = node(id)
        while (!target.isClickable && target.parent != null) target = target.parent
        check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)); settle()
    }
    fun fill(id: Int, value: String) {
        check(node(id, editable = true).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })); settle()
    }
    fun focusSetting(id: Int) {
        repeat(30) {
            var target = find(uiAutomation.rootInActiveWindow, text(id))
            while (target != null && !target.isClickable && target.parent != null) target = target.parent
            if (target?.isFocused == true) return
            key(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        error("Setting focus missing: " + text(id))
    }
    fun capture(name: String) {
        check(artifacts.isDirectory || artifacts.mkdirs())
        val image = checkNotNull(uiAutomation.takeScreenshot())
        File(artifacts, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun recordFailure(error: Throwable) {
        runCatching {
            check(artifacts.isDirectory || artifacts.mkdirs())
            val image = checkNotNull(uiAutomation.takeScreenshot())
            File(artifacts, "failure.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
            val nodes = mutableListOf<String>()
            fun dump(node: AccessibilityNodeInfo?) {
                node ?: return
                nodes += "${node.className}|${node.isEditable}|${node.isPassword}|${node.isScrollable}|${node.contentDescription}|${if (node.isPassword) "masked" else node.text}"
                for (i in 0 until node.childCount) dump(node.getChild(i))
            }
            dump(uiAutomation.rootInActiveWindow)
            File(artifacts, "tree.txt").writeText(nodes.joinToString("\n"))
        }
        result.putString("failure_geometry", error.message?.takeIf { it in listOf("Missing text", "Horizontal text alignment", "Vertical text alignment", "Missing character bounds") }?.replace(' ', '_'))
        result.putString("failure_type", error.javaClass.simpleName)
        result.putString("failure_phase", phase)
        val frame = error.stackTrace.firstOrNull { it.className.startsWith("ru.seasonvar.tv.probe.Proxy") || it.className.startsWith("ru.seasonvar.tv.probe.PacProxy") || it.className.startsWith("ru.seasonvar.tv.probe.ManualProxy") }
        result.putString("failure_line", frame?.lineNumber?.toString())
        result.putBoolean("script_success", false)
        frame?.let { result.putString("failure_detail", "${it.fileName}:${it.lineNumber}") }
    }
}
