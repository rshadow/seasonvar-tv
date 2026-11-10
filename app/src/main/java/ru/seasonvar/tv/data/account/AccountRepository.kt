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
import ru.seasonvar.tv.data.cache.*

interface AccountTransport {
    suspend fun clearHistory(jar: SessionCookies): Unit = throw UnsupportedOperationException()
    suspend fun filters(filters: PersonalListFilters, jar: SessionCookies): String = throw UnsupportedOperationException()
    suspend fun get(url: String, jar: SessionCookies): String
    suspend fun login(login: String, password: String, jar: SessionCookies): String
    suspend fun removeMark(mark: AccountListMark, jar: SessionCookies): String = throw UnsupportedOperationException()
    suspend fun saveStatus(change: AccountStatusChange, jar: SessionCookies): String = throw UnsupportedOperationException()
    suspend fun saveMark(mark: PlaybackMark, jar: SessionCookies): String
    suspend fun saveHistory(event: ru.seasonvar.tv.data.playback.PlaybackHistoryEvent, jar: SessionCookies): Unit = throw UnsupportedOperationException()
}

data class AccountSession(val user: AccountUser? = null, val version: Long = 0, val expired: Boolean = false)

class AccountRepository(private val sessions: SessionManager, private val transport: AccountTransport,
    private val htmlCache: HtmlResponseCache? = null, private val cardData: CardDataCache? = null) {
    constructor(sessions: SessionManager, client: SiteClient, htmlCache: HtmlResponseCache? = null,
        cardData: CardDataCache? = null) : this(sessions, object : AccountTransport {
        override suspend fun clearHistory(jar: SessionCookies) = client.accountClearHistory(jar)
        override suspend fun filters(filters: PersonalListFilters, jar: SessionCookies) = client.accountFilters(filters, jar)
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
        override suspend fun saveHistory(event: ru.seasonvar.tv.data.playback.PlaybackHistoryEvent, jar: SessionCookies) = client.accountHistory(event, jar)
    }, htmlCache, cardData)
    private val mutableSession = MutableStateFlow(AccountSession())
    val session = mutableSession.asStateFlow()
    private val mutex = Mutex()
    private val mutableMarksRevision = MutableStateFlow(0L)
    val marksRevision = mutableMarksRevision.asStateFlow()
    private val mutableHistoryRevision = MutableStateFlow(0L)
    val historyRevision = mutableHistoryRevision.asStateFlow()
    private val mutableHistoryReset = MutableStateFlow(0L)
    val historyReset = mutableHistoryReset.asStateFlow()

    suspend fun clearHistory(ownerVersion: Long): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        val expected = session.value
        if (expected.user == null) throw SessionExpiredException()
        if (expected.version != ownerVersion) throw kotlinx.coroutines.CancellationException()
        val jar = sessions.current()
        suspend fun read(): AccountPage {
            val html = transport.get(PersonalFeed.HISTORY.url, jar)
            currentCoroutineContext().ensureActive()
            if (session.value.version != expected.version) throw kotlinx.coroutines.CancellationException()
            if (AccountParser.user(html)?.id != expected.user.id) {
                clear(expired = true)
                throw SessionExpiredException()
            }
            return HistoryParser.parse(html)
        }
        val before = read()
        invalidateHistoryCache()
        mutableHistoryReset.value++
        try {
            if (before.entries.isNotEmpty() || before.nextPage != null) {
                transport.clearHistory(jar)
                currentCoroutineContext().ensureActive()
                val after = read()
                if (after.entries.isNotEmpty() || after.nextPage != null) throw ru.seasonvar.tv.data.MarkupChangedException()
            }
        } finally {
            if (session.value.version == expected.version) { invalidateHistoryCache(); mutableHistoryRevision.value++ }
        }
    } }

    suspend fun recordHistory(event: ru.seasonvar.tv.data.playback.PlaybackHistoryEvent, ownerVersion: Long,
        expectedReset: Long = historyReset.value): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        val expected = session.value
        if (expected.user == null) throw SessionExpiredException()
        if (expected.version != ownerVersion) throw kotlinx.coroutines.CancellationException()
        if (historyReset.value != expectedReset) return@withLock
        transport.saveHistory(event, sessions.current())
        currentCoroutineContext().ensureActive()
        if (session.value.version != expected.version) throw kotlinx.coroutines.CancellationException()
        invalidateHistoryCache()
        mutableHistoryRevision.value++
    } }

    suspend fun restore(): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        if (!sessions.hasSession()) return@withLock
        val html = transport.get("https://seasonvar.ru/resume", sessions.current())
        val user = AccountParser.user(html)
        currentCoroutineContext().ensureActive()
        if (user == null) { clear(expired = true); throw SessionExpiredException() }
        sessions.confirmAccount(user.id)
        htmlCache?.let { it.save(personalKey(PersonalFeed.RESUME), html, it.capture()) }
        val version = session.value.version + if (user.id != "session" && session.value.user?.id == user.id) 0 else 1
        mutableSession.value = AccountSession(user, version)
    } }

    suspend fun login(login: String, password: String): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        if (login.isBlank() || password.isEmpty()) throw LoginRejectedException()
        val candidate = sessions.candidate()
        transport.get("https://seasonvar.ru/?mod=login", candidate)
        transport.login(login.trim(), password, candidate)
        val html = transport.get("https://seasonvar.ru/resume", candidate)
        val user = AccountParser.user(html) ?: throw LoginRejectedException()
        currentCoroutineContext().ensureActive()
        sessions.commit(candidate, user.id)
        htmlCache?.let { it.save(personalKey(PersonalFeed.RESUME), html, it.capture()) }
        mutableSession.value = AccountSession(user, session.value.version + 1)
    } }

    suspend fun resume(): AccountPage = personalList(PersonalFeed.RESUME)

    suspend fun bestFilterMetadata(filters: ru.seasonvar.tv.data.BestListFilters): String = withContext(Dispatchers.IO) {
        authenticatedHtml(filters.safe().url())
    }

    suspend fun catalogMetadata(): String = withContext(Dispatchers.IO) { authenticatedHtml("https://seasonvar.ru/") }

    suspend fun personalFilterOptions(): PersonalFilterOptions = withContext(Dispatchers.IO) {
        PersonalFilterParser.options(authenticatedHtml("https://seasonvar.ru/resume"))
    }

    suspend fun filteredPersonalList(feed: PersonalFeed, requested: PersonalListFilters, windowed: Boolean = false): FilteredPersonalPage = withContext(Dispatchers.IO) {
        require(feed != PersonalFeed.HISTORY)
        val expected = session.value
        val lease = htmlCache?.capture()
        val jar = sessions.current()
        val html = authenticatedHtml("https://seasonvar.ru/resume")
        fun valid() { if (session.value.version != expected.version) throw kotlinx.coroutines.CancellationException() }
        valid()
        val options = PersonalFilterParser.options(html)
        val selected = options.normalize(requested)
        if (lease != null) htmlCache.save(personalKey(PersonalFeed.RESUME), html, lease)
        val user = expected.user ?: throw SessionExpiredException()
        val page = if (!selected.active) AccountParser.personalList(html, feed, windowed = windowed) else {
            val response = transport.filters(selected, jar)
            currentCoroutineContext().ensureActive()
            valid()
            try { AccountParser.filteredPersonalList(response, user, feed, windowed).also {
                if (lease != null) htmlCache.save(filterKey(selected), response, lease)
            } }
            catch (error: SessionExpiredException) {
                mutex.withLock { if (session.value.version == expected.version) clear(expired = true) }
                throw error
            } catch (error: ru.seasonvar.tv.data.MarkupChangedException) {
                authenticatedHtml("https://seasonvar.ru/resume")
                throw error
            }
        }
        currentCoroutineContext().ensureActive()
        valid()
        FilteredPersonalPage(page, options, selected)
    }

    suspend fun personalList(feed: PersonalFeed, pageUrl: String? = null, windowed: Boolean = false): AccountPage = withContext(Dispatchers.IO) {
        val lease = htmlCache?.capture()
        val url = if (pageUrl == null) feed.url else {
            require(feed == PersonalFeed.HISTORY)
            HistoryParser.pageUrl(pageUrl) ?: throw ru.seasonvar.tv.data.MarkupChangedException()
        }
        val html = authenticatedHtml(url)
        AccountParser.personalList(html, feed, url, windowed).also {
            if (lease != null) htmlCache.save(personalKey(feed, url), html, lease)
        }
    }

    suspend fun cachedPersonalList(feed: PersonalFeed, pageUrl: String = feed.url, windowed: Boolean = false): AccountPage? = withContext(Dispatchers.IO) {
        val cache = htmlCache ?: return@withContext null
        val expected = session.value
        val owner = expected.user?.id ?: return@withContext null
        val lease = cache.capture()
        val saved = cache.read(personalKey(feed, pageUrl), lease) ?: return@withContext null
        runCatching {
            require(AccountParser.user(saved.html)?.id == owner)
            AccountParser.personalList(saved.html, feed, pageUrl, windowed)
        }.getOrNull()?.takeIf { session.value.version == expected.version && cache.isCurrent(lease) }
    }

    suspend fun cachedFilteredPersonalList(feed: PersonalFeed, requested: PersonalListFilters, windowed: Boolean = false): FilteredPersonalPage? =
        withContext(Dispatchers.IO) {
            val cache = htmlCache ?: return@withContext null
            val expected = session.value
            val user = expected.user ?: return@withContext null
            val lease = cache.capture()
            val metadata = cache.read(personalKey(PersonalFeed.RESUME), lease) ?: return@withContext null
            runCatching {
                require(AccountParser.user(metadata.html)?.id == user.id)
                val options = PersonalFilterParser.options(metadata.html)
                val selected = options.normalize(requested)
                require(selected == requested)
                val page = if (!selected.active) AccountParser.personalList(metadata.html, feed, windowed = windowed) else {
                    val response = cache.read(filterKey(selected), lease) ?: return@runCatching null
                    AccountParser.filteredPersonalList(response.html, user, feed, windowed)
                }
                FilteredPersonalPage(page, options, selected)
            }.getOrNull()?.takeIf { session.value.version == expected.version && cache.isCurrent(lease) }
        }

    private fun personalKey(feed: PersonalFeed, url: String = feed.url) = HtmlResponseCache.requestKey(
        if (feed == PersonalFeed.HISTORY) "history-" else "personal-", "GET", url)
    private fun filterKey(filters: PersonalListFilters) = HtmlResponseCache.requestKey(
        "personal-filter-", "POST", "https://seasonvar.ru/ajax.php", filters.form())

    private fun invalidateHistoryCache() { htmlCache?.invalidate("history-") }
    private fun invalidateMarksCache(seasonId: String) {
        htmlCache?.invalidate("personal-")
        htmlCache?.invalidate("series-$seasonId.html")
        cardData?.clearPrivate(cancelLoads = false)
    }

    internal suspend fun seasonPage(entry: ru.seasonvar.tv.data.SeriesEntry): String = authenticatedHtml(entry.url)

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
        invalidateMarksCache(mark.seasonId)
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
        invalidateMarksCache(target.seasonId)
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
        invalidateMarksCache(target.seasonId)
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
