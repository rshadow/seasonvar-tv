package ru.seasonvar.tv.data.network

import org.junit.Assert.*
import org.junit.Test

class ProxyConfigurationTest {
    @Test fun codec_round_trips_optional_credentials_and_ipv6() {
        for (protocol in ProxyProtocol.entries) for (host in listOf("proxy.invalid", "127.0.0.1", "::1")) for (auth in listOf(false, true)) {
            val proxy = ProxyConfiguration.Server(host, 1080, if (auth) "user" else "", if (auth) "password" else "", protocol)
            assertEquals(proxy, ProxyCodec.decode(ProxyCodec.encode(proxy)))
        }
        assertEquals("example.invalid", canonicalHost(" EXAMPLE.invalid "))
    }

    @Test fun socks_credentials_have_rfc1929_byte_limits_and_http_storage_stays_readable() {
        val proxy = ProxyConfiguration.Server("proxy.invalid", 1080, "я".repeat(127), "x".repeat(255), ProxyProtocol.SOCKS5)
        assertEquals(proxy, ProxyCodec.decode(ProxyCodec.encode(proxy)))
        for ((username, password) in listOf("я".repeat(128) to "pass", "user" to "", "user" to "x".repeat(256))) {
            assertThrows(IllegalArgumentException::class.java) {
                ProxyConfiguration.Server("proxy.invalid", 1080, username, password, ProxyProtocol.SOCKS5)
            }
        }
        assertEquals(ProxyProtocol.HTTP_CONNECT, ProxyCodec.decode("""{"version":1,"type":"http_connect","host":"proxy.invalid","port":8080,"username":"","password":""}""").protocol)
    }

    @Test fun invalid_hosts_ports_credentials_and_unknown_storage_fail_closed() {
        for (host in listOf("", "http://proxy.invalid", "proxy.invalid/path", "user@proxy.invalid")) {
            assertThrows(IllegalArgumentException::class.java) { canonicalHost(host) }
        }
        for (port in listOf(0, 65536, -1)) {
            assertThrows(IllegalArgumentException::class.java) { ProxyConfiguration.Server("proxy.invalid", port) }
        }
        assertThrows(IllegalArgumentException::class.java) { ProxyConfiguration.Server("proxy.invalid", 80, "user:name", "password") }
        assertThrows(IllegalArgumentException::class.java) { ProxyConfiguration.Server("proxy.invalid", 80, "", "password") }
        for (text in listOf("broken", "{}", "{\"version\":2,\"type\":\"http_connect\"}", "{\"version\":1,\"type\":\"future\"}")) {
            assertThrows(Exception::class.java) { ProxyCodec.decode(text) }
        }
    }
}
