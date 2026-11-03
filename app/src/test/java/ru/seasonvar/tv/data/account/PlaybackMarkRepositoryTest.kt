package ru.seasonvar.tv.data.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.MarkupChangedException
import java.io.IOException

class PlaybackMarkRepositoryTest {
    private val page = "https://seasonvar.ru/serial-123-Test.html"
    private val point = PlaybackMark("123", page, "7", "298", 754)
    private val site = "https://seasonvar.ru/".toHttpUrl()
    private val guest = """<div class="headblock-menu"><a href="/?mod=login">Вход</a></div>"""
    private inner class Transport : AccountTransport {
        var saved: PlaybackMark? = null
        var status: Int? = null
        var response = """{"msg":"success","id":999}"""
        var persist = true
        var expired = false
        var expireAfterWrite = false
        var invalidPage = false
        var offline = false
        val calls = mutableListOf<String>()
        val writes = mutableListOf<PlaybackMark>()
        override suspend fun get(url: String, jar: SessionCookies): String {
            calls += "GET $url"
            if (offline) throw IOException()
            if (expired || jar.loadForRequest(site).isEmpty()) return guest
            if (url != page) return javaClass.getResource("/account-resume.html")!!.readText()
            if (invalidPage) return "<html>changed markup</html>"
            val mark = saved
            val href = if (status != null) page + "#rewind=" + status + "_seriya" else mark?.let { page + "#rewind=" + it.episode + "_seriya_na_" +
                (it.positionSeconds / 60) + "_minute_" + (it.positionSeconds % 60) + "_sekunde" }.orEmpty()
            val json = JSONObject().put("href", href).put("trans", mark?.translationId ?: "0")
            return """<div class="headblock-menu"><a href="/profile/12">Профиль</a></div>
                <div class="pgs-sinfo" data-id-season="123"></div><script>var mark=$json;</script>"""
        }
        override suspend fun login(login: String, password: String, jar: SessionCookies): String {
            jar.saveFromResponse(site, listOf(Cookie.Builder().name("svid1").value("dummy-session")
                .hostOnlyDomain("seasonvar.ru").secure().build()))
            return ""
        }
        override suspend fun saveMark(mark: PlaybackMark, jar: SessionCookies): String {
            calls += "POST mark"
            writes += mark
            if (persist) saved = mark
            if (expireAfterWrite) expired = true
            return response
        }
    }
    private suspend fun loggedIn(transport: Transport): AccountRepository {
        val repository = AccountRepository(SessionManager(MemorySessionStore()), transport)
        repository.login("example", "dummy-password")
        transport.calls.clear()
        return repository
    }

    @Test fun saving_is_confirmed_by_a_fresh_authenticated_page_after_the_post() = runBlocking {
        val transport = Transport()
        val repository = loggedIn(transport)
        assertEquals(point, repository.savePlaybackMark(point))
        assertEquals(listOf("GET $page", "POST mark", "GET $page"), transport.calls)
        assertEquals(listOf(point), transport.writes)
    }

    @Test fun success_response_without_persisted_state_is_not_reported_as_saved() = runBlocking {
        val transport = Transport().apply { persist = false }
        val repository = loggedIn(transport)
        try { repository.savePlaybackMark(point); fail() } catch (_: MarkVerificationException) { }
    }

    @Test fun rejected_response_does_not_report_success_even_if_the_page_changed() = runBlocking {
        val transport = Transport().apply { response = """{"success":true}""" }
        val repository = loggedIn(transport)
        try { repository.savePlaybackMark(point); fail() } catch (_: MarkSaveRejectedException) { }
        assertNotNull(repository.session.value.user)
    }

    @Test fun guest_and_stale_account_actions_never_reach_the_write_transport() = runBlocking {
        val transport = Transport()
        val guestRepository = AccountRepository(SessionManager(MemorySessionStore()), transport)
        try { guestRepository.savePlaybackMark(point); fail() } catch (_: SessionExpiredException) { }
        val repository = loggedIn(transport)
        val version = repository.session.value.version
        repository.logout()
        repository.login("other", "dummy-password")
        transport.calls.clear()
        try { repository.savePlaybackMark(point, version); fail() } catch (_: CancellationException) { }
        assertTrue(transport.writes.isEmpty())
        assertTrue(transport.calls.isEmpty())
    }

    @Test fun session_expiry_after_the_post_clears_identity_and_does_not_claim_success() = runBlocking {
        for (response in listOf("""{"msg":"success"}""", """{"msg":"error"}""")) {
            val transport = Transport().apply { this.response = response; expireAfterWrite = true }
            val repository = loggedIn(transport)
            try { repository.savePlaybackMark(point); fail() } catch (_: SessionExpiredException) { }
            assertNull(repository.session.value.user)
            assertTrue(repository.session.value.expired)
        }
    }

    @Test fun automatic_updates_do_not_downgrade_later_episodes_or_completed_marks() = runBlocking {
        for (existing in listOf(point.copy(episode = "8"), point.copy(positionSeconds = 0), point)) {
            val transport = Transport().apply { saved = existing }
            val repository = loggedIn(transport)
            assertNull(repository.savePlaybackMark(point, automatic = true))
            assertTrue(transport.writes.isEmpty())
            assertEquals(listOf("GET $page"), transport.calls)
        }
        for (status in listOf(-2, -3)) {
            val transport = Transport().apply { this.status = status }
            val repository = loggedIn(transport)
            assertNull(repository.savePlaybackMark(point, automatic = true))
            assertTrue(transport.writes.isEmpty())
        }
    }

    @Test fun automatic_completion_replaces_timed_point_and_notifies_after_verification() = runBlocking {
        val transport = Transport().apply { saved = point }
        val repository = loggedIn(transport)
        assertEquals(0L, repository.marksRevision.value)
        assertEquals(point.copy(positionSeconds = 0), repository.savePlaybackMark(point.copy(positionSeconds = 0), automatic = true))
        assertEquals(1L, repository.marksRevision.value)
    }

    @Test fun invalid_page_or_network_failure_prevents_writes_and_preserves_existing_session() = runBlocking {
        for (offline in listOf(false, true)) {
            val transport = Transport()
            val repository = loggedIn(transport)
            transport.invalidPage = !offline
            transport.offline = offline
            try { repository.savePlaybackMark(point); fail() }
            catch (error: Exception) { assertTrue(error is MarkupChangedException || error is IOException) }
            assertTrue(transport.writes.isEmpty())
            assertNotNull(repository.session.value.user)
        }
    }
}
