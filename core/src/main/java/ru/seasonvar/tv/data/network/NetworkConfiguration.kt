package ru.seasonvar.tv.data.network

enum class DnsProvider(val storageId: String) {
    SYSTEM("system"),
    QUAD9_DOH("quad9_doh"),
    CLOUDFLARE_DOH("cloudflare_doh");

    companion object {
        fun fromStorage(value: String?): DnsProvider =
            entries.firstOrNull { it.storageId == value } ?: QUAD9_DOH
    }
}

data class NetworkConfiguration(
    val bypassEnabled: Boolean = true,
    val dnsProvider: DnsProvider = DnsProvider.QUAD9_DOH,
    val proxy: ProxyConfiguration = ProxyConfiguration.Disabled,
)
