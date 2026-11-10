package ru.seasonvar.tv.data.network

import org.junit.Assert.*
import org.junit.Test

class ProxyConfigurationTest {
    @Test fun codec_round_trips_optional_credentials_and_ipv6() {
        for (host in listOf("proxy.invalid", "127.0.0.1", "::1")) for (auth in listOf(false, true)) {
            val proxy = ProxyConfiguration.Server(host, 1080, if (auth) "user" else "", if (auth) "password" else "")
            assertEquals(proxy, ProxyCodec.decode(ProxyCodec.encode(proxy)))
        }
        assertEquals("example.invalid", canonicalHost(" EXAMPLE.invalid "))
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
