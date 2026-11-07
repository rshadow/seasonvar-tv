package ru.seasonvar.tv.data.home

import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.playback.PlaybackRequest

data class LocalPlayback(val point: ResumePoint, val completed: Boolean)
data class AccountContinuation(val entry: SeriesEntry?)
class HomePlaybackUnavailable : Exception("Continuation unavailable")

class HomePlaybackResolver(
    private val details: suspend (SeriesEntry) -> SeriesDetails,
    private val local: suspend (List<String>) -> LocalPlayback?,
    private val account: suspend (SeriesDetails) -> AccountContinuation? = { null },
    private val preferred: () -> List<String> = { emptyList() },
) {
    suspend fun resolve(source: SeriesEntry): PlaybackRequest {
        val seed = source.copy(initialSelection = EntrySelection())
        val first = details(seed)
        val options = first.seasons.sortedBy { it.number ?: Int.MAX_VALUE }
        val ids = (options.map { it.id } + seed.id).distinct()
        suspend fun season(id: String): SeriesDetails {
            if (id == first.entry.id) return first
            val option = options.singleOrNull { it.id == id } ?: throw HomePlaybackUnavailable()
            return details(seed.copy(id = option.id, url = option.url, season = option.number)).also {
                if (it.serialId != first.serialId) throw HomePlaybackUnavailable()
            }
        }
        account(first)?.let { continuation ->
            val target = continuation.entry ?: throw HomePlaybackUnavailable()
            val selected = season(target.id)
            return request(selected, target.initialSelection)
        }
        local(ids)?.let { saved ->
            val selected = season(saved.point.seasonId)
            val voice = selected.translations.singleOrNull { it.id == saved.point.translationId }
                ?: throw HomePlaybackUnavailable()
            val episodes = voice.episodes ?: throw HomePlaybackUnavailable()
            val index = episodes.indexOfFirst { it.id == saved.point.episodeId }
            if (index < 0) throw HomePlaybackUnavailable()
            if (!saved.completed) return request(selected, EntrySelection(
                episodeId = saved.point.episodeId, translationId = voice.id, positionMillis = saved.point.positionMillis))
            episodes.getOrNull(index + 1)?.let { return request(selected,
                EntrySelection(episodeId = it.id, translationId = voice.id, positionMillis = 0)) }
            val current = options.indexOfFirst { it.id == selected.entry.id }
            if (current < 0) throw HomePlaybackUnavailable()
            for (next in options.drop(current + 1)) {
                val candidate = season(next.id)
                if (candidate.playbackUnavailableReason == null && candidate.translations.any { !it.episodes.isNullOrEmpty() })
                    return request(candidate, EntrySelection(), listOf(voice.label) + preferred())
            }
            throw HomePlaybackUnavailable()
        }
        for (id in if (options.isEmpty()) listOf(seed.id) else options.map { it.id }) {
            val candidate = season(id)
            if (candidate.playbackUnavailableReason == null && candidate.translations.any { !it.episodes.isNullOrEmpty() })
                return request(candidate, EntrySelection())
        }
        throw HomePlaybackUnavailable()
    }

    private fun request(details: SeriesDetails, hint: EntrySelection, preferences: List<String> = preferred()): PlaybackRequest {
        val playable = if (!hint.hasEpisode && !hint.hasTranslation) details.copy(
            translations = details.translations.filter { it.id != "68" && !it.episodes.isNullOrEmpty() }) else details
        val selected = EntrySelectionResolver.resolve(playable, hint, null, preferences)
        if (selected.failure != null) throw HomePlaybackUnavailable()
        val voice = details.translations.singleOrNull { it.id == selected.translationId } ?: throw HomePlaybackUnavailable()
        val episode = voice.episodes?.singleOrNull { it.id == selected.episodeId } ?: throw HomePlaybackUnavailable()
        return PlaybackRequest(details.entry.id, details.entry.url, voice.id, episode.id, episode.label,
            details.entry.title, voice.episodes, hint.positionMillis ?: 0,
            details.seasons.firstOrNull { it.id == details.entry.id }?.number ?: details.entry.season)
    }
}
