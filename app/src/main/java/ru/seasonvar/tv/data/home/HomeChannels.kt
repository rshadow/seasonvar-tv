package ru.seasonvar.tv.data.home

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.PersonalFeed
import ru.seasonvar.tv.data.account.PlaybackMarkParser
import ru.seasonvar.tv.data.smart.MarkedSeason
import ru.seasonvar.tv.data.smart.SmartContinueRepository
import java.time.LocalDate

class HomeChannels(private val app: SeasonvarApplication, private val scope: CoroutineScope) {
    val store = HomeStore(app)
    val publisher = HomePublisher(app, store)
    val usesLauncherSettings get() = publisher.supported && !publisher.canRequestBrowsing
    private fun selectedChannels() = if (usesLauncherSettings) HomeChannel.entries.toSet() else store.enabled.value
    private val mutex = Mutex()
    private val restoreMutex = Mutex()
    private val requests = MutableStateFlow(0L)
    private val mutableFailure = MutableStateFlow(false)
    val failure = mutableFailure.asStateFlow()
    private val mutableRefreshing = MutableStateFlow(false)
    val refreshing = mutableRefreshing.asStateFlow()

    fun start() {
        scope.launch {
            combine(store.enabled, app.account.session, app.account.marksRevision,
                app.interfaceSettings.preferredTranslations, requests) { _, _, _, _, _ -> Unit }
                .collectLatest {
                    try { schedule(); refresh() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        mutableFailure.value = true
                        Log.w("SeasonvarHome", "refresh_failed reason=" + error.javaClass.simpleName)
                    }
                }
        }
    }
    fun requestRefresh() { requests.value += 1 }
    private fun schedule() {
        val scheduler = app.getSystemService(JobScheduler::class.java)
        if (selectedChannels().isEmpty()) scheduler.cancel(JOB_ID)
        else if (scheduler.getPendingJob(JOB_ID) == null) scheduler.schedule(JobInfo.Builder(JOB_ID,
            ComponentName(app, HomeRefreshJob::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(6 * 60 * 60 * 1000L).setPersisted(true).build())
    }
    suspend fun enable(channel: HomeChannel): Long = withContext(Dispatchers.IO) { mutex.withLock {
        check(!channel.personal || app.account.session.value.user != null)
        val id = publisher.prepare(channel)
        store.setEnabled(channel, true)
        id
    } }
    fun disable(channel: HomeChannel) { store.setEnabled(channel, false) }

    suspend fun refresh() = withContext(Dispatchers.IO) { mutex.withLock {
        if (!publisher.supported) return@withLock
        if (usesLauncherSettings) publisher.registerForLauncher()
        val enabled = selectedChannels()
        HomeChannel.entries.filter { it !in enabled }.forEach(publisher::clear)
        val needsAccount = enabled.any { it.personal }
        if (needsAccount) restoreAccountIfNeeded()
        val session = app.account.session.value
        val owner = session.user?.id
        HomeChannel.entries.filter { it.personal }.forEach { channel ->
            if (owner == null || publisherCardOwnerChanged(channel, owner)) publisher.clear(channel)
        }
        if (enabled.isEmpty()) return@withLock
        mutableRefreshing.value = true
        mutableFailure.value = false
        try {
            for (channel in enabled) {
                if (channel.personal && owner == null) continue
                try {
                    val entries = when (channel) {
                        HomeChannel.CONTINUE -> {
                            val page = SmartContinueRepository(app.account, { app.interfaceSettings.preferredTranslations.value }).load()
                            if (page.incomplete) throw page.failure ?: java.io.IOException("Incomplete feed")
                            page.entries
                        }
                        HomeChannel.WANT -> app.account.personalList(PersonalFeed.WANT).entries
                        HomeChannel.NEWEST -> app.repository.load(LocalDate.now(), PublicFeed.NEWEST).entries
                    }
                    ensureActive()
                    fun valid() = selectedChannels() == enabled && app.account.session.value == session
                    if (!valid()) throw CancellationException()
                    publisher.publish(channel, entries, owner, ::valid)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    mutableFailure.value = true
                    Log.w("SeasonvarHome", "refresh_failed channel=" + channel.storageId + " reason=" + error.javaClass.simpleName)
                }
            }
            publisher.prunePosters()
        } finally { mutableRefreshing.value = false }
    } }
    private fun publisherCardOwnerChanged(channel: HomeChannel, owner: String) = store.cards(channel).values.any { it.owner != owner }

    suspend fun open(token: String): ru.seasonvar.tv.data.playback.PlaybackRequest = withContext(Dispatchers.IO) {
        val card = store.read(token) ?: throw HomePlaybackUnavailable()
        if (card.channel !in selectedChannels() || token !in publisher.programs(card.channel)) throw HomePlaybackUnavailable()
        restoreAccountIfNeeded()
        val session = app.account.session.value
        if (card.channel.personal && (card.owner == null || card.owner != session.user?.id)) throw HomePlaybackUnavailable()
        val request = resolver().resolve(card.entry)
        ensureActive()
        if (app.account.session.value != session || card.channel !in selectedChannels() || store.read(token) != card)
            throw HomePlaybackUnavailable()
        request
    }

    private suspend fun restoreAccountIfNeeded() = restoreMutex.withLock {
        if (app.account.session.value.user == null && app.sessions.hasSession()) app.account.restore()
    }

    fun resolver() = HomePlaybackResolver(
        details = { app.repository.details(it, force = true) }, local = app.playbackProgress::latest,
        preferred = { app.interfaceSettings.preferredTranslations.value },
        account = { details ->
            if (app.account.session.value.user == null) null else {
                val ids = details.seasons.map { it.id }.toSet() + details.entry.id
                val marks = app.account.resume().entries.filter { it.id in ids }
                val order = details.seasons.associate { it.id to (it.number ?: 0) }
                val candidates = (marks + details.entry).distinctBy { it.id }.sortedByDescending { order[it.id] ?: it.season ?: 0 }
                var continuation: AccountContinuation? = null
                for (entry in candidates) {
                    val html = app.account.seasonPage(entry)
                    val snapshot = PlaybackMarkParser.snapshot(html, entry.id, entry.url)
                    if (snapshot.status == -3) throw HomePlaybackUnavailable()
                    if (snapshot.mark != null || snapshot.status == -2) {
                        val marked = MarkedSeason(SeriesDetailsParser.parse(html, entry), snapshot)
                        continuation = AccountContinuation(SmartContinueRepository(app.account,
                            { app.interfaceSettings.preferredTranslations.value }).resolve(marked))
                        break
                    }
                }
                continuation
            }
        })
    companion object { const val JOB_ID = 106 }
}
