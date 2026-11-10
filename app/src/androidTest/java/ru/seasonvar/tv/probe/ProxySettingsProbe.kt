package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.account.EncryptedSessionStore
import ru.seasonvar.tv.data.network.*
import ru.seasonvar.tv.ui.SeasonvarApp
import ru.seasonvar.tv.ui.navigation.Section
import java.io.File

object ProxySettingsProbe {
    fun run(probe: Instrumentation) = with(probe) {
        val result = Bundle()
        val artifacts = File(targetContext.getExternalFilesDir(null), "proxy-settings-probe/run-${SystemClock.uptimeMillis()}")
        val storeName = "proxy-fixture-${SystemClock.uptimeMillis()}"
        val preferences = targetContext.getSharedPreferences(storeName, Context.MODE_PRIVATE)
        var activity: MarkMenuPreviewActivity? = null
        var phase = "start"
        try {
            check(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.FINGERPRINT.contains("generic"))
            EncryptedSessionStore(targetContext).read()
            result.putBoolean("session_readable", true)
            val settings = NetworkSettings(targetContext, storeName)
            val proxy = mutableStateOf<ProxyConfiguration>(ProxyConfiguration.Disabled)
            var saves = 0
            fun settle() { Thread.sleep(500); waitForIdleSync(); if (android.os.Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache() }
            fun key(code: Int) { sendKeyDownUpSync(code); settle() }
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
            fun capture(name: String) {
                check(artifacts.isDirectory || artifacts.mkdirs())
                val image = checkNotNull(uiAutomation.takeScreenshot())
                File(artifacts, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            activity = startActivitySync(Intent(targetContext, MarkMenuPreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MarkMenuPreviewActivity
            runOnMainSync { activity.setContent {
                SeasonvarApp(ru.seasonvar.tv.data.PublicFeed.entries.associateWith { ru.seasonvar.tv.ui.catalog.CatalogUiState(loading = false) }, {}, startPage = Section.SETTINGS, imagePrefetchEnabled = false,
                    proxy = proxy.value, onProxyChange = {
                        withContext(Dispatchers.IO) { settings.setProxy(it) }
                        saves++; proxy.value = it; true
                    })
            } }
            settle()
            phase = "open"
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            repeat(10) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            capture("proxy-open")
            ButtonAlignmentChecks.centered(probe, text(R.string.proxy_save))
            ButtonAlignmentChecks.centered(probe, text(R.string.proxy_cancel))
            capture("proxy-off")
            phase = "edit"
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            fill(R.string.proxy_host, "127.0.0.1")
            fill(R.string.proxy_port, "4080")
            fill(R.string.proxy_username, "fixture-user")
            fill(R.string.proxy_password, "fixture-password")
            check(node(R.string.proxy_password, editable = true).isPassword)
            capture("proxy-edit")
            click(R.string.proxy_save)
            val expected = ProxyConfiguration.Server("127.0.0.1", 4080, "fixture-user", "fixture-password")
            check(proxy.value == expected && saves == 1)
            check(NetworkSettings(targetContext, storeName).configuration.value.proxy == expected)
            val sealed = checkNotNull(preferences.getString("proxy_sealed", null))
            check(!sealed.contains("fixture-user") && !sealed.contains("fixture-password"))
            result.putBoolean("proxy_saved", true)
            phase = "cancel"
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            fill(R.string.proxy_username, "changed")
            click(R.string.proxy_cancel)
            check(proxy.value == expected && saves == 1)
            result.putBoolean("proxy_cancelled", true)
            phase = "corrupt"
            check(preferences.edit().putString("proxy_sealed", "corrupt").commit())
            check(NetworkSettings(targetContext, storeName).configuration.value.proxy == ProxyConfiguration.Unavailable)
            settings.setProxy(ProxyConfiguration.Disabled)
            check(NetworkSettings(targetContext, storeName).configuration.value.proxy == ProxyConfiguration.Disabled)
            result.putBoolean("proxy_fail_closed", true)
            result.putBoolean("text_centered", true)
            result.putBoolean("fixture_only", true)
            result.putBoolean("script_success", true)
        } catch (error: Throwable) {
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
            result.putString("failure_line", error.stackTrace.firstOrNull { it.className.contains("ProxySettingsProbe") }?.lineNumber?.toString())
            result.putBoolean("script_success", false)
        } finally {
            preferences.edit().clear().commit()
            if (artifacts.exists()) result.putString("artifacts", artifacts.absolutePath)
            runOnMainSync { activity?.finish() }
            finish(Activity.RESULT_OK, result)
        }
    }
}
