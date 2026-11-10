package ru.seasonvar.tv.data.network

import okhttp3.CertificatePinner
import okhttp3.Dns
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.Authenticator
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLPeerUnverifiedException

class Socks5ProxyTest {
    @Test fun tls_uses_remote_dns_and_scoped_optional_credentials_with_both_transports() {
        val getter = Authenticator::class.java.getMethod("getDefault")
        val original = getter.invoke(null)
        for (auth in listOf(false, true)) for (bypass in listOf(false, true)) {
            exchange(auth, bypass = bypass)
        }
        assertSame(original, getter.invoke(null))
    }

    @Test fun incorrect_pins_still_reject_trusted_tls_through_socks() {
        exchange(false, wrongPin = true)
    }

    @Test fun configured_authentication_cannot_silently_downgrade_or_retry_a_bad_password() {
        for (reply in listOf(byteArrayOf(5, 0), byteArrayOf(5, -1), byteArrayOf(4, 2))) {
            rejected { socket, input ->
                assertArrayEquals(byteArrayOf(5, 1, 2), ByteArray(3).also(input::readFully))
                socket.getOutputStream().apply { write(reply); flush() }
                closed(input)
            }
        }
        rejected { socket, input ->
            greeting(socket, input, true)
            socket.getOutputStream().apply { write(byteArrayOf(1, 1)); flush() }
            closed(input)
        }
    }

    @Test fun malformed_or_refused_connect_replies_close_the_tunnel() {
        for (reply in listOf(byteArrayOf(5, 5, 0, 1), byteArrayOf(5, 0, 1, 1), byteArrayOf(5, 0, 0, 9), byteArrayOf(5, 0, 0, 3, 0))) {
            rejected(auth = false) { socket, input ->
                greeting(socket, input, false)
                assertArrayEquals(byteArrayOf(5, 1, 0, 3), ByteArray(4).also(input::readFully))
                input.readFully(ByteArray(input.readUnsignedByte() + 2))
                socket.getOutputStream().apply { write(reply); flush() }
                closed(input)
            }
        }
    }

