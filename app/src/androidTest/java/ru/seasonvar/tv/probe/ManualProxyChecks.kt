package ru.seasonvar.tv.probe

import android.view.KeyEvent
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.network.*

internal class ManualProxyChecks(private val ui: ProxySettingsProbeSession, private val fixture: ProxySettingsFixtures) {
    fun run() {
        for (protocol in ProxyProtocol.entries) {
            ui.render(fixture, protocol)
            open(protocol)
            val expected = editAndSave(protocol)
            cancel(expected)
            corrupt()
        }
    }

    private fun open(protocol: ProxyProtocol) = with(ui) {
        phase = "open"
        click(R.string.settings_title)
        focusSetting(R.string.image_prefetch_title)
        capture("settings-network")
        focusSetting(R.string.bypass_title)
        capture("settings-connection")
        focusSetting(R.string.proxy_title)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        capture("proxy-open")
        check(find(uiAutomation.rootInActiveWindow, text(R.string.proxy_host)) == null)
        check(find(uiAutomation.rootInActiveWindow, text(R.string.proxy_port)) == null)
        check(find(uiAutomation.rootInActiveWindow, text(R.string.proxy_username)) == null)
        check(find(uiAutomation.rootInActiveWindow, text(R.string.proxy_password)) == null)
        centered(R.string.proxy_save)
        centered(R.string.proxy_cancel)
        capture("proxy-${protocol.storageId}-off")
    }

    private fun editAndSave(protocol: ProxyProtocol): ProxyConfiguration.Server = with(ui) { with(fixture) {
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
        expected
    } }

    private fun cancel(expected: ProxyConfiguration.Server) = with(ui) { with(fixture) {
        phase = "cancel"
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        fill(R.string.proxy_username, "changed")
        click(R.string.proxy_cancel)
        check(proxy.value == expected && saves == 1)
        result.putBoolean("proxy_cancelled", true)
    } }

    private fun corrupt() = with(ui) { with(fixture) {
        phase = "corrupt"
        check(preferences.edit().putString("proxy_sealed", "corrupt").commit())
        check(NetworkSettings(targetContext, storeName).configuration.value.proxy == ProxyConfiguration.Unavailable)
        settings.setProxy(ProxyConfiguration.Disabled)
        check(NetworkSettings(targetContext, storeName).configuration.value.proxy == ProxyConfiguration.Disabled)
        result.putBoolean("proxy_fail_closed", true)
    } }
}
