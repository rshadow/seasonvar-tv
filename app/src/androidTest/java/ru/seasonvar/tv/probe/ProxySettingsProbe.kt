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
import kotlinx.coroutines.cancelAndJoin
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
            val app = targetContext.applicationContext as ru.seasonvar.tv.SeasonvarApplication
            kotlinx.coroutines.runBlocking { app.applicationScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin() }
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
            for (protocol in ProxyProtocol.entries) {
                runOnMainSync { activity.setContent {} }
                settle()
                saves = 0
                runOnMainSync { proxy.value = ProxyConfiguration.Disabled; activity.setContent { key(protocol) {
                    SeasonvarApp(ru.seasonvar.tv.data.PublicFeed.entries.associateWith { ru.seasonvar.tv.ui.catalog.CatalogUiState(loading = false) }, {}, startPage = Section.SETTINGS, imagePrefetchEnabled = false,
                        proxy = proxy.value, onProxyChange = {
                            withContext(Dispatchers.IO) { settings.setProxy(it) }
                            saves++; proxy.value = it; true
                        })
                } } }
                settle()
                phase = "open"
                click(R.string.settings_title)
                repeat(7) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
                capture("settings-network")
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                capture("settings-connection")
                repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                capture("proxy-open")
                check(find(uiAutomation.rootInActiveWindow, text(R.string.proxy_host)) == null)
                check(find(uiAutomation.rootInActiveWindow, text(R.string.proxy_port)) == null)
                check(find(uiAutomation.rootInActiveWindow, text(R.string.proxy_username)) == null)
                check(find(uiAutomation.rootInActiveWindow, text(R.string.proxy_password)) == null)
                ButtonAlignmentChecks.centered(probe, text(R.string.proxy_save))
                ButtonAlignmentChecks.centered(probe, text(R.string.proxy_cancel))
                capture("proxy-${protocol.storageId}-off")
                phase = "edit"
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                capture("proxy-types")
                repeat(protocol.ordinal + 1) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                fill(R.string.proxy_host, "127.0.0.1")
                fill(R.string.proxy_port, "4080")
                fill(R.string.proxy_username, "fixture-user")
                fill(R.string.proxy_password, "fixture-password")
                check(node(R.string.proxy_password, editable = true).isPassword)
                capture("proxy-${protocol.storageId}-edit")
                click(R.string.proxy_save)
                val expected = ProxyConfiguration.Server("127.0.0.1", 4080, "fixture-user", "fixture-password", protocol)
                check(proxy.value == expected && saves == 1)
                capture("proxy-${protocol.storageId}-saved")
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
            }
            phase = "pac_modes"
            val manual = ProxyConfiguration.Server("proxy.example.test", 8080, "fixture", "fixture")
            settings.setProxy(manual)
            var commonGeometry: List<android.graphics.Rect>? = null
            for (type in listOf(3, 4)) {
                val url = "https://pac-fixture.invalid/run-${SystemClock.uptimeMillis()}.pac"
                val selected = if (type == 3) ProxyConfiguration.Pac(url) else ProxyConfiguration.AntiZapret
                val storage = ru.seasonvar.tv.data.network.pac.pacStorage(targetContext, selected)
                val originalInterval = storage.interval
                kotlinx.coroutines.runBlocking { app.pac.manager(selected).setInterval(ru.seasonvar.tv.data.settings.PacRefreshInterval.DAY) }
                try {
                    runOnMainSync { activity.setContent {} }; settle()
                    saves = 0
                    runOnMainSync { proxy.value = ProxyConfiguration.Disabled; activity.setContent { key(type) {
                        SeasonvarApp(ru.seasonvar.tv.data.PublicFeed.entries.associateWith { ru.seasonvar.tv.ui.catalog.CatalogUiState(loading = false) }, {},
                            startPage = Section.SETTINGS, imagePrefetchEnabled = false, proxy = proxy.value,
                            onProxyChange = { withContext(Dispatchers.IO) { settings.setProxy(it) }; saves++; proxy.value = it; true })
                    } } }
                    settle()
                    click(R.string.settings_title)
                    repeat(11) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    repeat(type) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    check(find(uiAutomation.rootInActiveWindow, text(R.string.proxy_host)) == null)
                    check(find(uiAutomation.rootInActiveWindow, text(R.string.pac_url)) != null)
                    if (type == 3) fill(R.string.pac_url, url)
                    else check(find(uiAutomation.rootInActiveWindow, ru.seasonvar.tv.data.settings.PacSettings.ANTIZAPRET_URL) != null)
                    ButtonAlignmentChecks.centered(probe, text(R.string.proxy_save))
                    ButtonAlignmentChecks.centered(probe, text(R.string.proxy_cancel))
                    val intervalLabel = targetContext.getString(R.string.pac_interval, text(R.string.pac_day))
                    var interval = checkNotNull(find(uiAutomation.rootInActiveWindow, intervalLabel))
                    while (!interval.isClickable && interval.parent != null) interval = interval.parent
                    check(interval.performAction(AccessibilityNodeInfo.ACTION_CLICK)); settle()
                    key(KeyEvent.KEYCODE_DPAD_DOWN)
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    settle()
                    val weekLabel = targetContext.getString(R.string.pac_interval, text(R.string.pac_week))
                    check(find(uiAutomation.rootInActiveWindow, weekLabel) != null)
                    fun timer(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                        node ?: return null
                        if (node.text?.toString()?.matches(Regex("[0-9]{1,2}")) == true) return node
                        for (i in 0 until node.childCount) timer(node.getChild(i))?.let { return it }
                        return null
                    }
                    fun dated(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                        node ?: return null
                        if (node.text?.toString() == text(R.string.pac_empty) || node.text?.toString()?.startsWith(targetContext.getString(R.string.pac_updated, "")) == true) return node
                        for (i in 0 until node.childCount) dated(node.getChild(i))?.let { return it }
                        return null
                    }
                    fun controlBounds(node: AccessibilityNodeInfo): android.graphics.Rect {
                        var control = node
                        while (!control.isClickable && control.parent != null) control = control.parent
                        return android.graphics.Rect().also(control::getBoundsInScreen)
                    }
                    phase = "pac_row"
                    val reload = if (type == 3) {
                        check(app.pac.manager(selected).cooldownSeconds() == 0)
                        check(timer(uiAutomation.rootInActiveWindow) == null)
                        result.putBoolean("pac_unlimited", true)
                        checkNotNull(find(uiAutomation.rootInActiveWindow, text(R.string.pac_refresh)))
                    } else {
                        if (app.pac.manager(selected).cooldownSeconds() > 0) checkNotNull(timer(uiAutomation.rootInActiveWindow))
                        else checkNotNull(find(uiAutomation.rootInActiveWindow, text(R.string.pac_refresh)))
                    }
                    val periodBounds = controlBounds(checkNotNull(find(uiAutomation.rootInActiveWindow, weekLabel)))
                    val dateBounds = android.graphics.Rect().also(checkNotNull(dated(uiAutomation.rootInActiveWindow))::getBoundsInScreen)
                    val refreshBounds = controlBounds(reload)
                    check(periodBounds.right <= dateBounds.left && dateBounds.right <= refreshBounds.left)
                    check(kotlin.math.abs(periodBounds.centerY() - refreshBounds.centerY()) <= 2)
                    check(kotlin.math.abs(periodBounds.centerY() - dateBounds.centerY()) <= 2)
                    check(refreshBounds.width() == refreshBounds.height())
                    val geometry = listOf(periodBounds, refreshBounds)
                    if (commonGeometry == null) commonGeometry = geometry else check(commonGeometry == geometry)
                    result.putBoolean("pac_compact_row", true)
                    fun focusedWithin(bounds: android.graphics.Rect) {
                        fun focused(node: AccessibilityNodeInfo?): Boolean {
                            node ?: return false
                            val area = android.graphics.Rect().also(node::getBoundsInScreen)
                            if (node.isFocused && bounds.contains(area.centerX(), area.centerY())) return true
                            return (0 until node.childCount).any { focused(node.getChild(it)) }
                        }
                        check(focused(uiAutomation.rootInActiveWindow))
                    }
                    phase = "pac_focus"
                    key(KeyEvent.KEYCODE_DPAD_RIGHT); focusedWithin(refreshBounds)
                    key(KeyEvent.KEYCODE_DPAD_DOWN)
                    focusedWithin(controlBounds(node(R.string.proxy_cancel)))
                    key(KeyEvent.KEYCODE_DPAD_UP); focusedWithin(refreshBounds)
                    key(KeyEvent.KEYCODE_DPAD_LEFT); focusedWithin(periodBounds)
                    capture(if (type == 3) "proxy-custom-pac" else "proxy-antizapret")
                    key(KeyEvent.KEYCODE_DPAD_DOWN)
                    focusedWithin(controlBounds(node(R.string.proxy_save)))
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    check(proxy.value == selected)
                    check(settings.savedManualProxy() == manual)
                } finally {
                    kotlinx.coroutines.runBlocking { app.pac.manager(selected).setInterval(originalInterval) }
                }
            }
            phase = "pac_preview"
            PacRowChecks.run(probe, activity, artifacts)
            result.putBoolean("pac_countdown", true)
            result.putBoolean("pac_same_layout", true)
            result.putBoolean("pac_modes", true)
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
