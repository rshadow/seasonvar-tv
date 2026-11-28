package ru.seasonvar.tv.data.network.pac

import android.content.Context
import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrl
import ru.seasonvar.tv.data.network.ProxyConfiguration
import ru.seasonvar.tv.data.settings.PacSettings
import ru.seasonvar.tv.data.settings.TlsPinSettings
import java.security.MessageDigest
import javax.net.ssl.SSLSocketFactory

internal data class PacSource(
    val url: String,
    val variant: String = "custom",
    val userAgent: String = PacSettings.USER_AGENT,
    val downloadPins: CertificatePinner = TlsPinSettings.certificatePinner,
    val proxyTls: () -> SSLSocketFactory = { SSLSocketFactory.getDefault() as SSLSocketFactory },
    val refreshCooldownMillis: Long = 0,
) {
    val key get() = "$variant:$url"
}

internal fun ProxyConfiguration.pacSource(): PacSource? = when (this) {
    ProxyConfiguration.AntiZapret -> AntiZapret.source
    is ProxyConfiguration.Pac -> PacSource(url.toHttpUrl().toString())
    else -> null
}

fun ProxyConfiguration.pacUrl(): String? = pacSource()?.url

internal fun pacStorage(context: Context, proxy: ProxyConfiguration): PreferencesPacStorage {
    val source = requireNotNull(proxy.pacSource())
    fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return PreferencesPacStorage(context, hash(source.key))
}
