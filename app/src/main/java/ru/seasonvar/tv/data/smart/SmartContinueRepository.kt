package ru.seasonvar.tv.data.smart

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.AccountListMark
import ru.seasonvar.tv.data.account.PlaybackMarkSnapshot

data class MarkedSeason(val details: SeriesDetails, val snapshot: PlaybackMarkSnapshot)
data class SmartContinuePage(val entries: List<SeriesEntry>, val resolvedKeys: Set<String>, val incomplete: Boolean, val failure: Exception? = null, val failedSeasons: List<Pair<String, String>> = emptyList())

class SmartContinueRepository(
    private val loadMarks: suspend () -> List<SeriesEntry>,
    private val loadSeason: suspend (SeriesEntry) -> MarkedSeason,
    private val preferences: () -> List<String> = { emptyList() },
    private val historyOrder: suspend (Map<String, Set<String>>) -> List<String> = { emptyList() },
) {
    constructor(account: ru.seasonvar.tv.data.account.AccountRepository, preferences: () -> List<String>) : this(
        { account.resume().entries },
        { entry ->
            val html = account.seasonPage(entry)
            MarkedSeason(SeriesDetailsParser.parse(html, entry),
                ru.seasonvar.tv.data.account.PlaybackMarkParser.snapshot(html, entry.id, entry.url))
        }, preferences,
        { groups -> smartHistoryOrder(groups) { next -> account.personalList(ru.seasonvar.tv.data.account.PersonalFeed.HISTORY, next) } },
    )

    suspend fun load(): SmartContinuePage = withContext(Dispatchers.Default) {
        val entries = loadMarks().distinctBy { it.id }
        val permits = Semaphore(4)
        val loaded = entries.map { entry -> async {
            permits.withPermit {
                try { entry.id to Result.success(loadSeason(entry)) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { entry.id to Result.failure<MarkedSeason>(e) }
            }
        } }.awaitAll()
        val failures = loaded.filter { it.second.isFailure }.map { it.first to ("load_" + it.second.exceptionOrNull()!!.javaClass.simpleName) }.toMutableList()
        val failedIds = loaded.filter { it.second.isFailure }.map { it.first }.toSet()
        val groups = loaded.mapNotNull { it.second.getOrNull() }.groupBy { it.details.serialId }
        val result = mutableListOf<SeriesEntry>()
        val resolved = mutableSetOf<String>()
        val visibleGroups = mutableMapOf<String, Set<String>>()
        var incomplete = failedIds.isNotEmpty()
        var failure = loaded.firstNotNullOfOrNull { it.second.exceptionOrNull() as? Exception }
        for ((serialId, seasons) in groups) {
            val key = "smart:$serialId"
            if (seasons.any { season -> season.details.seasons.any { it.id in failedIds } }) continue
            try {
                val eligible = seasons.filter { it.snapshot.mark != null || it.snapshot.status == -2 }
                if (eligible.isEmpty()) { resolved += key; continue }
                val latest = eligible.maxBy { season -> season.details.seasons.firstOrNull { it.id == season.details.entry.id }?.number
                    ?: season.details.entry.season ?: season.details.seasons.indexOfFirst { it.id == season.details.entry.id } }
                resolve(latest)?.let {
                    result += it
                    visibleGroups[key] = latest.details.seasons.map { season -> season.id }.toSet() + latest.details.entry.id
                }
                resolved += key
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { incomplete = true; if (failure == null) failure = e; failures += seasons.first().details.entry.id to ("resolve_" + e.javaClass.simpleName) }
        }
        val order = if (visibleGroups.isEmpty()) emptyList() else historyOrder(visibleGroups)
        val ranks = order.withIndex().associate { it.value to it.index }
        SmartContinuePage(result.filter { it.key in ranks }.sortedBy { ranks.getValue(it.key) }, resolved, incomplete, failure, failures)
    }

    internal suspend fun resolve(marked: MarkedSeason): SeriesEntry? = resolve(marked, emptySet())

    private suspend fun resolve(marked: MarkedSeason, visited: Set<String>): SeriesEntry? {
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
            val nextMarked = loadSeason(target)
            val nextDetails = nextMarked.details
            if (nextDetails.serialId != details.serialId) throw MarkupChangedException()
            if (nextMarked.snapshot.status == -3) return null
            if (nextMarked.snapshot.status == -2) continue
            if (nextMarked.snapshot.mark != null) return resolve(nextMarked, visited + details.entry.id)
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

internal suspend fun smartHistoryOrder(
    groups: Map<String, Set<String>>,
    loadPage: suspend (String?) -> ru.seasonvar.tv.data.account.AccountPage,
): List<String> {
    if (groups.isEmpty()) return emptyList()
    val owners = mutableMapOf<String, String>()
    for ((key, seasons) in groups) for (id in seasons) {
        val previous = owners.put(id, key)
        if (previous != null && previous != key) throw MarkupChangedException()
    }
    val order = linkedSetOf<String>()
    val seen = mutableSetOf<String>()
    var url: String? = null
    while (true) {
        val page = loadPage(url)
        for (entry in page.entries) owners[entry.id]?.let(order::add)
        if (order.size == groups.size) break
        val next = page.nextPage ?: break
        if (!seen.add(next)) throw MarkupChangedException()
        url = next
    }
    return order.toList()
}