    @Test fun one_deadline_bounds_the_whole_handshake_even_when_replies_arrive_in_pieces() {
        withServer({ socket ->
            val input = DataInputStream(socket.getInputStream())
            assertArrayEquals(byteArrayOf(5, 1, 0), ByteArray(3).also(input::readFully))
            val timer = Executors.newSingleThreadScheduledExecutor()
            try {
                socket.getOutputStream().write(5)
                timer.schedule({ socket.getOutputStream().write(0) }, 500, TimeUnit.MILLISECONDS)
                assertArrayEquals(byteArrayOf(5, 1, 0, 3), ByteArray(4).also(input::readFully))
                input.readFully(ByteArray(input.readUnsignedByte() + 2))
                timer.schedule({ socket.getOutputStream().write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0)) }, 800, TimeUnit.MILLISECONDS)
                closed(input)
            } finally { timer.shutdownNow() }
        }) { proxy ->
            val routes = socks5Routes(proxy, Dns { error("Literal proxy needs no DNS") })
            Socks5SocketFactory(proxy, routes).createSocket().use { socket ->
                assertThrows(SocketTimeoutException::class.java) {
                    socket.connect(InetSocketAddress(routes.lookup("destination.invalid").single(), 443), 1000)
                }
                assertTrue(socket.isClosed)
            }
        }
    }

    @Test fun proxy_dns_preserves_target_names_without_resolving_them_locally() {
        val proxy = ProxyConfiguration.Server("proxy.invalid", 1080, protocol = ProxyProtocol.SOCKS5)
        val first = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val second = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2))
        val routes = socks5Routes(proxy, Dns { host -> assertEquals("proxy.invalid", host); listOf(first, second) })
        val resolved = routes.lookup("destination.invalid")
        assertEquals(2, resolved.size)
        assertEquals("destination.invalid", resolved[0].hostName)
        assertArrayEquals(second.address, resolved[1].address)
    }

    @Test fun literal_ipv4_and_ipv6_targets_use_binary_address_types() {
        for ((host, type) in listOf("192.0.2.17" to 1, "2001:db8::17" to 4)) {
            withServer({ socket ->
                val input = DataInputStream(socket.getInputStream())
                greeting(socket, input, false)
                assertArrayEquals(byteArrayOf(5, 1, 0, type.toByte()), ByteArray(4).also(input::readFully))
                val expected = InetAddress.getByName(host).address
                assertArrayEquals(expected, ByteArray(expected.size).also(input::readFully))
                assertEquals(8443, input.readUnsignedShort())
                socket.getOutputStream().write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
            }) { proxy ->
                val routes = socks5Routes(proxy, Dns { error("Literal proxy needs no DNS") })
                Socks5SocketFactory(proxy, routes).createSocket().use {
                    it.connect(InetSocketAddress(routes.lookup(host).single(), 8443), 4000)
                    assertTrue(it.isConnected)
                }
            }
        }
    }

    private fun closed(input: DataInputStream) {
        try { assertEquals(-1, input.read()) } catch (_: SocketException) { return }
    }

    private fun greeting(socket: Socket, input: DataInputStream, auth: Boolean) {
        assertArrayEquals(byteArrayOf(5, 1, if (auth) 2 else 0), ByteArray(3).also(input::readFully))
        socket.getOutputStream().apply { write(byteArrayOf(5, if (auth) 2 else 0)); flush() }
        if (auth) {
            assertEquals(1, input.readUnsignedByte())
            assertEquals("user", String(ByteArray(input.readUnsignedByte()).also(input::readFully), Charsets.UTF_8))
            assertEquals("password", String(ByteArray(input.readUnsignedByte()).also(input::readFully), Charsets.UTF_8))
        }
    }

    private fun connectRequest(socket: Socket, input: DataInputStream, host: String) {
        assertArrayEquals(byteArrayOf(5, 1, 0, 3), ByteArray(4).also(input::readFully))
        assertEquals(host, String(ByteArray(input.readUnsignedByte()).also(input::readFully), Charsets.US_ASCII))
        assertEquals(443, input.readUnsignedShort())
        socket.getOutputStream().apply { write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0)); flush() }
    }

    private fun rejected(auth: Boolean = true, server: (Socket, DataInputStream) -> Unit) {
        withServer({ server(it, DataInputStream(it.getInputStream())) }, auth) { proxy ->
            val client = NetworkClients().forConfiguration(NetworkConfiguration(false, DnsProvider.SYSTEM, proxy))
                .newBuilder().retryOnConnectionFailure(false).build()
            try {
                assertThrows(IOException::class.java) {
                    client.newCall(Request.Builder().url("https://destination.invalid/").build()).execute().close()
                }
            } finally { client.connectionPool.evictAll() }
        }
    }

    private fun exchange(auth: Boolean, bypass: Boolean = false, wrongPin: Boolean = false) {
        val certificate = HeldCertificate.Builder().commonName("destination.invalid").addSubjectAlternativeName("destination.invalid").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).addTrustedCertificate(certificate.certificate).build()
        withServer({ socket ->
            val input = DataInputStream(socket.getInputStream())
            greeting(socket, input, auth)
            if (auth) socket.getOutputStream().apply { write(byteArrayOf(1, 0)); flush() }
            connectRequest(socket, input, "destination.invalid")
            (tls.sslSocketFactory().createSocket(socket, "destination.invalid", 443, true) as SSLSocket).use { secure ->
                secure.useClientMode = false
                secure.startHandshake()
                if (!wrongPin) {
                    val reader = secure.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    assertEquals("GET / HTTP/1.1", reader.readLine())
                    val headers = buildList { while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; add(line) } }
                    assertFalse(headers.any { it.startsWith("Proxy-Authorization:", true) })
                    secure.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                }
            }
        }, auth) { proxy ->
            val pin = if (wrongPin) HeldCertificate.Builder().build().certificate else certificate.certificate
            val client = NetworkClients().forConfiguration(NetworkConfiguration(bypass, DnsProvider.SYSTEM, proxy)).newBuilder()
                .sslSocketFactory(if (bypass) DpiSocketFactory(tls.sslContext()) else tls.sslSocketFactory(), tls.trustManager)
                .certificatePinner(CertificatePinner.Builder().add("destination.invalid", CertificatePinner.pin(pin)).build())
                .retryOnConnectionFailure(false).build()
            try {
                val call = client.newCall(Request.Builder().url("https://destination.invalid/").build())
                if (wrongPin) assertThrows(SSLPeerUnverifiedException::class.java) { call.execute().close() }
                else call.execute().use { assertEquals("ok", it.body.string()) }
            } finally { client.connectionPool.evictAll() }
        }
    }

    private fun withServer(server: (Socket) -> Unit, auth: Boolean = false, client: (ProxyConfiguration.Server) -> Unit) {
        ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { listener ->
            listener.soTimeout = 6000
            val executor = Executors.newSingleThreadExecutor()
            val task = executor.submit { listener.accept().use { socket -> socket.soTimeout = 6000; server(socket) } }
            try {
                client(ProxyConfiguration.Server(canonicalHost(listener.inetAddress.hostAddress!!), listener.localPort,
                    if (auth) "user" else "", if (auth) "password" else "", ProxyProtocol.SOCKS5))
                task.get(8, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }
}
