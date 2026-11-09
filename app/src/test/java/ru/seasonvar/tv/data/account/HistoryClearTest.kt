package ru.seasonvar.tv.data.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.playback.PlaybackHistoryEvent
import java.io.IOException

class HistoryClearTest {
    private val event = PlaybackHistoryEvent("123", "https://seasonvar.ru/serial-123-test.html", "40", "900", "0", "7")
    @Test fun uses_original_clear_form_without_search_or_episode_parameters() {
        val request = SiteClient.clearHistoryRequest()
        assertEquals("POST", request.method)
        assertEquals("https://seasonvar.ru/ajax.php", request.url.toString())
        assertEquals(PersonalFeed.HISTORY.url, request.header("Referer"))
        assertEquals("https://seasonvar.ru", request.header("Origin"))
        assertEquals("XMLHttpRequest", request.header("X-Requested-With"))
        val body = checkNotNull(request.body)
        assertTrue(body.isOneShot())
        assertEquals("application/x-www-form-urlencoded", body.contentType().toString())
        val buffer = Buffer(); body.writeTo(buffer)
        assertEquals("dellAllHistory=true", buffer.readUtf8())
    }
    @Test fun verifies_empty_history_invalidates_only_history_and_discards_old_queued_writes() = runBlocking {
        val transport = Transport(); val account = repository(transport)
        val version = account.session.value.version
        account.clearHistory(version)
        assertEquals(listOf("GET", "CLEAR", "GET"), transport.calls)
        assertEquals(1L, account.historyRevision.value); assertEquals(1L, account.historyReset.value)
        assertEquals(0L, account.marksRevision.value)
        assertEquals(version, account.session.value.version)
        account.recordHistory(event, version, 0)
        assertEquals(listOf("GET", "CLEAR", "GET"), transport.calls)
        account.recordHistory(event, version, 1)
        assertEquals("SAVE", transport.calls.last()); assertEquals(2L, account.historyRevision.value)
    }
    @Test fun unchanged_history_after_http_success_is_not_reported_as_cleared() = runBlocking {
        val transport = Transport().apply { removes = false }; val account = repository(transport)
        try { account.clearHistory(account.session.value.version); fail() } catch (_: MarkupChangedException) { }
        assertEquals(listOf("GET", "CLEAR", "GET"), transport.calls)
        assertEquals(1L, account.historyRevision.value)
    }
    @Test fun ambiguous_network_failure_invalidates_history_without_automatic_second_post() = runBlocking {
        val transport = Transport().apply { failure = IOException() }; val account = repository(transport)
        try { account.clearHistory(account.session.value.version); fail() } catch (_: IOException) { }
        assertEquals(listOf("GET", "CLEAR"), transport.calls)
        assertEquals(1L, account.historyRevision.value)
    }
    @Test fun guest_unrecognised_and_stale_pages_never_send_clear() = runBlocking {
        for (bad in listOf("<html>Guest</html>", "<div class='headblock-menu'><a href='/profile/12'>Test</a><a href='/?mod=logout'>Exit</a></div>")) {
            val transport = Transport(); val account = repository(transport); transport.html = bad
            try { account.clearHistory(account.session.value.version); fail() } catch (_: Exception) { }
            assertEquals(listOf("GET"), transport.calls)
            assertEquals(0L, account.historyRevision.value); assertEquals(0L, account.historyReset.value)
        }
        val transport = Transport(); val account = repository(transport)
        try { account.clearHistory(0); fail() } catch (_: CancellationException) { }
        assertTrue(transport.calls.isEmpty())
    }
    @Test fun already_empty_history_succeeds_without_destructive_request() = runBlocking {
        val transport = Transport(); val account = repository(transport); transport.empty()
        account.clearHistory(account.session.value.version)
        assertEquals(listOf("GET"), transport.calls)
    }
    private suspend fun repository(transport: Transport): AccountRepository =
        AccountRepository(SessionManager(MemorySessionStore()), transport).also { it.login("test", "dummy"); transport.calls.clear() }
    private class Transport : AccountTransport {
        var html = javaClass.getResource("/account-history.html")!!.readText()
        var removes = true
        var failure: Exception? = null
        val calls = mutableListOf<String>()
        fun empty() { html = Jsoup.parse(html).also { it.select(".pgs-history-c, .pgs-history-p").empty() }.outerHtml() }
        override suspend fun get(url: String, jar: SessionCookies): String { calls += "GET"; return html }
        override suspend fun login(login: String, password: String, jar: SessionCookies) = ""
        override suspend fun saveMark(mark: PlaybackMark, jar: SessionCookies): String = error("Unexpected mark write")
        override suspend fun saveHistory(event: PlaybackHistoryEvent, jar: SessionCookies) { calls += "SAVE" }
        override suspend fun clearHistory(jar: SessionCookies) { calls += "CLEAR"; failure?.let { throw it }; if (removes) empty() }
    }
}
