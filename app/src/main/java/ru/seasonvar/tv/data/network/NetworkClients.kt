package ru.seasonvar.tv.data.network

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.dnsoverhttps.DnsOverHttps
import ru.seasonvar.tv.data.settings.TlsPinSettings
import java.net.InetAddress
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal class NetworkClients {
    private val directClient = OkHttpClient.Builder()
        .certificatePinner(TlsPinSettings.certificatePinner)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val bypassClient by lazy {
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(null as KeyStore?)
        }
        val trustManager = managers.trustManagers.filterIsInstance<X509TrustManager>().single()
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        directClient.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .sslSocketFactory(DpiSocketFactory(context), trustManager)
            .build()
    }

    private val quad9 by lazy { dnsOverHttps("https://dns.quad9.net/dns-query", "9.9.9.9") }
    private val cloudflare by lazy { dnsOverHttps("https://cloudflare-dns.com/dns-query", "1.1.1.1") }

    private fun dnsOverHttps(url: String, bootstrapAddress: String): DnsOverHttps =
        DnsOverHttps.Builder()
            .client(OkHttpClient.Builder().certificatePinner(TlsPinSettings.certificatePinner)
                .callTimeout(8, TimeUnit.SECONDS).build())
            .url(url.toHttpUrl())
            .bootstrapDnsHosts(InetAddress.getByName(bootstrapAddress))
            .includeIPv6(false)
            .build()

    private val clients = mutableMapOf<NetworkConfiguration, OkHttpClient>()

    private val mediaClients = mutableMapOf<NetworkConfiguration, OkHttpClient>()

    @Synchronized
    fun forMedia(configuration: NetworkConfiguration): OkHttpClient = mediaClients.getOrPut(configuration) {
        forConfiguration(configuration).newBuilder()
            .followRedirects(true)
            .followSslRedirects(false)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    @Synchronized
    fun forConfiguration(configuration: NetworkConfiguration): OkHttpClient =
        clients.getOrPut(configuration) {
            val transport = if (configuration.bypassEnabled) bypassClient else directClient
            val dns = when (configuration.dnsProvider) {
                DnsProvider.SYSTEM -> Dns.SYSTEM
                DnsProvider.QUAD9_DOH -> quad9
                DnsProvider.CLOUDFLARE_DOH -> cloudflare
            }
            transport.newBuilder().dns(dns).build()
        }
}
