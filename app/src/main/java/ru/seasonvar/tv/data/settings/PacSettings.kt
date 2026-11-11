package ru.seasonvar.tv.data.settings

object PacSettings {
    const val ANTIZAPRET_URL = "https://p.thenewone.lol:8443/proxy.pac"
    // Сервис выбирает PAC с HTTPS-прокси по совместимому браузерному User-Agent.
    const val ANTIZAPRET_USER_AGENT = "Mozilla/5.0 (Android TV; compatible; rv:128.0) Gecko/20100101 Firefox/128.0 Seasonvar-TV/PAC"
    const val USER_AGENT = "Seasonvar-TV/PAC (Android TV)"
    const val MAX_BYTES = 2 * 1024 * 1024
    const val FETCH_TIMEOUT_MILLIS = 15_000L
    const val EVALUATION_TIMEOUT_MILLIS = 15_000L
    const val REFRESH_COOLDOWN_MILLIS = 60_000L
    const val ROUTE_TTL_MILLIS = 5 * 60_000L
    const val MAX_ROUTE_RECORDS = 256
    const val MAX_BATCH_HOSTS = 32
    const val SOURCE_MEMORY_CAPACITY = 8
    const val MAX_PROXY_ENDPOINTS = 256
    val knownHosts = listOf("seasonvar.ru", "www.seasonvar.ru", "cdn.svstat.net", "svstat.net",
        "github.com", "api.github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com",
        "raw.githubusercontent.com")
}

enum class PacRefreshInterval(val hours: Int) {
    HOUR(1), SIX_HOURS(6), TWELVE_HOURS(12), DAY(24), WEEK(168);
    val millis: Long get() = hours * 60 * 60_000L
    companion object {
        fun fromHours(hours: Int) = entries.firstOrNull { it.hours == hours } ?: DAY
    }
}
