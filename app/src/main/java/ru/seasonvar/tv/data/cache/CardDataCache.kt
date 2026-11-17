package ru.seasonvar.tv.data.cache

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.seasonvar.tv.data.account.PlaybackMarkSnapshot
import org.jsoup.Jsoup
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.settings.CacheSettings

data class CardPrivateData(
    val scope: CacheScope?,
    val defaultTranslationId: String?,
    val playbackUnavailableReason: String?,
    val mark: AccountMark?,
    val progress: AccountProgress?,
    val listMark: ru.seasonvar.tv.data.account.AccountListMark?,
    val snapshot: PlaybackMarkSnapshot? = null,
    val pageOwner: String? = null,
)

data class CardData(
    val description: SeriesDescription,
    val details: SeriesDetails?,
    val savedAt: Long,
    val privateData: CardPrivateData?,
)

class CardDataCache(
    private val htmlCache: HtmlResponseCache,
    private val capacity: Int = CacheSettings.CARD_DATA_CAPACITY,
    private val now: () -> Long = System::currentTimeMillis,
    private val workers: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val cpuDispatcher: CoroutineDispatcher = ru.seasonvar.tv.data.work.BackgroundCpu.dispatcher,
) {
    private data class LoadKey(val season: String, val lease: CacheLease, val force: Boolean)
    private class Flight(val job: Deferred<CardData>, var readers: Int = 0)
    private val monitor = Any()
    private class Record(var data: CardData? = null) {
        val mark = MutableStateFlow<CardMarkChange?>(null)
        val hasLocalChange get() = (mark.value?.revision ?: 0) > 0
    }
    private val values = LinkedHashMap<String, Record>(16, 0.75f, true)
    private val flights = mutableMapOf<LoadKey, Flight>()
    private val pins = mutableMapOf<Any, Set<String>>()
    private val workerScope = CoroutineScope(workers.coroutineContext + SupervisorJob(workers.coroutineContext[Job]))
    private var privateRevision = 0L
    private var dataCount = 0
    init { require(capacity > 0) }

    val size get() = synchronized(monitor) { dataCount }
    internal fun diagnosticCounts(): IntArray = synchronized(monitor) {
        intArrayOf(values.size, dataCount, pins.size, pins.values.flatten().toSet().size, flights.size)
    }
    fun description(id: String): SeriesDescription? = synchronized(monitor) { fresh(id)?.description }

    fun details(entry: SeriesEntry, lease: CacheLease): SeriesDetails? = synchronized(monitor) {
        val value = fresh(entry.id) ?: return@synchronized null
        val privateData = value.privateData ?: return@synchronized null
        if (privateData.scope != lease.scope || !htmlCache.isCurrent(lease)) return@synchronized null
        contextual(value, entry)
    }

    fun knownDetails(id: String): SeriesDetails? = synchronized(monitor) { fresh(id)?.details }
    fun knownEntry(id: String): SeriesEntry? = knownDetails(id)?.entry

    fun mark(id: String): CardMarkChange? = synchronized(monitor) {
        values[id]?.mark?.value?.takeIf { htmlCache.isCurrent(CacheLease(it.scope, 0)) }
    }

    fun observeMark(id: String): Flow<CardMarkChange?> = flow {
        val observer = CardCacheDiagnostics.current()
        val cpu = if (observer != null) android.os.Debug.threadCpuTimeNanos() else 0L
        val owner = Any()
        val state = synchronized(monitor) {
            pins[owner] = setOf(id)
            values.getOrPut(id) { Record() }.mark.also { evict() }
        }
        observer?.subscription(true, android.os.Debug.threadCpuTimeNanos() - cpu)
        try { emitAll(state.map { change -> change?.takeIf { htmlCache.isCurrent(CacheLease(it.scope, 0)) } }.distinctUntilChanged()) }
        finally {
            val closing = CardCacheDiagnostics.current()
            val start = if (closing != null) android.os.Debug.threadCpuTimeNanos() else 0L
            retain(owner, emptySet())
            closing?.subscription(false, android.os.Debug.threadCpuTimeNanos() - start)
        }
    }

    fun applyMark(entry: SeriesEntry, snapshot: PlaybackMarkSnapshot, revision: Long): CardMarkChange {
        val lease = htmlCache.capture()
        return synchronized(monitor) {
            val record = values.getOrPut(entry.id) { Record() }
            val change = CardMarkChange(commonEntry(entry).copy(previewDescription = null), snapshot, revision, lease.scope,
                episodeTotal(record.data?.details, snapshot))
            record.mark.value = change
            evict()
            change
        }
    }

    fun changesSince(revision: Long): List<CardMarkChange> = synchronized(monitor) {
        values.values.mapNotNull { it.mark.value?.takeIf { mark -> mark.revision > revision && htmlCache.isCurrent(CacheLease(mark.scope, 0)) } }.sortedBy { it.revision }
    }

    fun retain(owner: Any, ids: Set<String>) = synchronized(monitor) {
        val previous = if (ids.isEmpty()) pins.remove(owner) else pins.put(owner, ids)
        for (id in previous.orEmpty() - ids) {
            val record = values[id]
            if (record?.data == null && record?.hasLocalChange != true && pins.values.none { id in it }) values.remove(id)
        }
        evict()
    }

    private fun parse(entry: SeriesEntry, response: CachedHtml, lease: CacheLease, previousMark: CardMarkChange?): CardData {
        val doc = Jsoup.parse(response.html, entry.url)
        val description = SeasonvarParser.description(doc, entry)
        val pageOwner = runCatching { ru.seasonvar.tv.data.account.AccountParser.user(doc)?.id }.getOrNull()
        if (lease.scope?.private == true && doc.selectFirst(".headblock-menu") != null && pageOwner != lease.scope.owner)
            throw ru.seasonvar.tv.data.account.SessionExpiredException()
        val details = runCatching { SeriesDetailsParser.parse(doc, entry) }.getOrNull()
        val serverMark = if ((!response.fromDisk || previousMark == null) && lease.scope?.private == true)
            runCatching { ru.seasonvar.tv.data.account.PlaybackMarkParser.snapshot(doc, entry.id, entry.url) }.getOrNull()
            else null
        val commonDetails = details?.copy(entry = commonEntry(details.entry), defaultTranslationId = null,
            playbackUnavailableReason = null)
        val privateData = CardPrivateData(lease.scope, details?.defaultTranslationId,
            details?.playbackUnavailableReason, entry.accountMark, entry.accountProgress, entry.accountListMark,
            serverMark, pageOwner)
        return CardData(description, commonDetails, response.savedAt, privateData)
    }

    fun clearPrivate(cancelLoads: Boolean = true) = synchronized(monitor) {
        privateRevision++
        for (record in values.values) {
            record.data = record.data?.copy(privateData = null)
            record.mark.value = null
        }
        val held = pins.values.flatten().toSet()
        values.entries.removeAll { it.value.data == null && it.key !in held }
        if (cancelLoads) for ((key, flight) in flights.toMap()) if (key.lease.scope?.private == true) {
            flights.remove(key); flight.job.cancel()
        }
    }

    suspend fun load(entry: SeriesEntry, lease: CacheLease, force: Boolean,
        fetch: suspend (Boolean) -> CachedHtml, persist: (CachedHtml) -> Unit = {}, needDetails: Boolean = false): CardData {
        val key = LoadKey(entry.id, lease, force)
        val flight = synchronized(monitor) {
            if (!htmlCache.isCurrent(lease)) throw CancellationException("Card cache scope changed")
            if (!force) fresh(entry.id)?.takeIf {
                it.privateData != null && it.privateData.scope == lease.scope && (!needDetails || it.details != null)
            }?.let { return it }
            flights.getOrPut(key) {
                val version = privateRevision
                val previousMark = values[entry.id]?.mark?.value
                Flight(workerScope.async(start = CoroutineStart.LAZY) {
                    var response = fetch(false)
                    currentCoroutineContext().ensureActive()
                    val parsed = try {
                        withContext(cpuDispatcher) { parse(entry, response, lease, previousMark) }
                    } catch (error: MarkupChangedException) {
                        if (!response.fromDisk) throw error
                        response = fetch(true)
                        currentCoroutineContext().ensureActive()
                        withContext(cpuDispatcher) { parse(entry, response, lease, previousMark) }
                    }
                    currentCoroutineContext().ensureActive()
                    if (!htmlCache.isCurrent(lease)) throw CancellationException("Card cache scope changed")
                    persist(response)
                    synchronized(monitor) {
                        if (!htmlCache.isCurrent(lease)) throw CancellationException("Card cache scope changed")
                        val result = parsed.copy(details = parsed.details ?: fresh(entry.id)?.details,
                            privateData = parsed.privateData.takeIf { privateRevision == version })
                        val serverMark = parsed.privateData?.snapshot
                        if (lease.scope != null) {
                            val record = values.getOrPut(entry.id) { Record() }
                            if (record.data == null) dataCount++
                            record.data = result
                            if (serverMark != null && privateRevision == version && record.mark.value === previousMark)
                                record.mark.value = CardMarkChange(commonEntry(entry).copy(previewDescription = null), serverMark, previousMark?.revision ?: 0, lease.scope, episodeTotal(parsed.details, serverMark))
                            evict()
                        }
                        result
                    }
                })
            }.also { it.readers++ }
        }
        flight.job.start()
        try { return flight.job.await() }
        finally { synchronized(monitor) {
            flight.readers--
            if (flight.readers == 0 && flights[key] === flight) {
                flights.remove(key)
                if (!flight.job.isCompleted) flight.job.cancel()
            }
        } }
    }

    fun contextual(value: CardData, entry: SeriesEntry): SeriesDetails? {
        val details = value.details ?: return null
        val private = value.privateData
        val contextual = entry.copy(
            originalTitle = details.entry.originalTitle ?: entry.originalTitle,
            season = details.entry.season ?: entry.season,
            posterUrl = details.entry.posterUrl.ifBlank { entry.posterUrl },
            accountMark = entry.accountMark ?: private?.mark,
            accountProgress = entry.accountProgress ?: private?.progress,
            accountListMark = entry.accountListMark ?: private?.listMark,
        )
        return details.copy(entry = mark(entry.id)?.apply(contextual, details) ?: contextual,
            defaultTranslationId = private?.defaultTranslationId,
            playbackUnavailableReason = private?.playbackUnavailableReason)
    }

    fun entry(entry: SeriesEntry): SeriesEntry {
        val lease = htmlCache.capture()
        return synchronized(monitor) {
            val value = fresh(entry.id)
            val contextual = value?.let { contextual(it.copy(privateData = it.privateData?.takeIf { data -> data.scope == lease.scope }), entry)?.entry } ?: entry
            mark(entry.id)?.apply(contextual, value?.details) ?: contextual
        }
    }

    private fun fresh(id: String): CardData? {
        val record = values[id] ?: return null
        val value = record.data ?: return null
        if (now() - value.savedAt !in 0 until HtmlResponseCache.DAY) {
            record.data = null
            dataCount--
            if (!record.hasLocalChange && pins.values.none { id in it }) values.remove(id)
            return null
        }
        return value
    }

    private fun evict() {
        if (dataCount <= capacity) return
        val held = pins.values.flatten().toSet()
        val iterator = values.entries.iterator()
        while (dataCount > capacity && iterator.hasNext()) {
            val record = iterator.next()
            if (record.key !in held && record.value.data != null) {
                record.value.data = null
                dataCount--
                if (!record.value.hasLocalChange) iterator.remove()
            }
        }
    }

    private fun episodeTotal(details: SeriesDetails?, snapshot: PlaybackMarkSnapshot): Int? =
        (details?.translations?.firstOrNull { it.id == snapshot.mark?.translationId }?.episodes?.size
            ?: details?.translations?.firstOrNull { it.id == "0" }?.episodes?.size)?.takeIf { it > 0 }

    private fun commonEntry(entry: SeriesEntry) = entry.copy(update = "", sourceKey = null, accountMark = null,
        accountProgress = null, historyVisitedAt = null, initialSelection = EntrySelection(), accountListMark = null,
        isWatching = false, isNew = false)
}
