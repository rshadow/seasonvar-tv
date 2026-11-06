package ru.seasonvar.tv.data.account

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

internal class MemorySessionStore : SessionStore {
    var cookies = emptyList<Cookie>()
    override fun read() = cookies
    override fun save(cookies: List<Cookie>) { this.cookies = cookies }
    override fun clear() { cookies = emptyList() }
}

class SessionCookiesTest {
    private val site = "https://seasonvar.ru/".toHttpUrl()
    private fun cookie(name: String = "example", path: String = "/", hostOnly: Boolean = false): Cookie = Cookie.Builder()
        .name(name).value("dummy-value").path(path).secure().httpOnly()
        .apply { if (hostOnly) hostOnlyDomain("seasonvar.ru") else domain("seasonvar.ru") }.build()

    @Test fun respects_cookie_path_host_only_and_domain_rules() {
        val jar = SessionCookies(listOf(cookie("domain", "/resume"), cookie("host", hostOnly = true)))
        assertEquals(listOf("host"), jar.loadForRequest(site).map { it.name })
        assertEquals(setOf("domain", "host"), jar.loadForRequest("https://seasonvar.ru/resume/more".toHttpUrl()).map { it.name }.toSet())
        assertEquals(listOf("domain"), jar.loadForRequest("https://www.seasonvar.ru/resume".toHttpUrl()).map { it.name })
    }
    @Test fun never_sends_or_accepts_account_cookies_on_a_cdn_or_over_http() {
        val jar = SessionCookies(listOf(cookie()))
        for (url in listOf("https://cdn.svstat.net/", "https://seasonvar.ru.example.test/", "http://seasonvar.ru/")) {
            assertTrue(jar.loadForRequest(url.toHttpUrl()).isEmpty())
        }
        jar.saveFromResponse("https://cdn.svstat.net/".toHttpUrl(), listOf(cookie("injected")))
        assertEquals(listOf("example"), jar.loadForRequest(site).map { it.name })
    }
    @Test fun removes_expired_cookie_without_erasing_other_paths() {
        val jar = SessionCookies(listOf(cookie("same"), cookie("same", "/resume")))
        jar.saveFromResponse(site, listOf(Cookie.Builder().name("same").value("deleted").domain("seasonvar.ru").path("/").expiresAt(1).build()))
        assertEquals(listOf("/resume"), jar.snapshot().map { it.path })
    }
    @Test fun codec_preserves_session_and_persistent_cookie_attributes() {
        val session = cookie(hostOnly = true)
        val persistent = cookie("persistent", "/resume").newBuilderForTest()
        val decoded = CookieCodec.decode(CookieCodec.encode(listOf(session, persistent)))
        assertEquals(listOf(session, persistent), decoded)
        assertFalse(decoded[0].persistent)
        assertTrue(decoded[1].persistent)
    }
    private fun Cookie.newBuilderForTest(): Cookie = Cookie.Builder().name(name).value(value).domain(domain).path(path)
        .expiresAt(System.currentTimeMillis() + 100_000).secure().httpOnly().build()

    @Test fun discarded_candidates_and_late_responses_cannot_restore_a_logged_out_session() {
        val store = MemorySessionStore()
        val manager = SessionManager(store)
        val candidate = manager.candidate()
        candidate.saveFromResponse(site, listOf(cookie()))
        assertTrue(store.cookies.isEmpty())
        manager.commit(candidate)
        assertTrue(manager.hasSession())
        assertNull(manager.cacheToken())
        manager.clear()
        candidate.saveFromResponse(site, listOf(cookie("late")))
        assertFalse(manager.hasSession())
        assertTrue(store.cookies.isEmpty())
        assertTrue(manager.current().loadForRequest(site).isEmpty())
    }
    @Test fun restored_jar_remains_private_until_server_verification() {
        val store = MemorySessionStore().apply { cookies = listOf(cookie()) }
        val manager = SessionManager(store)
        assertTrue(manager.hasSession())
        assertNull(manager.cacheToken())
    }
    private class FailingReadStore(initial: List<Cookie>) : SessionStore {
        var cookies = initial
        var failure: Exception? = null
        var reads = 0
        var writes = 0
        var clears = 0
        override fun read(): List<Cookie> {
            reads++
            failure?.let { throw it }
            return cookies
        }
        override fun save(cookies: List<Cookie>) { writes++; this.cookies = cookies }
        override fun clear() { clears++; cookies = emptyList() }
    }
    @Test fun read_failure_preserves_saved_cookies_and_allows_retry_without_memoizing_a_guest() {
        val original = listOf(cookie())
        val error = java.io.IOException("Transient read failure")
        val store = FailingReadStore(original).apply { failure = error }
        val manager = SessionManager(store)
        try { manager.current(); fail("Unreadable session must not become a guest session") }
        catch (failure: SessionStorageException) { assertSame(error, failure.cause) }
        assertEquals(original, store.cookies); assertEquals(0, store.clears); assertEquals(0, store.writes)
        store.failure = null
        assertTrue(manager.hasSession())
        assertEquals(original, manager.current().loadForRequest(site))
        assertEquals(2, store.reads)
    }
    @Test fun decryption_failure_is_reported_without_erasing_the_encrypted_session() {
        val original = listOf(cookie())
        val store = FailingReadStore(original).apply { failure = javax.crypto.AEADBadTagException("Fixture") }
        val manager = SessionManager(store)
        repeat(2) {
            try { manager.hasSession(); fail() }
            catch (failure: SessionStorageException) { assertTrue(failure.cause is javax.crypto.AEADBadTagException) }
        }
        assertEquals(original, store.cookies); assertEquals(0, store.clears); assertEquals(0, store.writes)
    }
    @Test fun cancellation_is_rethrown_unchanged_and_never_clears_or_caches_a_guest() {
        val original = listOf(cookie())
        val cancelled = kotlinx.coroutines.CancellationException("Cancelled")
        val store = FailingReadStore(original).apply { failure = cancelled }
        val manager = SessionManager(store)
        for (read in listOf<() -> Any?>({ manager.current() }, { manager.hasSession() }, { manager.cacheToken() })) {
            try { read(); fail() }
            catch (error: kotlinx.coroutines.CancellationException) { assertSame(cancelled, error) }
        }
        assertEquals(original, store.cookies); assertEquals(0, store.clears); assertEquals(0, store.writes)
        store.failure = null
        assertTrue(manager.hasSession())
    }
    @Test fun unreadable_session_never_enables_public_cache_and_explicit_clear_still_works() {
        val original = listOf(cookie())
        val store = FailingReadStore(original).apply { failure = java.io.IOException("Read failed") }
        val manager = SessionManager(store)
        assertNull(manager.cacheToken())
        assertEquals(original, store.cookies); assertEquals(0, store.clears)
        store.failure = null
        assertTrue(manager.hasSession()); assertNull(manager.cacheToken())
        manager.clear()
        assertEquals(1, store.clears); assertTrue(store.cookies.isEmpty()); assertFalse(manager.hasSession())
        assertNotNull(manager.cacheToken())
    }
    @Test fun an_existing_storage_error_is_propagated_without_erasing_or_wrapping_it() {
        val error = SessionStorageException()
        val store = FailingReadStore(listOf(cookie())).apply { failure = error }
        try { SessionManager(store).current(); fail() }
        catch (failure: SessionStorageException) { assertSame(error, failure) }
        assertEquals(0, store.clears); assertEquals(0, store.writes)
    }

}
