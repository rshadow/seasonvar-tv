package ru.seasonvar.tv.data.network

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class NetworkSettings(context: Context) {
    private val preferences = context.getSharedPreferences("network", Context.MODE_PRIVATE)
    private val mutableConfiguration = MutableStateFlow(
        NetworkConfiguration(
            bypassEnabled = preferences.getBoolean("dpi_bypass", true),
            dnsProvider = DnsProvider.fromStorage(preferences.getString("dns_provider", null)),
        )
    )
    val configuration = mutableConfiguration.asStateFlow()

    fun setBypassEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("dpi_bypass", enabled).apply()
        mutableConfiguration.update { it.copy(bypassEnabled = enabled) }
    }

    fun setDnsProvider(provider: DnsProvider) {
        preferences.edit().putString("dns_provider", provider.storageId).apply()
        mutableConfiguration.update { it.copy(dnsProvider = provider) }
    }
}
