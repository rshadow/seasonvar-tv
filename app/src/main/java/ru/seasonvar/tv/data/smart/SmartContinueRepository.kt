package ru.seasonvar.tv.data.smart

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.AccountPage
import ru.seasonvar.tv.data.account.AccountListMark
import ru.seasonvar.tv.data.account.PlaybackMarkSnapshot

data class MarkedSeason(val details: SeriesDetails, val snapshot: PlaybackMarkSnapshot)
data class SmartContinuePage(val entries: List<SeriesEntry>, val resolvedKeys: Set<String>, val incomplete: Boolean, val failure: Exception? = null, val failedSeasons: List<Pair<String, String>> = emptyList())

class SmartContinueRepository(
    private val loadMarks: suspend () -> List<SeriesEntry>,
    private val loadSeason: suspend (SeriesEntry) -> MarkedSeason,
    private val preferences: () -> List<String> = { emptyList() },
    private val loadHistoryPage: suspend (String?) -> AccountPage? = { null },
) {
    constructor(account: ru.seasonvar.tv.data.account.AccountRepository, preferences: () -> List<String>) : this(
        { account.resume().entries },
        { entry ->
            val html = account.seasonPage(entry)
            MarkedSeason(SeriesDetailsParser.parse(html, entry),
                ru.seasonvar.tv.data.account.PlaybackMarkParser.snapshot(html, entry.id, entry.url))
        }, preferences,
        { next -> account.personalList(ru.seasonvar.tv.data.account.PersonalFeed.HISTORY, next) },
    )

    suspend fun load(): SmartContinuePage = withContext(Dispatchers.Default) {
        val permits = Semaphore(4)
        val marksJob = async { permits.withPermit { loadMarks().distinctBy { it.id } } }
        var historyJob = async { permits.withPermit { loadHistoryPage(null) } }
        val marks = marksJob.await()
        if (marks.isEmpty()) {
            historyJob.cancel()
            return@withContext SmartContinuePage(emptyList(), emptySet(), false)
        }

        val pagesMutex = Mutex()
        val pages = mutableMapOf<String, Deferred<Result<MarkedSeason>>>()
        suspend fun page(entry: SeriesEntry): MarkedSeason {
            val job = pagesMutex.withLock {
                pages.getOrPut(entry.id) { async { attempt { permits.withPermit { loadSeason(entry) } } } }
            }
            return job.await().getOrThrow()
        }
        val groupsMutex = Mutex()
        val groups = mutableMapOf<String, Deferred<Result<SeriesEntry?>>>()
        data class Discovery(val id: String, val metadata: SeriesDetails?, val result: Result<SeriesEntry?>)
        val history = mutableListOf<SeriesEntry>()
        val queued = mutableSetOf<String>()
        val visitedPages = mutableSetOf<String>()
        val jobs = mutableListOf<Deferred<Discovery>>()
        while (true) {
            val historyPage = historyJob.await()
            if (historyPage == null) {
                if (visitedPages.isNotEmpty()) throw MarkupChangedException()
                break
            }
            history += historyPage.entries
            val next = historyPage.nextPage
            if (next != null) {
                if (!visitedPages.add(next)) throw MarkupChangedException()
                historyJob = async { permits.withPermit { loadHistoryPage(next) } }
            }
            for (seed in historyPage.entries) if (queued.add(seed.id)) jobs += async {
                var metadata: SeriesDetails? = null
                val result = attempt {
                    val discovered = page(seed)
                    metadata = discovered.details
                    val job = groupsMutex.withLock {
                        groups.getOrPut(discovered.details.serialId) {
                            async { attempt { resolveGroup(discovered, marks, ::page) } }
                        }
                    }
                    job.await().getOrThrow()
                }
                Discovery(seed.id, metadata, result)
            }
            if (next == null) break
        }
        val discoveries = jobs.awaitAll()

        val owners = mutableMapOf<String, String>()
        for (discovery in discoveries) discovery.metadata?.let { details ->
            for (id in details.seasons.map { it.id } + details.entry.id) {
                val key = "smart:" + details.serialId
                val previous = owners.put(id, key)
                if (previous != null && previous != key) throw MarkupChangedException()
            }
        }
        val order = history.mapNotNull { owners[it.id] }.distinct()
        val ranks = order.withIndex().associate { it.value to it.index }
        val resolved = discoveries.filter { it.result.isSuccess }.mapNotNull {
            it.metadata?.serialId?.let { id -> "smart:$id" }
        }.toSet()
        val failures = discoveries.filter { it.result.isFailure }
        val entries = discoveries.mapNotNull { it.result.getOrNull() }.distinctBy { it.key }
            .sortedBy { ranks.getValue(it.key) }
        SmartContinuePage(entries, resolved, failures.isNotEmpty(),
            failures.firstOrNull()?.result?.exceptionOrNull() as? Exception,
            failures.map { it.id to it.result.exceptionOrNull()!!.javaClass.simpleName })
    }

    private suspend fun resolveGroup(seed: MarkedSeason, marks: List<SeriesEntry>,
        readSeason: suspend (SeriesEntry) -> MarkedSeason): SeriesEntry? {
        val details = seed.details
        val options = details.seasons.associateBy { it.id }
        val candidates = marks.filter { it.id in options || it.id == details.entry.id }.sortedByDescending {
            options[it.id]?.number ?: it.season ?: details.seasons.indexOfFirst { option -> option.id == it.id }
        }
        for (candidate in candidates) {
            val marked = readSeason(candidate)
            if (marked.details.serialId != details.serialId) throw MarkupChangedException()
            if (marked.snapshot.mark != null || marked.snapshot.status == -2) {
                return resolve(marked, emptySet(), readSeason)
            }
        }
        return null
    }

    internal suspend fun resolve(marked: MarkedSeason): SeriesEntry? = resolve(marked, emptySet(), loadSeason)

    private suspend fun resolve(marked: MarkedSeason, visited: Set<String>, readSeason: suspend (SeriesEntry) -> MarkedSeason): SeriesEntry? {
        val details = marked.details
        if (details.entry.id in visited) throw MarkupChangedException()
        val snapshot = marked.snapshot
        if (snapshot.status in setOf(-1, -3)) return null
        val mark = snapshot.mark
        if (mark == null && snapshot.status != -2) return null
        if (mark != null && details.playbackUnavailableReason != null && mark.positionSeconds > 0) return null
        if (mark != null && details.playbackUnavailableReason == null) {
            val hint = EntrySelection(episodeId = mark.episode + "_seriya", episodeLabel = mark.episode, translationId = mark.translationId)
            val selected = EntrySelectionResolver.resolve(details, hint, null, preferences())
            val voice = details.translations.firstOrNull { it.id == selected.translationId } ?: return null
            val episodes = voice.episodes ?: throw MarkupChangedException()
            if (selected.failure != null || selected.episodeId == null) return null
            val index = episodes.indexOfFirst { it.id == selected.episodeId }
            if (mark.positionSeconds > 0) return entry(details, voice, episodes[index], mark.positionSeconds * 1000, true)
            episodes.getOrNull(index + 1)?.let { return entry(details, voice, it, 0, true) }
        }
        val ordered = details.seasons.sortedWith(compareBy<SeasonOption> { it.number ?: Int.MAX_VALUE })
        val index = ordered.indexOfFirst { it.id == details.entry.id }
        if (index < 0) throw MarkupChangedException()
        val voiceName = mark?.translationId?.let { id -> details.translations.firstOrNull { it.id == id }?.label }
        for (next in ordered.drop(index + 1)) {
            val target = details.entry.copy(id = next.id, url = next.url, season = next.number,
                accountMark = null, accountProgress = null, accountListMark = null, initialSelection = EntrySelection())
            val nextMarked = readSeason(target)
            val nextDetails = nextMarked.details
            if (nextDetails.serialId != details.serialId) throw MarkupChangedException()
            if (nextMarked.snapshot.status == -3) return null
            if (nextMarked.snapshot.status == -2) continue
            if (nextMarked.snapshot.mark != null) return resolve(nextMarked, visited + details.entry.id, readSeason)
            if (nextDetails.playbackUnavailableReason != null) continue
            val selected = EntrySelectionResolver.resolve(nextDetails, EntrySelection(), null, listOfNotNull(voiceName) + preferences())
            val voice = nextDetails.translations.firstOrNull { it.id == selected.translationId && it.id != "68" } ?: continue
            val episode = voice.episodes?.firstOrNull() ?: continue
            return entry(nextDetails, voice, episode, 0, false)
        }
        return null
    }

    private fun entry(details: SeriesDetails, voice: Translation, episode: Episode, position: Long, marked: Boolean): SeriesEntry {
        val number = details.seasons.firstOrNull { it.id == details.entry.id }?.number ?: details.entry.season
        return details.entry.copy(season = number, sourceKey = "smart:" + details.serialId,
            update = listOfNotNull(number?.let { "Сезон $it" }, "Серия " + episode.label).joinToString(" · "),
            accountMark = null, accountProgress = null, historyVisitedAt = null,
            accountListMark = if (marked) AccountListMark(details.entry.id, details.entry.url) else null,
            initialSelection = EntrySelection(episode.id, episode.label, voice.id, voice.label, position))
    }
}

private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try { Result.success(block()) }
catch (error: CancellationException) { throw error }
catch (error: Exception) { Result.failure(error) }
