package ru.seasonvar.tv.data.network.pac

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.seasonvar.tv.data.network.*
import ru.seasonvar.tv.data.settings.*

class PacManagerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val configuration = NetworkConfiguration()

    private inner class Fixture {
        var wall = 10 * PacRefreshInterval.DAY.millis
        var elapsed = 1_000_000L
        var downloads = 0
        var evaluations = 0
        var published = 0
        var brokenDownload = false
        var brokenParser = false
        val storage = object : PacStorage {
            private val directory = temporary.newFolder()
            override val file = java.io.File(directory, "active.pac")
            override val staging = java.io.File(directory, "staging.pac")
            override var fetchedAt = 0L
            override var attemptedAt = 0L
            override var interval = PacRefreshInterval.DAY
            override fun commit(at: Long) { file.writeBytes(staging.readBytes()); fetchedAt = at }
        }
        fun manager(gate: PacDownloadGate = PacDownloadGate(), cooldown: Long = PacSettings.REFRESH_COOLDOWN_MILLIS) = PacManager(storage, object : PacEvaluator {
            override suspend fun evaluate(file: java.io.File, queries: List<PacQuery>): Map<String, List<PacRoute>> {
                evaluations++
                if (brokenParser) throw PacException(PacFailure.FORMAT)
                return queries.associate { it.host to listOf(PacRoute(PacRouteType.DIRECT)) }
            }
        }, {
            downloads++
            if (brokenDownload) throw PacException(PacFailure.DOWNLOAD)
            "function FindProxyForURL(url, host) { return 'DIRECT'; }".toByteArray(Charsets.UTF_8)
        }, { _, _ -> "203.0.113.1" }, { wall }, { elapsed }, { check(storage.file.isFile); published++ }, gate, cooldown)
        fun advance(millis: Long) { wall += millis; elapsed += millis }
    }

    @Test fun custom_pac_can_refresh_repeatedly_without_a_cooldown_including_after_failure_and_restart() = runBlocking {
        val f = Fixture(); var manager = f.manager(cooldown = 0)
        manager.activate(configuration)
        repeat(3) { assertEquals(0, manager.cooldownSeconds()); assertTrue(manager.refresh(configuration)) }
        assertEquals(4, f.downloads)
        f.brokenDownload = true
        assertTrue(manager.refresh(configuration))
        assertEquals(0, manager.cooldownSeconds())
        f.brokenDownload = false
        manager = f.manager(cooldown = 0)
        assertTrue(manager.refresh(configuration))
        assertEquals(6, f.downloads)
    }

    @Test fun first_activation_downloads_and_prepares_but_fresh_activation_only_prepares() = runBlocking {
        val f = Fixture(); val manager = f.manager()
        manager.activate(configuration)
        assertEquals(1, f.downloads); assertTrue(f.evaluations >= 1); assertEquals(1, f.published)
        assertEquals(f.wall, manager.state.value.fetchedAt)
        f.advance(3_600_000)
        manager.activate(configuration)
        assertEquals(1, f.downloads)
        assertFalse(manager.state.value.downloading); assertFalse(manager.state.value.preparing)
        f.advance(PacRefreshInterval.DAY.millis)
        manager.activate(configuration)
        assertEquals(2, f.downloads)
    }

    @Test fun cooldown_covers_manual_automatic_and_failed_requests_and_survives_recreation() = runBlocking {
        val f = Fixture(); var manager = f.manager()
        manager.activate(configuration)
        assertEquals(60, manager.cooldownSeconds())
        assertFalse(manager.refresh(configuration)); assertEquals(1, f.downloads)
        f.advance(19_000)
        manager = f.manager()
        assertEquals(41, manager.cooldownSeconds())
        assertFalse(manager.refresh(configuration))
        f.advance(41_000)
        f.brokenDownload = true
        assertTrue(manager.refresh(configuration))
        assertEquals(2, f.downloads); assertEquals(60, manager.cooldownSeconds())
        assertEquals(PacFailure.DOWNLOAD, manager.state.value.failure)
        assertFalse(manager.refresh(configuration)); assertEquals(2, f.downloads)
    }

    @Test fun invalid_update_keeps_previous_file_routes_and_success_time() = runBlocking {
        val f = Fixture(); val manager = f.manager()
        manager.activate(configuration)
        val old = f.storage.file.readBytes()
        val successTime = manager.state.value.fetchedAt
        val version = manager.revision
        f.advance(60_000); f.brokenParser = true
        manager.refresh(configuration)
        assertArrayEquals(old, f.storage.file.readBytes())
        assertEquals(successTime, manager.state.value.fetchedAt)
        assertEquals(version, manager.revision); assertEquals(1, f.published)
        assertEquals(PacFailure.FORMAT, manager.state.value.failure)
        assertEquals(listOf(PacRoute(PacRouteType.DIRECT)), manager.routes("seasonvar.ru", configuration))
    }

    @Test fun unknown_host_uses_existing_file_without_a_download_and_then_hits_route_cache() = runBlocking {
        val f = Fixture(); val manager = f.manager()
        manager.activate(configuration)
        val before = f.evaluations
        manager.routes("new-cdn.example", configuration)
        assertEquals(1, f.downloads); assertEquals(before + 1, f.evaluations)
        manager.routes("new-cdn.example", configuration)
        assertEquals(before + 1, f.evaluations)
        manager.routes("new-cdn.example", configuration.copy(dnsProvider = DnsProvider.SYSTEM))
        assertEquals(before + 2, f.evaluations); assertEquals(1, f.downloads)
    }

    @Test fun expired_route_cache_is_recomputed_without_an_early_file_download() = runBlocking {
        val f = Fixture(); val manager = f.manager()
        manager.activate(configuration)
        val before = f.evaluations
        f.advance(PacSettings.ROUTE_TTL_MILLIS)
        manager.routes("seasonvar.ru", configuration)
        assertEquals(before + 1, f.evaluations); assertEquals(1, f.downloads)
    }

    @Test fun automatic_interval_uses_last_attempt_and_does_not_retry_a_failed_update_in_a_loop() = runBlocking {
        val f = Fixture(); val manager = f.manager()
        manager.activate(configuration)
        manager.setInterval(PacRefreshInterval.WEEK)
        f.advance(PacRefreshInterval.DAY.millis)
        manager.updateIfDue(configuration); assertEquals(1, f.downloads)
        f.advance(PacRefreshInterval.WEEK.millis)
        f.brokenDownload = true
        manager.updateIfDue(configuration); assertEquals(2, f.downloads)
        f.advance(60_000)
        manager.updateIfDue(configuration); assertEquals(2, f.downloads)
    }

    @Test fun first_load_failure_never_publishes_a_working_version() = runBlocking {
        val f = Fixture(); val manager = f.manager(); f.brokenParser = true
        try { manager.activate(configuration); fail("Invalid PAC must fail") } catch (_: PacException) {}
        assertEquals(0, manager.revision); assertEquals(0, f.storage.fetchedAt); assertEquals(0, f.published)
        assertFalse(f.storage.file.exists()); assertEquals(60, manager.cooldownSeconds())
    }
}
