package ru.seasonvar.tv.data.network.pac

import okhttp3.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.network.*
import java.io.IOException
import java.net.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

class PacTransportTest {
    @Test fun https_proxy_and_destination_have_independent_verified_tls_layers() {
        for (bypass in listOf(false, true)) tunnel(bypass, false)
    }

    @Test fun untrusted_proxy_certificate_is_rejected_before_connect_or_origin_headers() { tunnel(false, true) }

    @Test fun missing_intermediate_is_completed_without_adding_a_trust_anchor() { tunnel(false, false, incompleteChain = true) }

    @Test fun completing_a_chain_does_not_accept_a_certificate_for_another_proxy() { tunnel(false, true, incompleteChain = true, wrongHost = true) }

    @Test fun completing_a_chain_does_not_trust_an_unknown_root() { tunnel(false, true, incompleteChain = true, untrustedRoot = true) }

    @Test fun ordered_http_failure_falls_back_to_direct_without_resolving_synthetic_names() {
        ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { broken ->
            ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { origin ->
                broken.soTimeout = 4000; origin.soTimeout = 4000
                val pool = Executors.newFixedThreadPool(2)
                val failed = pool.submit { broken.accept().use { } }
                val successful = pool.submit { origin.accept().use { socket ->
                    socket.soTimeout = 4000
                    headers(socket)
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                } }
                val provider = provider(listOf(PacRoute(PacRouteType.HTTP, "proxy.invalid", broken.localPort), PacRoute(PacRouteType.DIRECT)))
                val client = OkHttpClient.Builder().pacTransport(provider, config(), Dns { host ->
                    assertTrue(host in setOf("proxy.invalid", "origin.invalid")); listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
                }).connectTimeout(2, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
                try {
                    client.newCall(Request.Builder().url("http://origin.invalid:${origin.localPort}/").build()).execute().use {
                        assertEquals("ok", it.body.string())
                    }
                    failed.get(5, TimeUnit.SECONDS); successful.get(5, TimeUnit.SECONDS)
                } finally { client.connectionPool.evictAll(); pool.shutdownNow() }
            }
        }
    }

    private fun tunnel(bypass: Boolean, rejectProxy: Boolean, incompleteChain: Boolean = false,
        wrongHost: Boolean = false, untrustedRoot: Boolean = false) {
        val root = HeldCertificate.Builder().commonName("Fixture Root").certificateAuthority(2).build()
        val intermediate = HeldCertificate.Builder().commonName("Fixture Intermediate").certificateAuthority(1).signedBy(root).build()
        val proxyCert = HeldCertificate.Builder().commonName("proxy.invalid")
            .addSubjectAlternativeName(if (wrongHost) "other.invalid" else "127.0.0.1")
            .apply { if (incompleteChain) signedBy(intermediate) }.build()
        val originCert = HeldCertificate.Builder().commonName("origin.invalid").addSubjectAlternativeName("origin.invalid").build()
        val proxyTls = HandshakeCertificates.Builder().heldCertificate(proxyCert).addTrustedCertificate(proxyCert.certificate).build()
        val originTls = HandshakeCertificates.Builder().heldCertificate(originCert).addTrustedCertificate(originCert.certificate).build()
        ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { listener ->
            listener.soTimeout = 4000
            val pool = Executors.newSingleThreadExecutor()
            val response = pool.submit {
                listener.accept().use { raw ->
                    raw.soTimeout = 4000
                    (proxyTls.sslSocketFactory().createSocket(raw, "proxy.invalid", listener.localPort, true) as SSLSocket).use { outer ->
                        outer.useClientMode = false
                        try {
                            outer.startHandshake()
                            if (!rejectProxy) {
                                val connect = headers(outer)
                                assertEquals("CONNECT origin.invalid:443 HTTP/1.1", connect.first())
                                assertFalse(connect.any { it.startsWith("Cookie:", true) || it.startsWith("Proxy-Authorization:", true) })
                                outer.getOutputStream().apply { write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()); flush() }
                                (originTls.sslSocketFactory().createSocket(outer, "origin.invalid", 443, true) as SSLSocket).use { inner ->
                                    inner.useClientMode = false
                                    inner.startHandshake()
                                    val request = headers(inner)
                                    assertEquals("GET /episode HTTP/1.1", request.first())
                                    assertTrue(request.contains("Cookie: fixture=origin-only"))
                                    assertFalse(request.any { it.startsWith("Proxy-Authorization:", true) })
                                    inner.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                                }
                            }
                        } catch (error: IOException) { if (!rejectProxy) throw error }
                    }
                }
            }
            val trustedProxy = if (incompleteChain) completingProxyTlsFactory(
                HandshakeCertificates.Builder().addTrustedCertificate(if (untrustedRoot)
                    HeldCertificate.Builder().commonName("Unrelated Root").certificateAuthority(2).build().certificate
                    else root.certificate).build().trustManager,
                intermediate.certificate) else if (rejectProxy) originTls.sslSocketFactory() else proxyTls.sslSocketFactory()
            val client = OkHttpClient.Builder().sslSocketFactory(
                if (bypass) DpiSocketFactory(originTls.sslContext()) else originTls.sslSocketFactory(), originTls.trustManager)
                .pacTransport(provider(listOf(PacRoute(PacRouteType.HTTPS, "127.0.0.1", listener.localPort))), config(),
                    Dns { error("Numeric proxy and tunneled origin must not use local DNS") }, trustedProxy, CertificatePinner.DEFAULT)
                .connectTimeout(2, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
            try {
                val request = Request.Builder().url("https://origin.invalid/episode").header("Cookie", "fixture=origin-only").build()
                if (rejectProxy) assertThrows(IOException::class.java) { client.newCall(request).execute().close() }
                else client.newCall(request).execute().use { assertEquals("ok", it.body.string()) }
                response.get(5, TimeUnit.SECONDS)
            } finally { client.connectionPool.evictAll(); pool.shutdownNow() }
        }
    }

    private fun headers(socket: Socket): List<String> = buildList {
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break; add(line) }
    }
    private fun config() = NetworkConfiguration(false, DnsProvider.SYSTEM, ProxyConfiguration.AntiZapret)
    private fun provider(routes: List<PacRoute>) = object : PacRouteProvider {
        override val revision = 0L
        override fun routes(host: String, configuration: NetworkConfiguration) = routes
        override suspend fun prepare(configuration: NetworkConfiguration) = Unit
    }
}
