package ru.seasonvar.tv.data.network

import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.dnsoverhttps.DnsOverHttps
import ru.seasonvar.tv.data.settings.TlsPinSettings
import java.net.InetAddress
import java.net.Proxy
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal class NetworkClients(
    private val connectTimeoutMillis: Long = 8_000,
    private val requestTimeoutMillis: Long = 35_000,
) : AutoCloseable {
    private val directClient = OkHttpClient.Builder()
        .certificatePinner(TlsPinSettings.certificatePinner)
        .connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(minOf(30_000, requestTimeoutMillis), TimeUnit.MILLISECONDS)
        .callTimeout(requestTimeoutMillis, TimeUnit.MILLISECONDS)
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
    private val dnsClients = mutableListOf<OkHttpClient>()

    private fun dnsOverHttps(url: String, bootstrapAddress: String): DnsOverHttps {
        val client = OkHttpClient.Builder().certificatePinner(TlsPinSettings.certificatePinner)
            .connectTimeout(minOf(8_000, connectTimeoutMillis), TimeUnit.MILLISECONDS)
            .callTimeout(minOf(8_000, requestTimeoutMillis), TimeUnit.MILLISECONDS).build()
        dnsClients += client
        return DnsOverHttps.Builder().client(client)
            .url(url.toHttpUrl())
            .bootstrapDnsHosts(InetAddress.getByName(bootstrapAddress))
            .includeIPv6(false)
            .build()
    }

    @Synchronized
    override fun close() {
        (dnsClients + directClient).forEach {
            it.dispatcher.cancelAll()
            it.connectionPool.evictAll()
            it.dispatcher.executorService.shutdown()
        }
    }

    private val clients = mutableMapOf<NetworkConfiguration, OkHttpClient>()

    private val mediaClients = mutableMapOf<NetworkConfiguration, OkHttpClient>()

    private var activeProxy: ProxyConfiguration = ProxyConfiguration.Disabled

    private fun selectProxy(proxy: ProxyConfiguration) {
        if (activeProxy == proxy) return
        (clients.values + mediaClients.values).forEach { it.connectionPool.evictAll() }
        clients.clear()
        mediaClients.clear()
        activeProxy = proxy
    }

    @Synchronized
    fun forMedia(configuration: NetworkConfiguration): OkHttpClient {
        selectProxy(configuration.proxy)
        return mediaClients.getOrPut(configuration) {
            forConfiguration(configuration).newBuilder()
                .followRedirects(true)
                .followSslRedirects(false)
                .callTimeout(0, TimeUnit.SECONDS)
                .build()
        }
    }

    @Synchronized
    fun forConfiguration(configuration: NetworkConfiguration): OkHttpClient {
        selectProxy(configuration.proxy)
        return clients.getOrPut(configuration) {
            val transport = if (configuration.bypassEnabled) bypassClient else directClient
            val dns = when (configuration.dnsProvider) {
                DnsProvider.SYSTEM -> Dns.SYSTEM
                DnsProvider.QUAD9_DOH -> quad9
                DnsProvider.CLOUDFLARE_DOH -> cloudflare
            }
            val builder = transport.newBuilder().dns(dns).proxy(Proxy.NO_PROXY)
            when (val proxy = configuration.proxy) {
                ProxyConfiguration.Disabled -> Unit
                ProxyConfiguration.Unavailable -> builder.addInterceptor { throw IOException("Proxy settings unavailable") }
                is ProxyConfiguration.Server -> if (proxy.protocol == ProxyProtocol.SOCKS5) {
                    val routes = socks5Routes(proxy, dns)
                    // Адреса маршрутов принадлежат прокси, поэтому объединять HTTP/2-соединения по IP нельзя.
                    builder.dns(routes).socketFactory(Socks5SocketFactory(proxy, routes)).protocols(listOf(Protocol.HTTP_1_1))
                } else {
                    val endpoint = Proxy(Proxy.Type.HTTP, proxy.address())
                    builder.proxy(endpoint)
                    if (proxy.authenticated) builder.proxyAuthenticator { route, response ->
                        if (route?.proxy != endpoint ||
                            response.request.header("Proxy-Authorization") != null ||
                            response.challenges().none { it.scheme.equals("Basic", ignoreCase = true) }) null
                        else response.request.newBuilder().header("Proxy-Authorization",
                            Credentials.basic(proxy.username, proxy.password, Charsets.UTF_8)).build()
                    }
                }
            }
            builder.build()
        }
    }
}
