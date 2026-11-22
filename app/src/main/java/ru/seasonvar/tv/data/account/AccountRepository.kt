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
    private val htmlCache: HtmlResponseCache? = null, private val cardData: CardDataCache? = null,
    workers: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO),
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val cpuDispatcher: kotlinx.coroutines.CoroutineDispatcher = ru.seasonvar.tv.data.work.BackgroundCpu.dispatcher) {
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

    private data class ResumeKey(val session: Long, val marks: Long, val generation: Long, val cached: Boolean)
    private val resumeRequests = SharedRequests<ResumeKey, ResumeSnapshot>(workers)
    private val filterRequests = SharedRequests<Pair<ResumeKey, PersonalListFilters>, ResumeSnapshot>(workers)
    private val htmlRequests = SharedRequests<Triple<ResumeKey, Long, String>, String>(workers)
    private val resumeMonitor = Any()
    @Volatile private var readsGeneration = 0L
    val readGeneration get() = readsGeneration
    private var diskSnapshot: Triple<ResumeKey, Long, ResumeSnapshot>? = null

    fun invalidateSharedReads(cancel: Boolean = true) {
        synchronized(resumeMonitor) { readsGeneration++; diskSnapshot = null }
        if (cancel) { resumeRequests.cancel(); filterRequests.cancel(); htmlRequests.cancel() }
    }

    private fun resumeKey(cached: Boolean = false) = ResumeKey(session.value.version, marksRevision.value, readsGeneration, cached)

    private suspend fun resumeData(): ResumeSnapshot {
        val expected = session.value
        if (expected.user == null) throw SessionExpiredException()
        val key = resumeKey()
        val lease = htmlCache?.capture()
        return resumeRequests.get(key) {
            if (resumeKey() != key) throw kotlinx.coroutines.CancellationException()
            val html = transport.get(PersonalFeed.RESUME.url, sessions.current())
            currentCoroutineContext().ensureActive()
            if (resumeKey() != key) throw kotlinx.coroutines.CancellationException()
            val parsed = try {
                withContext(cpuDispatcher) { AccountParser.snapshot(html) }.also { if (it.user.id != expected.user.id) throw SessionExpiredException() }
            } catch (error: SessionExpiredException) {
                mutex.withLock { if (session.value.version == expected.version) clear(expired = true, cancelReads = false) }
                throw error
            }
            if (resumeKey() != key) throw kotlinx.coroutines.CancellationException()
            synchronized(resumeMonitor) { diskSnapshot = null }
            if (lease != null) htmlCache.save(personalKey(PersonalFeed.RESUME), html, lease)
            parsed
        }.also { if (resumeKey() != key) throw kotlinx.coroutines.CancellationException() }
    }

    private suspend fun cachedResumeData(): ResumeSnapshot? {
        val cache = htmlCache ?: return null
        val expected = session.value
        val user = expected.user ?: return null
        val key = resumeKey(cached = true)
        val lease = cache.capture()
        val saved = cache.read(personalKey(PersonalFeed.RESUME), lease) ?: return null
        val ready = synchronized(resumeMonitor) {
            diskSnapshot?.takeIf { it.first == key && it.second == saved.savedAt }?.third
        }
        val parsed = ready ?: runCatching {
            resumeRequests.get(key) {
                val snapshot = withContext(cpuDispatcher) { AccountParser.snapshot(saved.html) }.also { require(it.user.id == user.id) }
                if (resumeKey(cached = true) != key || !cache.isCurrent(lease)) throw kotlinx.coroutines.CancellationException()
                synchronized(resumeMonitor) { diskSnapshot = Triple(key, saved.savedAt, snapshot) }
                snapshot
            }
        }.getOrNull()
        return parsed?.takeIf { resumeKey(cached = true) == key && cache.isCurrent(lease) }
    }

    suspend fun clearHistory(ownerVersion: Long): Unit = withContext(dispatcher) { mutex.withLock {
        val expected = session.value
        if (expected.user == null) throw SessionExpiredException()
        if (expected.version != ownerVersion) throw kotlinx.coroutines.CancellationException()
        val jar = sessions.current()
        suspend fun read(): AccountPage {
            val html = transport.get(PersonalFeed.HISTORY.url, jar)
            currentCoroutineContext().ensureActive()
            if (session.value.version != expected.version) throw kotlinx.coroutines.CancellationException()
            if (withContext(cpuDispatcher) { AccountParser.user(html) }?.id != expected.user.id) {
                clear(expired = true)
                throw SessionExpiredException()
            }
            return withContext(cpuDispatcher) { HistoryParser.parse(html) }
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
        expectedReset: Long = historyReset.value): Unit = withContext(dispatcher) { mutex.withLock {
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

    suspend fun restore(): Unit = withContext(dispatcher) { mutex.withLock {
        if (!sessions.hasSession()) return@withLock
        val html = transport.get("https://seasonvar.ru/resume", sessions.current())
        val user = withContext(cpuDispatcher) { AccountParser.user(html) }
        currentCoroutineContext().ensureActive()
        if (user == null) { clear(expired = true); throw SessionExpiredException() }
        sessions.confirmAccount(user.id)
        synchronized(resumeMonitor) { diskSnapshot = null }
        htmlCache?.let { it.save(personalKey(PersonalFeed.RESUME), html, it.capture()) }
        val version = session.value.version + if (user.id != "session" && session.value.user?.id == user.id) 0 else 1
        if (version != session.value.version) invalidateSharedReads()
        mutableSession.value = AccountSession(user, version)
    } }

    internal class PendingLogin(val jar: SessionCookies, val user: AccountUser, val html: String,
        val version: Long, val failure: SessionStorageException)

    internal suspend fun login(login: String, password: String): PendingLogin? = withContext(dispatcher) { mutex.withLock {
        if (login.isBlank() || password.isEmpty()) throw LoginRejectedException()
        val candidate = sessions.candidate()
        transport.get("https://seasonvar.ru/?mod=login", candidate)
        transport.login(login.trim(), password, candidate)
        val html = transport.get("https://seasonvar.ru/resume", candidate)
        val user = withContext(cpuDispatcher) { AccountParser.user(html) } ?: throw LoginRejectedException()
        currentCoroutineContext().ensureActive()
        try {
            sessions.commit(candidate, user.id)
        } catch (error: SessionStorageException) {
            if (!error.encryptionUnavailable) throw error
            return@withLock PendingLogin(candidate, user, html, session.value.version, error)
        }
        publishLogin(user, html)
        null
    } }

    internal suspend fun finishLogin(login: PendingLogin, remember: Boolean): Unit = withContext(dispatcher) { mutex.withLock {
        if (session.value.version != login.version) return@withLock
        val mode = if (remember) SessionPersistence.UNENCRYPTED else SessionPersistence.MEMORY
        sessions.commit(login.jar, login.user.id, mode)
        publishLogin(login.user, login.html)
    } }

    private fun publishLogin(user: AccountUser, html: String) {
        htmlCache?.let { it.save(personalKey(PersonalFeed.RESUME), html, it.capture()) }
        invalidateSharedReads()
        mutableSession.value = AccountSession(user, session.value.version + 1)
    }

    suspend fun resume(): AccountPage = personalList(PersonalFeed.RESUME)

    suspend fun bestFilterMetadata(filters: ru.seasonvar.tv.data.BestListFilters): String = withContext(dispatcher) {
        authenticatedHtml(filters.safe().url())
    }

    suspend fun catalogMetadata(): String = withContext(dispatcher) { authenticatedHtml("https://seasonvar.ru/") }

    suspend fun personalFilterOptions(): PersonalFilterOptions = withContext(dispatcher) {
        resumeData().options.getOrThrow()
    }

    suspend fun filteredPersonalList(feed: PersonalFeed, requested: PersonalListFilters, windowed: Boolean = false): FilteredPersonalPage = withContext(dispatcher) {
        require(feed != PersonalFeed.HISTORY)
        val expected = session.value
        val lease = htmlCache?.capture()
        val jar = sessions.current()
        val snapshot = resumeData()
        fun valid() { if (session.value.version != expected.version) throw kotlinx.coroutines.CancellationException() }
        valid()
        val options = snapshot.options.getOrThrow()
        val selected = options.normalize(requested)
        val user = expected.user ?: throw SessionExpiredException()
        val page = if (!selected.active) withContext(cpuDispatcher) { snapshot.page(feed, windowed) } else {
            val key = resumeKey()
            val filtered = filterRequests.get(key to selected) {
                if (key != resumeKey()) throw kotlinx.coroutines.CancellationException()
                val response = transport.filters(selected, jar)
                currentCoroutineContext().ensureActive()
                if (key != resumeKey()) throw kotlinx.coroutines.CancellationException()
                try {
                    withContext(cpuDispatcher) { AccountParser.filteredSnapshot(response, user) }.also {
                        if (key != resumeKey()) throw kotlinx.coroutines.CancellationException()
                        if (lease != null) htmlCache.save(filterKey(selected), response, lease)
                    }
                } catch (error: SessionExpiredException) {
                    mutex.withLock { if (session.value.version == expected.version) clear(expired = true, cancelReads = false) }
                    throw error
                } catch (error: ru.seasonvar.tv.data.MarkupChangedException) {
                    authenticatedHtml(PersonalFeed.RESUME.url)
                    throw error
                }
            }
            if (key != resumeKey()) throw kotlinx.coroutines.CancellationException()
            withContext(cpuDispatcher) { filtered.page(feed, windowed) }
        }
        currentCoroutineContext().ensureActive()
        valid()
        FilteredPersonalPage(page, options, selected)
    }

    suspend fun personalList(feed: PersonalFeed, pageUrl: String? = null, windowed: Boolean = false): AccountPage = withContext(dispatcher) {
        val lease = htmlCache?.capture()
        val url = if (pageUrl == null) feed.url else {
            require(feed == PersonalFeed.HISTORY)
            HistoryParser.pageUrl(pageUrl) ?: throw ru.seasonvar.tv.data.MarkupChangedException()
        }
        if (feed != PersonalFeed.HISTORY) return@withContext resumeData().let { withContext(cpuDispatcher) { it.page(feed, windowed) } }
        val html = authenticatedHtml(url)
        withContext(cpuDispatcher) { AccountParser.personalList(html, feed, url, windowed) }.also {
            if (lease != null) htmlCache.save(personalKey(feed, url), html, lease)
        }
    }

    internal suspend fun startupOwner(): String? = withContext(dispatcher) { sessions.startupOwner() }

    internal suspend fun cachedStartupPage(owner: String, feed: PersonalFeed, filters: PersonalListFilters): AccountPage? = withContext(dispatcher) {
        val cache = htmlCache ?: return@withContext null
        if (sessions.startupOwner() != owner) return@withContext null
        val lease = cache.capture()
        val saved = cache.readForStartup(personalKey(feed), lease, owner) ?: return@withContext null
        val filtered = if (filters.active && feed != PersonalFeed.HISTORY) {
            cache.readForStartup(filterKey(filters), lease, owner) ?: return@withContext null
        } else null
        val page = try {
            withContext(cpuDispatcher) {
                if (feed == PersonalFeed.HISTORY) AccountParser.personalList(saved.html, feed)
                else {
                    val snapshot = AccountParser.snapshot(saved.html)
                    if (snapshot.user.id != owner) return@withContext null
                    if (filtered == null) snapshot.page(feed, windowed = false)
                    else if (snapshot.options.getOrThrow().normalize(filters) == filters)
                        AccountParser.filteredPersonalList(filtered.html, snapshot.user, feed)
                    else null
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { null }
        page?.takeIf { it.user.id == owner && sessions.startupOwner() == owner && cache.isCurrent(lease) }
    }

    suspend fun cachedPersonalList(feed: PersonalFeed, pageUrl: String = feed.url, windowed: Boolean = false): AccountPage? = withContext(dispatcher) {
        if (feed != PersonalFeed.HISTORY) return@withContext runCatching { cachedResumeData()?.let { withContext(cpuDispatcher) { it.page(feed, windowed) } } }.getOrNull()
        val cache = htmlCache ?: return@withContext null
        val expected = session.value
        val owner = expected.user?.id ?: return@withContext null
        val lease = cache.capture()
        val saved = cache.read(personalKey(feed, pageUrl), lease) ?: return@withContext null
        runCatching {
            require(withContext(cpuDispatcher) { AccountParser.user(saved.html) }?.id == owner)
            withContext(cpuDispatcher) { AccountParser.personalList(saved.html, feed, pageUrl, windowed) }
        }.getOrNull()?.takeIf { session.value.version == expected.version && cache.isCurrent(lease) }
    }

    suspend fun cachedFilteredPersonalList(feed: PersonalFeed, requested: PersonalListFilters, windowed: Boolean = false): FilteredPersonalPage? =
        withContext(dispatcher) {
            val cache = htmlCache ?: return@withContext null
            val expected = session.value
            val user = expected.user ?: return@withContext null
            val lease = cache.capture()
            val metadata = cachedResumeData() ?: return@withContext null
            runCatching {
                val options = metadata.options.getOrThrow()
                val selected = options.normalize(requested)
                require(selected == requested)
                val page = if (!selected.active) withContext(cpuDispatcher) { metadata.page(feed, windowed) } else {
                    val response = cache.read(filterKey(selected), lease) ?: return@runCatching null
                    withContext(cpuDispatcher) { AccountParser.filteredPersonalList(response.html, user, feed, windowed) }
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
    }

    internal suspend fun seasonPage(entry: ru.seasonvar.tv.data.SeriesEntry): String = authenticatedHtml(entry.url)

    internal suspend fun markedSeason(entry: ru.seasonvar.tv.data.SeriesEntry, repository: ru.seasonvar.tv.data.CatalogRepository): ru.seasonvar.tv.data.smart.MarkedSeason {
        val expected = session.value
        val owner = expected.user?.id ?: throw SessionExpiredException()
        val key = resumeKey()
        val result = try { repository.markedSeason(entry, owner) }
        catch (error: SessionExpiredException) {
            mutex.withLock { if (session.value.version == expected.version) clear(expired = true) }
            throw error
        }
        if (key != resumeKey()) throw kotlinx.coroutines.CancellationException()
        return result
    }

    suspend fun progressSnapshot(): AccountProgressSnapshot? = withContext(dispatcher) {
        if (session.value.user == null) return@withContext null
        resumeData().progress
    }

    suspend fun savePlaybackMark(mark: PlaybackMark, ownerVersion: Long = session.value.version, automatic: Boolean = false): PlaybackMark? =
        writePlaybackMark(mark, ownerVersion) { !automatic || !markSupersedes(it, mark) }

    suspend fun completeEpisode(mark: PlaybackMark, ownerVersion: Long, confirmed: PlaybackMarkSnapshot?) {
        require(mark.positionSeconds == 0L)
        writePlaybackMark(mark, ownerVersion) { before ->
            val previous = before.mark
            val previousNumber = previous?.episode?.toBigDecimalOrNull()
            val selectedNumber = mark.episode.toBigDecimalOrNull()
            val replacesProgress = previous != null && previous.episode != mark.episode &&
                (previousNumber == null || selectedNumber == null || previousNumber >= selectedNumber)
            val replacesStatus = before.status == AccountListStatus.WATCHED.code ||
                before.status == AccountListStatus.NOT_WATCHING.code
            if ((replacesProgress || replacesStatus) && before != confirmed) throw EpisodeMarkConflict(before)
            previous != mark
        }
    }

    private suspend fun writePlaybackMark(mark: PlaybackMark, ownerVersion: Long,
        shouldWrite: (PlaybackMarkSnapshot) -> Boolean): PlaybackMark? = withContext(dispatcher) { mutex.withLock {
        val expected = session.value
        if (expected.user == null) throw SessionExpiredException()
        if (expected.version != ownerVersion) throw kotlinx.coroutines.CancellationException()
        val jar = sessions.current()
        suspend fun read(): String {
            val html = transport.get(mark.pageUrl, jar)
            currentCoroutineContext().ensureActive()
            if (session.value.version != expected.version) throw kotlinx.coroutines.CancellationException()
            if (withContext(cpuDispatcher) { AccountParser.user(html) }?.id != expected.user.id) {
                clear(expired = true)
                throw SessionExpiredException()
            }
            return html
        }
        val before = PlaybackMarkParser.snapshot(read(), mark.seasonId, mark.pageUrl)
        if (!shouldWrite(before)) {
            acceptCurrentMark(mark.seasonId, before)
            return@withLock null
        }
        currentCoroutineContext().ensureActive()
        val response = transport.saveMark(mark, jar)
        currentCoroutineContext().ensureActive()
        if (session.value.version != expected.version) throw kotlinx.coroutines.CancellationException()
        try { PlaybackMarkParser.requireSuccess(response) }
        catch (error: MarkSaveRejectedException) { read(); throw error }
        val entry = cardData?.knownEntry(mark.seasonId) ?: ru.seasonvar.tv.data.SeriesEntry(
            mark.seasonId, mark.pageUrl, "", null, "", "")
        publishMark(entry, PlaybackMarkSnapshot(mark = mark))
        mark
    } }

    suspend fun listStatus(target: AccountListMark): AccountListStatus? = withContext(dispatcher) {
        AccountListStatus.from(PlaybackMarkParser.snapshot(authenticatedHtml(target.pageUrl), target.seasonId, target.pageUrl))
    }

    suspend fun setListStatus(entry: ru.seasonvar.tv.data.SeriesEntry, status: AccountListStatus,
        ownerVersion: Long): PlaybackMarkSnapshot = withContext(dispatcher) { mutex.withLock {
        val target = AccountListMark(entry.id, entry.url)
        val expected = session.value
        if (expected.user == null) throw SessionExpiredException()
        if (expected.version != ownerVersion) throw kotlinx.coroutines.CancellationException()
        val jar = sessions.current()
        suspend fun read(): String {
            val html = transport.get(target.pageUrl, jar)
            currentCoroutineContext().ensureActive()
            if (session.value.version != expected.version) throw kotlinx.coroutines.CancellationException()
            if (withContext(cpuDispatcher) { AccountParser.user(html) }?.id != expected.user.id) {
                clear(expired = true); throw SessionExpiredException()
            }
            return html
        }
        val beforeHtml = read()
        val before = PlaybackMarkParser.snapshot(beforeHtml, target.seasonId, target.pageUrl)
        if (AccountListStatus.from(before) == status) {
            acceptCurrentMark(entry.id, before)
            return@withLock before
        }
        val watching = if (status == AccountListStatus.WATCHING) AccountStatusChange.watchingMark(
            ru.seasonvar.tv.data.SeriesDetailsParser.parse(beforeHtml, entry), entry.initialSelection) else null
        val response = transport.saveStatus(AccountStatusChange(target, status, watching), jar)
        currentCoroutineContext().ensureActive()
        if (session.value.version != expected.version) throw kotlinx.coroutines.CancellationException()
        try { PlaybackMarkParser.requireSuccess(response) }
        catch (error: MarkSaveRejectedException) { read(); throw error }
        val after = PlaybackMarkSnapshot(mark = watching, status = status.code)
        publishMark(entry, after)
        after
    } }

    suspend fun listMark(target: AccountListMark): AccountListMark? = withContext(dispatcher) {
        if (session.value.user == null) return@withContext null
        val snapshot = PlaybackMarkParser.snapshot(authenticatedHtml(target.pageUrl), target.seasonId, target.pageUrl)
        target.takeIf { snapshot.mark != null || snapshot.status != null }
    }

    suspend fun removeListMark(target: AccountListMark, ownerVersion: Long) = withContext(dispatcher) { mutex.withLock {
        val expected = session.value
        if (expected.user == null) throw SessionExpiredException()
        if (expected.version != ownerVersion) throw kotlinx.coroutines.CancellationException()
        val jar = sessions.current()
        suspend fun read(): PlaybackMarkSnapshot {
            val html = transport.get(target.pageUrl, jar)
            currentCoroutineContext().ensureActive()
            if (session.value.version != expected.version) throw kotlinx.coroutines.CancellationException()
            if (withContext(cpuDispatcher) { AccountParser.user(html) }?.id != expected.user.id) {
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
        }
        val entry = cardData?.knownEntry(target.seasonId) ?: ru.seasonvar.tv.data.SeriesEntry(
            target.seasonId, target.pageUrl, "", null, "", "")
        publishMark(entry, PlaybackMarkSnapshot())
    } }

    private fun acceptCurrentMark(id: String, snapshot: PlaybackMarkSnapshot) {
        val previous = cardData?.mark(id) ?: return
        if (previous.snapshot != snapshot) publishMark(cardData.knownEntry(id) ?: previous.entry, snapshot)
    }

    private fun publishMark(entry: ru.seasonvar.tv.data.SeriesEntry, snapshot: PlaybackMarkSnapshot) {
        invalidateMarksCache(entry.id)
        invalidateSharedReads()
        val revision = marksRevision.value + 1
        cardData?.applyMark(entry, snapshot, revision)
        mutableMarksRevision.value = revision
    }

    private suspend fun authenticatedHtml(url: String): String {
        val expected = session.value
        if (expected.user == null) throw SessionExpiredException()
        val key = Triple(resumeKey(), historyRevision.value, url)
        fun valid() = key.first == resumeKey() && key.second == historyRevision.value
        return htmlRequests.get(key) {
            if (!valid()) throw kotlinx.coroutines.CancellationException()
            val html = transport.get(url, sessions.current())
            currentCoroutineContext().ensureActive()
            if (!valid()) throw kotlinx.coroutines.CancellationException()
            if (withContext(cpuDispatcher) { AccountParser.user(html) }?.id != expected.user.id) {
                mutex.withLock { if (session.value.version == expected.version) clear(expired = true, cancelReads = false) }
                throw SessionExpiredException()
            }
            html
        }.also { if (!valid()) throw kotlinx.coroutines.CancellationException() }
    }

    suspend fun logout(): Unit = withContext(dispatcher) { mutex.withLock {
        val previous = try { sessions.current() } catch (_: SessionStorageException) { null }
        clear()
        if (previous == null) return@withLock
        try { transport.get("https://seasonvar.ru/?mod=logout", previous) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { }
    } }

    private fun clear(expired: Boolean = false, cancelReads: Boolean = true) {
        invalidateSharedReads(cancelReads)
        sessions.clear()
        mutableSession.value = AccountSession(version = session.value.version + 1, expired = expired)
    }
}
