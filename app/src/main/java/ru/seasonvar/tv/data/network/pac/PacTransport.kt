package ru.seasonvar.tv.data.network.pac

import okhttp3.CertificatePinner
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import ru.seasonvar.tv.data.network.NetworkConfiguration
import java.io.IOException
import java.net.*
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal fun OkHttpClient.Builder.pacTransport(provider: PacRouteProvider, configuration: NetworkConfiguration,
    dns: Dns, tls: SSLSocketFactory = requireNotNull(configuration.proxy.pacSource()).proxyTls(),
    pins: CertificatePinner = ru.seasonvar.tv.data.settings.TlsPinSettings.certificatePinner): OkHttpClient.Builder {
    val endpoints = PacEndpoints()
    val selector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = provider.routes(requireNotNull(uri.host), configuration).map { route ->
            if (route.type == PacRouteType.DIRECT) Proxy.NO_PROXY
            else Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(endpoints.alias(route), route.port))
        }
        override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) = Unit
    }
    val routes = Dns { host ->
        val endpoint = endpoints.get(host)
        if (endpoint == null) dns.lookup(host)
        else {
            val addresses = if (':' in endpoint.host || endpoint.host.all { it.isDigit() || it == '.' })
                listOf(InetAddress.getByName(endpoint.host)) else dns.lookup(endpoint.host)
            addresses.map { InetAddress.getByAddress(host, it.address) }
        }
    }
    // Синтетические имена разделяют HTTP- и HTTPS-прокси даже при одинаковых IP и портах.
    return proxy(null).proxySelector(selector).proxyAuthenticator(okhttp3.Authenticator.NONE).dns(routes).socketFactory(object : SocketFactory() {
        override fun createSocket(): Socket = PacSocket(endpoints, tls, pins)
        private fun connected(host: String, port: Int, local: InetAddress? = null, localPort: Int = 0): Socket = createSocket().apply {
            try {
                if (local != null) bind(InetSocketAddress(local, localPort))
                connect(InetSocketAddress(routes.lookup(host).first(), port), 8000)
            } catch (error: IOException) { close(); throw error }
        }
        override fun createSocket(host: String, port: Int) = connected(host, port)
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int) = connected(host, port, local, localPort)
        override fun createSocket(host: InetAddress, port: Int) = connected(host.hostName, port)
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int) = connected(host.hostName, port, local, localPort)
    }).protocols(listOf(Protocol.HTTP_1_1))
}

private class PacEndpoints {
    private val values = mutableMapOf<String, PacRoute>()
    @Synchronized fun alias(route: PacRoute): String {
        values.entries.firstOrNull { it.value == route }?.let { return it.key }
        if (values.size >= ru.seasonvar.tv.data.settings.PacSettings.MAX_PROXY_ENDPOINTS) throw IOException("PAC endpoint limit")
        val name = "pac-${values.size}.invalid"
        values[name] = route
        return name
    }
    @Synchronized fun get(host: String): PacRoute? = values[host]
}

private class PacSocket(private val endpoints: PacEndpoints, private val tls: SSLSocketFactory,
    private val pins: CertificatePinner) : Socket(Proxy.NO_PROXY) {
    private val raw = Socket(Proxy.NO_PROXY)
    @Volatile private var secured: SSLSocket? = null
    private val wire: Socket get() = secured ?: raw

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val target = endpoint as? InetSocketAddress ?: throw SocketException("Invalid PAC endpoint")
        val route = endpoints.get(target.hostString)
        try {
            raw.connect(target, timeout)
            if (route?.type == PacRouteType.HTTPS) {
                val previous = raw.soTimeout
                raw.soTimeout = if (timeout > 0) timeout else 8000
                val socket = tls.createSocket(raw, route.host, route.port, true) as SSLSocket
                secured = socket
                socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                socket.startHandshake()
                if (!proxyHostnameVerifier.verify(route.host, socket.session))
                    throw javax.net.ssl.SSLPeerUnverifiedException("HTTPS proxy hostname mismatch")
                pins.check(route.host, socket.session.peerCertificates.toList())
                socket.soTimeout = previous
            }
        } catch (error: IOException) { close(); throw error }
    }
    override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)
    override fun bind(address: SocketAddress?) = raw.bind(address)
    override fun getInputStream() = wire.getInputStream()
    override fun getOutputStream() = wire.getOutputStream()
    override fun close() { try { secured?.close() } finally { raw.close() } }
    override fun getInetAddress(): InetAddress? = raw.inetAddress
    override fun getLocalAddress(): InetAddress = raw.localAddress
    override fun getPort() = raw.port
    override fun getLocalPort() = raw.localPort
    override fun getRemoteSocketAddress(): SocketAddress? = raw.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = raw.localSocketAddress
    override fun isConnected() = raw.isConnected
    override fun isClosed() = raw.isClosed
    override fun isBound() = raw.isBound
    override fun isInputShutdown() = wire.isInputShutdown
    override fun isOutputShutdown() = wire.isOutputShutdown
    override fun shutdownInput() = wire.shutdownInput()
    override fun shutdownOutput() = wire.shutdownOutput()
    override fun setSoTimeout(value: Int) { wire.soTimeout = value }
    override fun getSoTimeout() = wire.soTimeout
    override fun setTcpNoDelay(value: Boolean) { raw.tcpNoDelay = value }
    override fun getTcpNoDelay() = raw.tcpNoDelay
    override fun setKeepAlive(value: Boolean) { raw.keepAlive = value }
    override fun getKeepAlive() = raw.keepAlive
    override fun setReuseAddress(value: Boolean) { raw.reuseAddress = value }
    override fun getReuseAddress() = raw.reuseAddress
    override fun setReceiveBufferSize(value: Int) { raw.receiveBufferSize = value }
    override fun getReceiveBufferSize() = raw.receiveBufferSize
    override fun setSendBufferSize(value: Int) { raw.sendBufferSize = value }
    override fun getSendBufferSize() = raw.sendBufferSize
    override fun setSoLinger(enabled: Boolean, value: Int) = raw.setSoLinger(enabled, value)
    override fun getSoLinger() = raw.soLinger
    override fun sendUrgentData(value: Int) = raw.sendUrgentData(value)
}

private val proxyHostnameVerifier by lazy { OkHttpClient().hostnameVerifier }
