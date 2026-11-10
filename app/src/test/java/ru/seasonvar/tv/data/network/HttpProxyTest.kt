package ru.seasonvar.tv.data.network

import okhttp3.CertificatePinner
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLPeerUnverifiedException

class HttpProxyTest {
    @Test fun connect_tunnels_tls_with_remote_dns_and_optional_basic_auth_without_origin_header_leaks() {
        for (bypass in listOf(false, true)) for (authenticated in listOf(false, true)) {
            tunnel(authenticated, bypass) { client, url ->
                client.newCall(Request.Builder().url(url).build()).execute().use {
                    assertEquals(200, it.code)
                    assertEquals("ok", it.body.string())
                }
            }
        }
    }

    @Test fun trusted_tls_with_incorrect_pin_is_rejected_through_the_tunnel() {
        tunnel(false, false, expectTlsFailure = true) { client, url ->
            val wrong = HeldCertificate.Builder().commonName("other").build()
            val rejected = client.newBuilder().certificatePinner(CertificatePinner.Builder()
                .add("destination.invalid", CertificatePinner.pin(wrong.certificate)).build()).build()
            assertThrows(SSLPeerUnverifiedException::class.java) {
                rejected.newCall(Request.Builder().url(url).build()).execute().close()
            }
        }
    }

    @Test fun rejected_credentials_stop_after_one_retry_without_a_direct_fallback() {
        ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { listener ->
            listener.soTimeout = 4000
            val executor = Executors.newSingleThreadExecutor()
            val server = executor.submit {
                repeat(2) { index -> listener.accept().use { socket ->
                    socket.soTimeout = 4000
                    val headers = headers(socket)
                    assertEquals(index == 1, headers.any { it.startsWith("Proxy-Authorization:", true) })
                    socket.getOutputStream().write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"test\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                } }
            }
            try {
                val config = NetworkConfiguration(false, DnsProvider.SYSTEM,
                    ProxyConfiguration.Server(canonicalHost(listener.inetAddress.hostAddress!!), listener.localPort, "user", "incorrect"))
                val client = NetworkClients().forConfiguration(config).newBuilder().retryOnConnectionFailure(false).build()
                assertThrows(IOException::class.java) {
                    client.newCall(Request.Builder().url("https://destination.invalid/").build()).execute().close()
                }
                server.get(6, TimeUnit.SECONDS)
                client.connectionPool.evictAll()
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun unreadable_proxy_settings_fail_before_dns_or_connection() {
        val client = NetworkClients().forConfiguration(NetworkConfiguration(proxy = ProxyConfiguration.Unavailable))
            .newBuilder().dns(Dns { error("DNS must not run") }).build()
        assertThrows(IOException::class.java) {
            client.newCall(Request.Builder().url("https://destination.invalid/").build()).execute().close()
        }
    }

    @Test fun credentials_are_redacted_and_previous_proxy_clients_are_evicted() {
        val proxy = ProxyConfiguration.Server("proxy.invalid", 8080, "unique-user", "unique-password")
        assertFalse(proxy.toString().contains("unique-"))
        assertFalse(NetworkConfiguration(proxy = proxy).toString().contains("unique-"))
        val clients = NetworkClients()
        val config = NetworkConfiguration(false, DnsProvider.SYSTEM, proxy)
        val first = clients.forConfiguration(config)
        val media = clients.forMedia(config)
        assertEquals(first.proxy, media.proxy)
        assertEquals(first.proxyAuthenticator, media.proxyAuthenticator)
        clients.forConfiguration(config.copy(proxy = ProxyConfiguration.Disabled))
        assertNotSame(first, clients.forConfiguration(config))
    }

    private fun headers(socket: Socket): List<String> {
        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        return buildList { while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; add(line) } }
    }

    private fun tunnel(authenticated: Boolean, bypass: Boolean, expectTlsFailure: Boolean = false,
        block: (okhttp3.OkHttpClient, String) -> Unit) {
        val certificate = HeldCertificate.Builder().commonName("destination.invalid").addSubjectAlternativeName("destination.invalid").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).addTrustedCertificate(certificate.certificate).build()
        ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { listener ->
            listener.soTimeout = 4000
            val executor = Executors.newSingleThreadExecutor()
            val server = executor.submit {
                if (authenticated) listener.accept().use { socket ->
                    socket.soTimeout = 4000
                    assertFalse(headers(socket).any { it.startsWith("Proxy-Authorization:", true) })
                    socket.getOutputStream().write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"test\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
                listener.accept().use { socket ->
                    socket.soTimeout = 4000
                    val request = headers(socket)
                    assertEquals("CONNECT destination.invalid:443 HTTP/1.1", request.first())
                    if (authenticated) assertTrue(request.contains("Proxy-Authorization: " + Credentials.basic("test-user", "test-password", Charsets.UTF_8)))
                    else assertFalse(request.any { it.startsWith("Proxy-Authorization:", true) })
                    socket.getOutputStream().apply { write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()); flush() }
                    (tls.sslSocketFactory().createSocket(socket, "destination.invalid", 443, true) as SSLSocket).use { secure ->
                        secure.useClientMode = false
                        secure.startHandshake()
                        if (!expectTlsFailure) {
                            val origin = headers(secure)
                            assertEquals("GET / HTTP/1.1", origin.first())
                            assertFalse(origin.any { it.startsWith("Proxy-Authorization:", true) })
                            secure.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                        }
                    }
                }
            }
            val proxy = ProxyConfiguration.Server(canonicalHost(listener.inetAddress.hostAddress!!), listener.localPort,
                if (authenticated) "test-user" else "", if (authenticated) "test-password" else "")
            val client = NetworkClients().forConfiguration(NetworkConfiguration(bypass, DnsProvider.SYSTEM, proxy)).newBuilder()
                .sslSocketFactory(if (bypass) DpiSocketFactory(tls.sslContext()) else tls.sslSocketFactory(), tls.trustManager)
                .certificatePinner(CertificatePinner.Builder().add("destination.invalid", CertificatePinner.pin(certificate.certificate)).build())
                .retryOnConnectionFailure(false).build()
            try { block(client, "https://destination.invalid/"); server.get(6, TimeUnit.SECONDS) }
            finally { client.connectionPool.evictAll(); executor.shutdownNow() }
        }
    }
}
