package ru.seasonvar.tv.data.network

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class NetworkSettings(context: Context, storeName: String = "network") {
    private val preferences = context.getSharedPreferences(storeName, Context.MODE_PRIVATE)
    private val cipher = ru.seasonvar.tv.data.security.KeystoreCipher("seasonvar-proxy-v1")
    private val mutableConfiguration = MutableStateFlow(
        NetworkConfiguration(
            proxy = readProxy(),
            bypassEnabled = preferences.getBoolean("dpi_bypass", true),
            dnsProvider = DnsProvider.fromStorage(preferences.getString("dns_provider", null)),
        )
    )
    val configuration = mutableConfiguration.asStateFlow()

    private fun readProxy(): ProxyConfiguration = try {
        preferences.getString("proxy_sealed", null)?.let { ProxyCodec.decode(cipher.open(it)) } ?: ProxyConfiguration.Disabled
    } catch (_: Exception) { ProxyConfiguration.Unavailable }

    fun setProxy(proxy: ProxyConfiguration) {
        require(proxy != ProxyConfiguration.Unavailable)
        val edit = preferences.edit()
        if (proxy is ProxyConfiguration.Server) edit.putString("proxy_sealed", cipher.seal(ProxyCodec.encode(proxy)))
        else edit.remove("proxy_sealed")
        check(edit.commit()) { "Proxy settings unavailable" }
        mutableConfiguration.update { it.copy(proxy = proxy) }
    }

    fun setBypassEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("dpi_bypass", enabled).apply()
        mutableConfiguration.update { it.copy(bypassEnabled = enabled) }
    }

    fun setDnsProvider(provider: DnsProvider) {
        preferences.edit().putString("dns_provider", provider.storageId).apply()
        mutableConfiguration.update { it.copy(dnsProvider = provider) }
    }
}
