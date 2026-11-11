package ru.seasonvar.tv.data.network.pac

import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.settings.PacRefreshInterval

class PacModelsTest {
    @Test fun preset_and_custom_url_share_canonical_address_but_have_isolated_provider_options() {
        val custom = ru.seasonvar.tv.data.network.ProxyConfiguration.Pac("https://P.THENEWONE.LOL:8443/proxy.pac")
        assertEquals(ru.seasonvar.tv.data.network.ProxyConfiguration.AntiZapret.pacUrl(), custom.pacUrl())
        assertFalse(custom.toString().contains("THENEWONE"))
        val preset = requireNotNull(ru.seasonvar.tv.data.network.ProxyConfiguration.AntiZapret.pacSource())
        val generic = requireNotNull(custom.pacSource())
        assertNotEquals(preset.key, generic.key)
        assertEquals(ru.seasonvar.tv.data.settings.PacSettings.USER_AGENT, generic.userAgent)
        assertEquals(ru.seasonvar.tv.data.settings.PacSettings.ANTIZAPRET_USER_AGENT, preset.userAgent)
        assertNotSame(preset.downloadPins, generic.downloadPins)
        assertEquals(javax.net.ssl.SSLSocketFactory.getDefault().javaClass, generic.proxyTls().javaClass)
        assertNotSame(preset.proxyTls(), generic.proxyTls())
        assertEquals(0L, generic.refreshCooldownMillis)
        assertEquals(60_000L, preset.refreshCooldownMillis)
        for (url in listOf("http://pac.example.test/", "https://user:pass@pac.example.test/", "https://pac.example.test/#fragment"))
            assertThrows(IllegalArgumentException::class.java) { ru.seasonvar.tv.data.network.ProxyConfiguration.Pac(url) }
    }
    @Test fun preserves_secure_proxy_and_ordered_fallbacks_without_turning_them_into_a_chain() {
        val routes = PacRoute.parse("HTTPS proxy.example:8443; PROXY backup.example:3128; DIRECT")
        assertEquals(listOf(PacRouteType.HTTPS, PacRouteType.HTTP, PacRouteType.DIRECT), routes.map { it.type })
        assertEquals(PacRoute(PacRouteType.HTTPS, "proxy.example", 8443), routes.first())
        assertEquals(listOf(PacRoute(PacRouteType.DIRECT)), PacRoute.parse("DIRECT"))
        assertEquals("2001:db8::1", PacRoute.parse("HTTPS [2001:db8::1]:8443").single().host)
    }

    @Test fun rejects_unknown_protocols_credentials_paths_and_missing_ports() {
        for (value in listOf("", "SOCKS5 proxy.example:1080", "PROXY user:pass@proxy.example:80",
            "HTTPS proxy.example:0", "HTTPS proxy.example:65536", "HTTPS proxy.example/path:443",
            "HTTPS proxy.example", "HTTPS proxy.example:443?query", "HTTPS proxy.example:443 extra")) {
            assertThrows(value, PacException::class.java) { PacRoute.parse(value) }
        }
    }

    @Test fun default_interval_is_a_day_and_week_is_available() {
        assertEquals(PacRefreshInterval.DAY, PacRefreshInterval.fromHours(-1))
        assertEquals(86_400_000L, PacRefreshInterval.DAY.millis)
        assertEquals(604_800_000L, PacRefreshInterval.WEEK.millis)
        assertEquals(listOf(1, 6, 12, 24, 168), PacRefreshInterval.entries.map { it.hours })
    }
}
