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
}
