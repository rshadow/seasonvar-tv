package ru.seasonvar.tv.ui.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.account.*
import ru.seasonvar.tv.diagnostics.DiagnosticStep
import ru.seasonvar.tv.diagnostics.LocalDiagnosticFailure
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelTest {
    @Test fun confirmed_login_exposes_the_storage_failure_code() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val failure = LocalDiagnosticFailure(DiagnosticStep.SESSION_WRITE, "COMMIT")
            val store = object : SessionStore {
                override fun read() = emptyList<Cookie>()
                override fun save(cookies: List<Cookie>) = throw SessionStorageException(failure)
                override fun clear() = error("Session must not be cleared")
            }
            val authenticated = javaClass.getResource("/account-resume.html")!!.readText()
            val transport = object : AccountTransport {
                override suspend fun get(url: String, jar: SessionCookies) = authenticated
                override suspend fun login(login: String, password: String, jar: SessionCookies) = authenticated
                override suspend fun saveMark(mark: PlaybackMark, jar: SessionCookies) = error("No viewing mark needed")
            }
            val repository = AccountRepository(SessionManager(store), transport)
            val model = AccountViewModel(repository)
            model.state.first { !it.busy }
            model.login("fixture", "fixture")

            val state = model.state.first { !it.busy }
            assertEquals(AccountFailure.STORAGE, state.failure)
            assertEquals("S04-COMMIT", state.storageFailureCode)
            assertEquals(failure, LocalDiagnosticFailure.fromCode(requireNotNull(state.storageFailureCode)))
            assertNull(LocalDiagnosticFailure.fromCode("S04-PRIVATE"))
            assertNull(repository.session.value.user)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun unreadable_session_is_a_retryable_storage_error_not_an_expired_login() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var unreadable = true
            var clears = 0
            val cookie = Cookie.Builder().name("svid1").value("dummy-session").hostOnlyDomain("seasonvar.ru").secure().build()
            val store = object : SessionStore {
                override fun read(): List<Cookie> { if (unreadable) throw IOException("Read failed"); return listOf(cookie) }
                override fun save(cookies: List<Cookie>) = error("No new login should be saved")
                override fun clear() { clears++ }
            }
            val authenticated = javaClass.getResource("/account-resume.html")!!.readText()
            var requests = 0
            val transport = object : AccountTransport {
                override suspend fun get(url: String, jar: SessionCookies): String {
                    assertEquals(listOf(cookie), jar.loadForRequest("https://seasonvar.ru/resume".toHttpUrl()))
                    requests++
                    return authenticated
                }
                override suspend fun login(login: String, password: String, jar: SessionCookies) = error("No password needed")
                override suspend fun saveMark(mark: PlaybackMark, jar: SessionCookies) = error("No viewing mark needed")
            }
            val repository = AccountRepository(SessionManager(store), transport)
            val model = AccountViewModel(repository)
            val failed = model.state.first { !it.busy }
            assertEquals(AccountFailure.STORAGE, failed.failure)
            assertFalse(model.state.value.busy); assertFalse(repository.session.value.expired)
            assertEquals(0, requests); assertEquals(0, clears)
            unreadable = false
            model.restore()
            val restored = model.state.first { !it.busy }
            assertNull(restored.failure); assertFalse(restored.busy)
            assertEquals("12", repository.session.value.user!!.id)
            assertEquals(1, requests); assertEquals(0, clears)
        } finally { Dispatchers.resetMain() }
    }
}
