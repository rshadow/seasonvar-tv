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
    suspend fun removeMark(mark: AccountListMark, jar: SessionCookies): String = throw UnsupportedOperationException()
    suspend fun saveStatus(change: AccountStatusChange, jar: SessionCookies): String = throw UnsupportedOperationException()
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
        override suspend fun removeMark(mark: AccountListMark, jar: SessionCookies) = client.accountRemoveMark(mark, jar)
        override suspend fun saveStatus(change: AccountStatusChange, jar: SessionCookies) = client.accountStatus(change, jar)
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

    suspend fun listStatus(target: AccountListMark): AccountListStatus? = withContext(Dispatchers.IO) {
        AccountListStatus.from(PlaybackMarkParser.snapshot(authenticatedHtml(target.pageUrl), target.seasonId, target.pageUrl))
    }

    suspend fun setListStatus(entry: ru.seasonvar.tv.data.SeriesEntry, status: AccountListStatus,
        ownerVersion: Long): PlaybackMarkSnapshot = withContext(Dispatchers.IO) { mutex.withLock {
        val target = AccountListMark(entry.id, entry.url)
        val expected = session.value
        if (expected.user == null) throw SessionExpiredException()
        if (expected.version != ownerVersion) throw kotlinx.coroutines.CancellationException()
        val jar = sessions.current()
        suspend fun read(): String {
            val html = transport.get(target.pageUrl, jar)
            currentCoroutineContext().ensureActive()
            if (session.value.version != expected.version) throw kotlinx.coroutines.CancellationException()
            if (AccountParser.user(html)?.id != expected.user.id) {
                clear(expired = true); throw SessionExpiredException()
            }
            return html
        }
        val beforeHtml = read()
        val before = PlaybackMarkParser.snapshot(beforeHtml, target.seasonId, target.pageUrl)
        if (AccountListStatus.from(before) == status) return@withLock before
        val watching = if (status == AccountListStatus.WATCHING) AccountStatusChange.watchingMark(
            ru.seasonvar.tv.data.SeriesDetailsParser.parse(beforeHtml, entry), entry.initialSelection) else null
        val response = transport.saveStatus(AccountStatusChange(target, status, watching), jar)
        currentCoroutineContext().ensureActive()
        if (session.value.version != expected.version) throw kotlinx.coroutines.CancellationException()
        try { PlaybackMarkParser.requireSuccess(response) }
        catch (error: MarkSaveRejectedException) { read(); throw error }
        val after = PlaybackMarkParser.snapshot(read(), target.seasonId, target.pageUrl)
        if (AccountListStatus.from(after) != status || watching != null && after.mark != watching) throw MarkVerificationException()
        mutableMarksRevision.value++
        after
    } }

    suspend fun listMark(target: AccountListMark): AccountListMark? = withContext(Dispatchers.IO) {
        if (session.value.user == null) return@withContext null
        val snapshot = PlaybackMarkParser.snapshot(authenticatedHtml(target.pageUrl), target.seasonId, target.pageUrl)
        target.takeIf { snapshot.mark != null || snapshot.status != null }
    }

    suspend fun removeListMark(target: AccountListMark, ownerVersion: Long) = withContext(Dispatchers.IO) { mutex.withLock {
        val expected = session.value
        if (expected.user == null) throw SessionExpiredException()
        if (expected.version != ownerVersion) throw kotlinx.coroutines.CancellationException()
        val jar = sessions.current()
        suspend fun read(): PlaybackMarkSnapshot {
            val html = transport.get(target.pageUrl, jar)
            currentCoroutineContext().ensureActive()
            if (session.value.version != expected.version) throw kotlinx.coroutines.CancellationException()
            if (AccountParser.user(html)?.id != expected.user.id) {
                clear(expired = true)
                throw SessionExpiredException()
            }
            return PlaybackMarkParser.snapshot(html, target.seasonId, target.pageUrl)
        }
        val before = read()
        if (before.mark != null || before.status != null) {
            val response = transport.removeMark(target, jar)
            currentCoroutineContext().ensureActive()
            if (session.value.version != expected.version) throw kotlinx.coroutines.CancellationException()
            try { PlaybackMarkParser.requireSuccess(response) }
            catch (error: MarkSaveRejectedException) { read(); throw error }
            val after = read()
            if (after.mark != null || after.status != null) throw MarkVerificationException()
        }
        mutableMarksRevision.value++
    } }

    private suspend fun authenticatedHtml(url: String): String {
        val expected = session.value
        if (expected.user == null) throw SessionExpiredException()
        val html = transport.get(url, sessions.current())
        currentCoroutineContext().ensureActive()
        if (session.value.version != expected.version) throw kotlinx.coroutines.CancellationException()
        if (AccountParser.user(html)?.id != expected.user.id) {
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
