package ru.seasonvar.tv.data.network

import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.dnsoverhttps.DnsOverHttps
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.settings.TlsPinSettings

class NetworkClientsTest {
    @Test fun dns_selection_is_independent_of_bypass_in_all_four_combinations() {
        val clients = NetworkClients()
        for (bypass in listOf(false, true)) {
            for (provider in DnsProvider.entries) {
                val client = clients.forConfiguration(NetworkConfiguration(bypass, provider))
                assertEquals(TlsPinSettings.certificatePinner.pins, client.certificatePinner.pins)
                assertEquals(TlsPinSettings.certificatePinner.pins,
                    clients.forMedia(NetworkConfiguration(bypass, provider)).certificatePinner.pins)
                assertEquals(TlsPinSettings.certificatePinner.pins, client.newBuilder().build().certificatePinner.pins)
                when (provider) {
                    DnsProvider.SYSTEM -> assertSame(Dns.SYSTEM, client.dns)
                    DnsProvider.QUAD9_DOH -> assertTrue(client.dns is DnsOverHttps)
                }
                assertEquals(bypass, client.sslSocketFactory is DpiSocketFactory)
                assertEquals(!bypass, Protocol.HTTP_2 in client.protocols)
                assertTrue(client.hostnameVerifier.verify("example.org",
                    javax.net.ssl.SSLContext.getDefault().createSSLEngine().session).not())
            }
        }
    }

    @Test fun toggling_dns_keeps_transport_and_reuses_each_configuration() {
        val clients = NetworkClients()
        for (bypass in listOf(false, true)) {
            val system = NetworkConfiguration(bypass, DnsProvider.SYSTEM)
            val quad9 = NetworkConfiguration(bypass, DnsProvider.QUAD9_DOH)
            val first = clients.forConfiguration(system)
            val second = clients.forConfiguration(quad9)
            assertSame(first.sslSocketFactory, second.sslSocketFactory)
            assertSame(first, clients.forConfiguration(system))
            assertSame(second, clients.forConfiguration(quad9))
            assertFalse(first.followRedirects)
            assertFalse(second.followSslRedirects)
        }
    }

    @Test fun stored_dns_identifiers_are_stable_and_have_a_defined_default() {
        assertEquals(DnsProvider.SYSTEM, DnsProvider.fromStorage("system"))
        assertEquals(DnsProvider.QUAD9_DOH, DnsProvider.fromStorage("quad9_doh"))
        assertEquals(DnsProvider.QUAD9_DOH, DnsProvider.fromStorage(null))
        assertEquals(DnsProvider.QUAD9_DOH, DnsProvider.fromStorage("future_provider"))
        assertEquals(DnsProvider.QUAD9_DOH, NetworkConfiguration().dnsProvider)
    }
    @Test fun media_uses_same_dns_and_bypass_without_a_whole_video_deadline_or_tls_downgrade() {
        val clients = NetworkClients()
        val configuration = NetworkConfiguration(true, DnsProvider.QUAD9_DOH)
        val site = clients.forConfiguration(configuration)
        val media = clients.forMedia(configuration)
        assertSame(site.dns, media.dns)
        assertSame(site.sslSocketFactory, media.sslSocketFactory)
        assertEquals(0, media.callTimeoutMillis)
        assertTrue(media.followRedirects)
        assertFalse(media.followSslRedirects)
        assertSame(media, clients.forMedia(configuration))
    }

}
