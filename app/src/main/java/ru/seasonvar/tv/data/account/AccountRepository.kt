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
    suspend fun saveMark(mark: PlaybackMark, jar: SessionCookies): String
}

data class AccountSession(val user: AccountUser? = null, val version: Long = 0, val expired: Boolean = false)

class AccountRepository(private val sessions: SessionManager, private val transport: AccountTransport) {
    constructor(sessions: SessionManager, client: SiteClient) : this(sessions, object : AccountTransport {
        override suspend fun get(url: String, jar: SessionCookies): String {
            val html = client.accountGet(url, jar)
            if (ru.seasonvar.tv.BuildConfig.DEBUG && url == "https://seasonvar.ru/resume") {
                android.util.Log.d("SeasonvarAccount", ru.seasonvar.tv.diagnostics.accountStructure(html))
                android.util.Log.d("SeasonvarAccount", ru.seasonvar.tv.diagnostics.accountProgressStructure(html))
            }
            if (ru.seasonvar.tv.BuildConfig.DEBUG && HistoryParser.pageUrl(url) != null) {
                android.util.Log.d("SeasonvarAccount", ru.seasonvar.tv.diagnostics.historyStructure(html))
            }
            return html
        }
        override suspend fun login(login: String, password: String, jar: SessionCookies) = client.accountLogin(login, password, jar)
        override suspend fun saveMark(mark: PlaybackMark, jar: SessionCookies) = client.accountMark(mark, jar)
    })
    private val mutableSession = MutableStateFlow(AccountSession())
    val session = mutableSession.asStateFlow()
    private val mutex = Mutex()
    private val mutableMarksRevision = MutableStateFlow(0L)
    val marksRevision = mutableMarksRevision.asStateFlow()

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

    suspend fun personalList(feed: PersonalFeed, pageUrl: String? = null): AccountPage = withContext(Dispatchers.IO) {
        val url = if (pageUrl == null) feed.url else {
            require(feed == PersonalFeed.HISTORY)
            HistoryParser.pageUrl(pageUrl) ?: throw ru.seasonvar.tv.data.MarkupChangedException()
        }
        AccountParser.personalList(authenticatedHtml(url), feed, url)
    }

    suspend fun progressSnapshot(): AccountProgressSnapshot? = withContext(Dispatchers.IO) {
        if (session.value.user == null) return@withContext null
        AccountProgressParser.snapshot(authenticatedHtml("https://seasonvar.ru/resume"))
    }

    suspend fun savePlaybackMark(mark: PlaybackMark, ownerVersion: Long = session.value.version, automatic: Boolean = false): PlaybackMark? = withContext(Dispatchers.IO) { mutex.withLock {
        val expected = session.value
        if (expected.user == null) throw SessionExpiredException()
        if (expected.version != ownerVersion) throw kotlinx.coroutines.CancellationException()
        val jar = sessions.current()
        suspend fun read(): String {
            val html = transport.get(mark.pageUrl, jar)
            currentCoroutineContext().ensureActive()
            if (session.value.version != expected.version) throw kotlinx.coroutines.CancellationException()
            if (AccountParser.user(html)?.id != expected.user.id) {
                clear(expired = true)
                throw SessionExpiredException()
            }
            return html
        }
        val before = PlaybackMarkParser.snapshot(read(), mark.seasonId, mark.pageUrl)
        if (automatic && markSupersedes(before, mark)) return@withLock null
        currentCoroutineContext().ensureActive()
        val response = transport.saveMark(mark, jar)
        currentCoroutineContext().ensureActive()
        if (session.value.version != expected.version) throw kotlinx.coroutines.CancellationException()
        try { PlaybackMarkParser.requireSuccess(response) }
        catch (error: MarkSaveRejectedException) { read(); throw error }
        val confirmed = PlaybackMarkParser.parse(read(), mark.seasonId, mark.pageUrl)
        if (confirmed != mark) throw MarkVerificationException()
        mutableMarksRevision.value++
        mark
    } }

    private suspend fun authenticatedHtml(url: String): String {
        val expected = session.value
        if (expected.user == null) throw SessionExpiredException()
        val html = transport.get(url, sessions.current())
        currentCoroutineContext().ensureActive()
        if (session.value.version != expected.version) throw kotlinx.coroutines.CancellationException()
        if (AccountParser.user(html) == null) {
            mutex.withLock { if (session.value.version == expected.version) clear(expired = true) }
            throw SessionExpiredException()
        }
        return html
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
