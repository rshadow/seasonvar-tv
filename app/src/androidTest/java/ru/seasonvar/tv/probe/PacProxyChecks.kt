package ru.seasonvar.tv.probe

import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.network.*
import ru.seasonvar.tv.data.network.pac.pacStorage
import ru.seasonvar.tv.data.settings.PacRefreshInterval

/** Интервал каждого PAC-источника восстанавливается до перехода к следующему режиму. */
internal class PacProxyChecks(private val ui: ProxySettingsProbeSession, private val fixture: ProxySettingsFixtures) {
    private data class Controls(val periodBounds: Rect, val refreshBounds: Rect)

    fun run() = with(ui) { with(fixture) {
        phase = "pac_modes"
        val manual = ProxyConfiguration.Server("proxy.example.test", 8080, "fixture", "fixture")
        settings.setProxy(manual)
        var commonGeometry: Controls? = null
        for (type in listOf(3, 4)) {
            val url = "https://pac-fixture.invalid/run-${SystemClock.uptimeMillis()}.pac"
            val selected = if (type == 3) ProxyConfiguration.Pac(url) else ProxyConfiguration.AntiZapret
            val storage = pacStorage(targetContext, selected)
            val originalInterval = storage.interval
            kotlinx.coroutines.runBlocking { app.pac.manager(selected).setInterval(PacRefreshInterval.DAY) }
            try {
                render(fixture, type)
                val weekLabel = openMode(type, url)
                val geometry = row(type, selected, weekLabel)
                if (commonGeometry == null) commonGeometry = geometry else check(commonGeometry == geometry)
                result.putBoolean("pac_compact_row", true)
                focusAndSave(type, selected, manual, geometry)
            } finally {
                kotlinx.coroutines.runBlocking { app.pac.manager(selected).setInterval(originalInterval) }
            }
        }
    } }

    private fun openMode(type: Int, url: String): String = with(ui) {
        click(R.string.settings_title)
        focusSetting(R.string.proxy_title)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        repeat(type) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        check(find(uiAutomation.rootInActiveWindow, text(R.string.proxy_host)) == null)
        check(find(uiAutomation.rootInActiveWindow, text(R.string.pac_url)) != null)
        if (type == 3) fill(R.string.pac_url, url)
        else check(find(uiAutomation.rootInActiveWindow, ru.seasonvar.tv.data.settings.PacSettings.ANTIZAPRET_URL) != null)
        centered(R.string.proxy_save)
        centered(R.string.proxy_cancel)
        val intervalLabel = targetContext.getString(R.string.pac_interval, text(R.string.pac_day))
        var interval = checkNotNull(find(uiAutomation.rootInActiveWindow, intervalLabel))
        while (!interval.isClickable && interval.parent != null) interval = interval.parent
        check(interval.performAction(AccessibilityNodeInfo.ACTION_CLICK)); settle()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        settle()
        val weekLabel = targetContext.getString(R.string.pac_interval, text(R.string.pac_week))
        check(find(uiAutomation.rootInActiveWindow, weekLabel) != null)
        weekLabel
    }

    private fun row(type: Int, selected: ProxyConfiguration, weekLabel: String): Controls = with(ui) {
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
        Controls(periodBounds, refreshBounds)
    }

    private fun focusAndSave(type: Int, selected: ProxyConfiguration, manual: ProxyConfiguration.Server, controls: Controls) = with(ui) { with(fixture) {
        val (periodBounds, refreshBounds) = controls
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
    } }

    private fun timer(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.toString()?.matches(Regex("[0-9]{1,2}")) == true) return node
        for (i in 0 until node.childCount) timer(node.getChild(i))?.let { return it }
        return null
    }
    private fun dated(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.toString() == ui.text(R.string.pac_empty) || node.text?.toString()?.startsWith(ui.targetContext.getString(R.string.pac_updated, "")) == true) return node
        for (i in 0 until node.childCount) dated(node.getChild(i))?.let { return it }
        return null
    }
    private fun controlBounds(node: AccessibilityNodeInfo): android.graphics.Rect {
        var control = node
        while (!control.isClickable && control.parent != null) control = control.parent
        return android.graphics.Rect().also(control::getBoundsInScreen)
    }

    private fun focusedWithin(bounds: android.graphics.Rect) {
        fun focused(node: AccessibilityNodeInfo?): Boolean {
            node ?: return false
            val area = android.graphics.Rect().also(node::getBoundsInScreen)
            if (node.isFocused && bounds.contains(area.centerX(), area.centerY())) return true
            return (0 until node.childCount).any { focused(node.getChild(it)) }
        }
        check(focused(ui.uiAutomation.rootInActiveWindow))
    }
}
