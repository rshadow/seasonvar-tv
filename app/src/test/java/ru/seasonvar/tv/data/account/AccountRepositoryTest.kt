package ru.seasonvar.tv.data.account

import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AccountRepositoryTest {
    private val authenticated get() = javaClass.getResource("/account-resume.html")!!.readText()
    private val guest = """<div class="headblock-menu"><a href="/?mod=login">Вход</a></div><div class="pgs-marks"></div>"""
    private val site = "https://seasonvar.ru/".toHttpUrl()
    private inner class Transport : AccountTransport {
        val calls = mutableListOf<String>()
        var page = authenticated
        var historyPage = javaClass.getResource("/account-history.html")!!.readText()
        var accepted = true
        var expired = false
        var offline = false
        override suspend fun get(url: String, jar: SessionCookies): String {
            calls += url
            if (offline) throw IOException()
            return if ((url.endsWith("/resume") || HistoryParser.pageUrl(url) != null) && accepted && !expired && jar.loadForRequest(site).isNotEmpty()) {
                if (HistoryParser.pageUrl(url) != null) historyPage else page
            } else guest
        }
        override suspend fun login(login: String, password: String, jar: SessionCookies): String {
            calls += "POST login"
            jar.saveFromResponse(site, listOf(Cookie.Builder().name("svid1").value("dummy-session").hostOnlyDomain("seasonvar.ru").secure().build()))
            return authenticated
        }
    }
    @Test fun login_is_verified_by_a_second_request_and_restore_does_not_need_password() = runBlocking {
        val store = MemorySessionStore(); val manager = SessionManager(store); val transport = Transport()
        val repository = AccountRepository(manager, transport)
        repository.login("example", "dummy-password")
        assertEquals(listOf("https://seasonvar.ru/?mod=login", "POST login", "https://seasonvar.ru/resume"), transport.calls)
        assertEquals("12", repository.session.value.user!!.id)
        assertEquals(2, repository.resume().entries.size)
        val restored = AccountRepository(SessionManager(store), transport)
        restored.restore()
        assertEquals("12", restored.session.value.user!!.id)
    }
    @Test fun successful_http_or_post_body_is_not_proof_of_login() = runBlocking {
        val store = MemorySessionStore(); val transport = Transport().apply { accepted = false }
        val repository = AccountRepository(SessionManager(store), transport)
        try { repository.login("example", "dummy-password"); fail() } catch (_: LoginRejectedException) { }
        assertNull(repository.session.value.user)
        assertTrue(store.cookies.isEmpty())
    }
    @Test fun expired_session_clears_identity_and_private_list_instead_of_reporting_empty() = runBlocking {
        val store = MemorySessionStore(); val manager = SessionManager(store); val transport = Transport()
        val repository = AccountRepository(manager, transport)
        repository.login("example", "dummy-password")
        transport.expired = true
        try { repository.resume(); fail() } catch (_: SessionExpiredException) { }
        assertNull(repository.session.value.user)
        assertTrue(repository.session.value.expired)
        assertFalse(manager.hasSession())
    }
    @Test fun network_failure_does_not_destroy_persisted_session() = runBlocking {
        val store = MemorySessionStore(); val transport = Transport(); val repository = AccountRepository(SessionManager(store), transport)
        repository.login("example", "dummy-password")
        transport.offline = true
        try { repository.restore(); fail() } catch (_: IOException) { }
        assertNotNull(repository.session.value.user)
        assertFalse(store.cookies.isEmpty())
    }
    @Test fun offline_logout_still_clears_local_identity_and_cookies() = runBlocking {
        val store = MemorySessionStore(); val manager = SessionManager(store); val transport = Transport()
        val repository = AccountRepository(manager, transport)
        repository.login("example", "dummy-password")
        transport.offline = true
        repository.logout()
        assertNull(repository.session.value.user)
        assertFalse(manager.hasSession())
        assertTrue(store.cookies.isEmpty())
    }
    @Test fun personal_sections_select_their_tab_and_history_uses_its_own_route() = runBlocking {
        val transport = Transport().apply { page = javaClass.getResource("/account-resume-current.html")!!.readText() }
        val repository = AccountRepository(SessionManager(MemorySessionStore()), transport)
        repository.login("example", "dummy-password")
        transport.calls.clear()
        val expected = listOf(listOf("123", "456"), listOf("123"), listOf("456"), listOf("789"), listOf("999"), listOf("123", "456", "123"))
        PersonalFeed.entries.forEachIndexed { index, feed ->
            assertEquals(expected[index], repository.personalList(feed).entries.map { it.id })
        }
        assertEquals(List(5) { "https://seasonvar.ru/resume" } + "https://seasonvar.ru/?mod=history", transport.calls)
    }
    @Test fun expiration_in_any_personal_section_clears_the_session() = runBlocking {
        PersonalFeed.entries.forEach { feed ->
            val manager = SessionManager(MemorySessionStore())
            val transport = Transport()
            val repository = AccountRepository(manager, transport)
            repository.login("example", "dummy-password")
            transport.expired = true
            try { repository.personalList(feed); fail(feed.name) } catch (_: SessionExpiredException) { }
            assertNull(repository.session.value.user)
            assertTrue(repository.session.value.expired)
            assertFalse(manager.hasSession())
        }
    }

    @Test fun history_next_page_is_authenticated_but_untrusted_pagination_never_reaches_transport() = runBlocking {
        val transport = Transport()
        val repository = AccountRepository(SessionManager(MemorySessionStore()), transport)
        repository.login("example", "dummy-password")
        transport.calls.clear()
        val result = repository.personalList(PersonalFeed.HISTORY, "https://seasonvar.ru/?mod=history&page=2")
        assertEquals("https://seasonvar.ru/?mod=history&page=3", result.nextPage)
        try { repository.personalList(PersonalFeed.HISTORY, "https://foreign.example.test/?mod=history&page=3"); fail() }
        catch (_: ru.seasonvar.tv.data.MarkupChangedException) { }
        assertEquals(listOf("https://seasonvar.ru/?mod=history&page=2"), transport.calls)
    }

    @Test fun progress_snapshot_requires_verified_authentication_and_contains_server_marks() = runBlocking {
        val transport = Transport().apply { page = javaClass.getResource("/account-resume-current.html")!!.readText() }
        val repository = AccountRepository(SessionManager(MemorySessionStore()), transport)
        assertNull(repository.progressSnapshot()); assertTrue(transport.calls.isEmpty())
        repository.login("example", "dummy-password")
        val snapshot = repository.progressSnapshot()!!
        assertEquals(setOf("456", "999"), snapshot.completedSeasonIds)
        transport.expired = true
        try { repository.progressSnapshot(); fail() } catch (_: SessionExpiredException) { }
        assertNull(repository.session.value.user)
    }

}
