package ru.seasonvar.tv.data.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.seasonvar.tv.data.SiteClient

interface AccountTransport {
    suspend fun get(url: String, jar: SessionCookies): String
    suspend fun login(login: String, password: String, jar: SessionCookies): String
}

data class AccountSession(val user: AccountUser? = null, val version: Long = 0, val expired: Boolean = false)

class AccountRepository(private val sessions: SessionManager, private val transport: AccountTransport) {
    constructor(sessions: SessionManager, client: SiteClient) : this(sessions, object : AccountTransport {
        override suspend fun get(url: String, jar: SessionCookies): String {
            val html = client.accountGet(url, jar)
            if (ru.seasonvar.tv.BuildConfig.DEBUG && url == "https://seasonvar.ru/resume") {
                android.util.Log.d("SeasonvarAccount", ru.seasonvar.tv.diagnostics.accountStructure(html))
            }
            return html
        }
        override suspend fun login(login: String, password: String, jar: SessionCookies) = client.accountLogin(login, password, jar)
    })
    private val mutableSession = MutableStateFlow(AccountSession())
    val session = mutableSession.asStateFlow()
    private val mutex = Mutex()

    suspend fun restore(): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        if (!sessions.hasSession()) return@withLock
        val html = transport.get("https://seasonvar.ru/resume", sessions.current())
        val user = AccountParser.user(html)
        currentCoroutineContext().ensureActive()
        if (user == null) { clear(expired = true); throw SessionExpiredException() }
        mutableSession.value = AccountSession(user, session.value.version + 1)
    } }

    suspend fun login(login: String, password: String): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        if (login.isBlank() || password.isEmpty()) throw LoginRejectedException()
        val candidate = sessions.candidate()
        transport.get("https://seasonvar.ru/?mod=login", candidate)
        transport.login(login.trim(), password, candidate)
        val user = AccountParser.user(transport.get("https://seasonvar.ru/resume", candidate)) ?: throw LoginRejectedException()
        currentCoroutineContext().ensureActive()
        sessions.commit(candidate)
        mutableSession.value = AccountSession(user, session.value.version + 1)
    } }

    suspend fun resume(): AccountPage = personalList(PersonalFeed.RESUME)

    suspend fun personalList(feed: PersonalFeed): AccountPage = withContext(Dispatchers.IO) {
        val expected = session.value
        if (expected.user == null) throw SessionExpiredException()
        val jar = sessions.current()
        val html = transport.get("https://seasonvar.ru/resume", jar)
        currentCoroutineContext().ensureActive()
        if (session.value.version != expected.version) throw kotlinx.coroutines.CancellationException()
        if (AccountParser.user(html) == null) {
            mutex.withLock { if (session.value.version == expected.version) clear(expired = true) }
            throw SessionExpiredException()
        }
        AccountParser.personalList(html, feed)
    }

    suspend fun logout(): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        val previous = sessions.current()
        clear()
        try { transport.get("https://seasonvar.ru/?mod=logout", previous) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { }
    } }

    private fun clear(expired: Boolean = false) {
        sessions.clear()
        mutableSession.value = AccountSession(version = session.value.version + 1, expired = expired)
    }
}
