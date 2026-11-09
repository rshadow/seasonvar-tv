package ru.seasonvar.tv.data.cache

import kotlinx.coroutines.*
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
) {
    private data class LoadKey(val season: String, val lease: CacheLease, val force: Boolean)
    private class Flight(val job: Deferred<CardData>, var readers: Int = 0)
    private val monitor = Any()
    private val values = LinkedHashMap<String, CardData>(16, 0.75f, true)
    private val flights = mutableMapOf<LoadKey, Flight>()
    private val pins = mutableMapOf<Any, Set<String>>()
    private val workerScope = CoroutineScope(workers.coroutineContext + SupervisorJob(workers.coroutineContext[Job]))
    private var privateRevision = 0L
    init { require(capacity > 0) }

    val size get() = synchronized(monitor) { values.size }
    fun description(id: String): SeriesDescription? = synchronized(monitor) { fresh(id)?.description }

    fun details(entry: SeriesEntry, lease: CacheLease): SeriesDetails? = synchronized(monitor) {
        val value = fresh(entry.id) ?: return@synchronized null
        val privateData = value.privateData ?: return@synchronized null
        if (privateData.scope != lease.scope || !htmlCache.isCurrent(lease)) return@synchronized null
        contextual(value, entry)
    }

    fun retain(owner: Any, ids: Set<String>) = synchronized(monitor) {
        if (ids.isEmpty()) pins.remove(owner) else pins[owner] = ids
        evict()
    }

    fun clearPrivate(cancelLoads: Boolean = true) = synchronized(monitor) {
        privateRevision++
        for ((id, value) in values.toMap()) values[id] = value.copy(privateData = null)
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
                Flight(workerScope.async(start = CoroutineStart.LAZY) {
                    var response = fetch(false)
                    currentCoroutineContext().ensureActive()
                    var doc = Jsoup.parse(response.html, entry.url)
                    val description = try { SeasonvarParser.description(doc, entry) }
                    catch (error: MarkupChangedException) {
                        if (!response.fromDisk) throw error
                        response = fetch(true)
                        currentCoroutineContext().ensureActive()
                        doc = Jsoup.parse(response.html, entry.url)
                        SeasonvarParser.description(doc, entry)
                    }
                    val details = runCatching { SeriesDetailsParser.parse(doc, entry) }.getOrNull()
                    val commonDetails = details?.copy(entry = commonEntry(details.entry), defaultTranslationId = null,
                        playbackUnavailableReason = null)
                    val privateData = CardPrivateData(lease.scope, details?.defaultTranslationId,
                        details?.playbackUnavailableReason, entry.accountMark, entry.accountProgress, entry.accountListMark)
                    val parsed = CardData(description, commonDetails, response.savedAt, privateData)
                    currentCoroutineContext().ensureActive()
                    if (!htmlCache.isCurrent(lease)) throw CancellationException("Card cache scope changed")
                    persist(response)
                    synchronized(monitor) {
                        if (!htmlCache.isCurrent(lease)) throw CancellationException("Card cache scope changed")
                        val result = parsed.copy(details = parsed.details ?: fresh(entry.id)?.details,
                            privateData = parsed.privateData.takeIf { privateRevision == version })
                        if (lease.scope != null) { values[entry.id] = result; evict() }
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
        return details.copy(entry = entry.copy(
            originalTitle = details.entry.originalTitle ?: entry.originalTitle,
            season = details.entry.season ?: entry.season,
            posterUrl = details.entry.posterUrl.ifBlank { entry.posterUrl },
            accountMark = entry.accountMark ?: private?.mark,
            accountProgress = entry.accountProgress ?: private?.progress,
            accountListMark = entry.accountListMark ?: private?.listMark,
        ), defaultTranslationId = private?.defaultTranslationId,
            playbackUnavailableReason = private?.playbackUnavailableReason)
    }

    fun entry(entry: SeriesEntry): SeriesEntry {
        val lease = htmlCache.capture()
        return synchronized(monitor) {
            val value = fresh(entry.id) ?: return@synchronized entry
            contextual(value.copy(privateData = value.privateData?.takeIf { it.scope == lease.scope }), entry)?.entry ?: entry
        }
    }

    private fun fresh(id: String): CardData? {
        val value = values[id] ?: return null
        if (now() - value.savedAt !in 0 until HtmlResponseCache.DAY) { values.remove(id); return null }
        return value
    }

    private fun evict() {
        if (values.size <= capacity) return
        val held = pins.values.flatten().toSet()
        val iterator = values.entries.iterator()
        while (values.size > capacity && iterator.hasNext()) {
            if (iterator.next().key !in held) iterator.remove()
        }
    }

    private fun commonEntry(entry: SeriesEntry) = entry.copy(update = "", sourceKey = null, accountMark = null,
        accountProgress = null, historyVisitedAt = null, initialSelection = EntrySelection(), accountListMark = null,
        isWatching = false, isNew = false)
}
