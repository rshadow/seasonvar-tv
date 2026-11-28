package ru.seasonvar.tv.data.network.pac

import okhttp3.CertificatePinner
import ru.seasonvar.tv.data.settings.TlsPinSettings
import ru.seasonvar.tv.data.settings.PacSettings
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.*

internal object AntiZapret {
    val source = PacSource(PacSettings.ANTIZAPRET_URL, "antizapret", PacSettings.ANTIZAPRET_USER_AGENT,
        TlsPinSettings.antiZapretCertificatePinner, { socketFactory }, PacSettings.REFRESH_COOLDOWN_MILLIS)
    private val socketFactory: SSLSocketFactory by lazy {
        val platform = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(null as KeyStore?)
        }.trustManagers.filterIsInstance<X509TrustManager>().single()
        val intermediate = checkNotNull(javaClass.getResourceAsStream("/certificates/sectigo-r46-usertrust.pem")).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        check(CertificatePinner.pin(intermediate) == TlsPinSettings.SECTIGO_ROOT_R46)
        completingProxyTlsFactory(platform, intermediate)
    }
}

internal fun completingProxyTlsFactory(platform: X509TrustManager, intermediate: X509Certificate): SSLSocketFactory {
    val trust = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = platform.checkClientTrusted(chain, authType)
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try { platform.checkServerTrusted(chain, authType) }
            catch (original: CertificateException) {
                // Перекрёстный сертификат дополняет цепочку; доверенные корни остаются системными.
                try { platform.checkServerTrusted(chain + intermediate, authType) }
                catch (error: CertificateException) { error.addSuppressed(original); throw error }
            }
        }
    }
    return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }.socketFactory
}
