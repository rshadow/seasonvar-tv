package ru.seasonvar.tv.data.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.SiteClient
import ru.seasonvar.tv.data.SiteHttpException
import ru.seasonvar.tv.data.playback.PlaybackHistoryEvent

class HistoryWriteTest {
    private val event = PlaybackHistoryEvent("123", "https://seasonvar.ru/serial-123-test.html", "40", "900", "0", "7")

    @Test fun sends_original_form_to_same_origin_with_cookie_name_index_and_exact_playlist_id() {
        val request = SiteClient.historyRequest(event, 13)
        assertEquals("https://seasonvar.ru/plStat.php", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals(event.pageUrl, request.header("Referer"))
        assertEquals("https://seasonvar.ru", request.header("Origin"))
        assertEquals("XMLHttpRequest", request.header("X-Requested-With"))
        val form = request.body as FormBody
        assertEquals(mapOf("id_season" to "123", "file_id" to "900", "serial_id" to "40", "svid1" to "13",
            "idTrans" to "0", "page" to "", "numFile" to "7"), (0 until form.size).associate { form.name(it) to form.value(it) })
    }

    @Test fun success_emits_only_history_revision_without_control_get_or_mark_write() = runBlocking {
        val transport = Transport(); val repository = AccountRepository(SessionManager(MemorySessionStore()), transport)
        repository.login("test", "dummy"); transport.calls.clear()
        repository.recordHistory(event, repository.session.value.version)
        assertEquals(listOf("POST history"), transport.calls)
        assertEquals(1L, repository.historyRevision.value); assertEquals(0L, repository.marksRevision.value)
    }

    @Test fun failure_and_stale_player_do_not_emit_a_success_or_read_history() = runBlocking {
        val transport = Transport(); val repository = AccountRepository(SessionManager(MemorySessionStore()), transport)
        repository.login("test", "dummy"); transport.calls.clear(); transport.failure = SiteHttpException(503)
        try { repository.recordHistory(event, repository.session.value.version); fail() } catch (_: SiteHttpException) { }
        assertEquals(0L, repository.historyRevision.value); assertEquals(listOf("POST history"), transport.calls)
        transport.calls.clear()
        try { repository.recordHistory(event, 0); fail() } catch (_: CancellationException) { }
        assertTrue(transport.calls.isEmpty())
    }

    private class Transport : AccountTransport {
        val calls = mutableListOf<String>()
        var failure: Exception? = null
        override suspend fun get(url: String, jar: SessionCookies): String {
            calls += url
            return javaClass.getResource("/account-resume.html")!!.readText()
        }
        override suspend fun login(login: String, password: String, jar: SessionCookies) = ""
        override suspend fun saveMark(mark: PlaybackMark, jar: SessionCookies): String = error("Unexpected mark write")
        override suspend fun saveHistory(event: PlaybackHistoryEvent, jar: SessionCookies) {
            calls += "POST history"; failure?.let { throw it }
        }
    }
}
