package ru.seasonvar.tv.data.playback

import ru.seasonvar.tv.data.*

class NextSeasonResolver(
    private val load: suspend (SeriesEntry) -> SeriesDetails,
    private val preferences: () -> List<String> = { emptyList() },
) {
    suspend fun next(request: PlaybackRequest): PlaybackRequest? {
        val entry = SeriesEntry(request.seasonId, request.pageUrl, request.title, null, "", "")
        val current = load(entry)
        val currentVoice = current.translations.firstOrNull { it.id == request.translationId } ?: throw MarkupChangedException()
        val currentEpisodes = currentVoice.episodes ?: throw MarkupChangedException()
        if (currentEpisodes.lastOrNull()?.id != request.episodeId) return null
        val seasons = current.seasons.sortedBy { it.number ?: Int.MAX_VALUE }
        val index = seasons.indexOfFirst { it.id == request.seasonId }
        if (index < 0) throw MarkupChangedException()
        val season = seasons.getOrNull(index + 1) ?: return null
        val next = load(entry.copy(id = season.id, url = season.url, season = season.number))
        if (next.serialId != current.serialId) throw MarkupChangedException()
        if (next.playbackUnavailableReason != null) return null
        val playable = next.copy(translations = next.translations.filter { it.id != "68" })
        val selected = EntrySelectionResolver.resolve(playable, EntrySelection(), null,
            listOf(currentVoice.label) + preferences())
        val voice = playable.translations.firstOrNull { it.id == selected.translationId } ?: return null
        val episodes = voice.episodes ?: throw MarkupChangedException()
        val first = episodes.firstOrNull() ?: return null
        return PlaybackRequest(season.id, season.url, voice.id, first.id, first.label, next.entry.title,
            episodes.toList(), resumePositionMillis = 0)
    }
}
