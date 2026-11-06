package ru.seasonvar.tv.data.playback

import ru.seasonvar.tv.data.Episode

data class PlaybackRequest(
    val seasonId: String,
    val pageUrl: String,
    val translationId: String,
    val episodeId: String,
    val episodeLabel: String,
    val title: String,
    val episodes: List<Episode> = emptyList(),
    val resumePositionMillis: Long? = null,
    val seasonNumber: Int? = null,
) {
    fun selectEpisode(id: String): PlaybackRequest? {
        if (id == episodeId) return this
        val episode = episodes.singleOrNull { it.id == id } ?: return null
        return copy(episodeId = episode.id, episodeLabel = episode.label, resumePositionMillis = null)
    }

    fun adjacent(direction: Int): PlaybackRequest? {
        if (direction != -1 && direction != 1) return null
        val current = episodes.indices.singleOrNull { episodes[it].id == episodeId } ?: return null
        val next = episodes.getOrNull(current + direction) ?: return null
        return selectEpisode(next.id)
    }
}

internal fun playbackEpisodes(ids: List<String>?, labels: List<String>?): List<Episode> {
    if (ids == null || labels == null || ids.size != labels.size || ids.distinct().size != ids.size ||
        ids.any { it.isBlank() } || labels.any { it.isBlank() }) return emptyList()
    return ids.zip(labels) { id, label -> Episode(id, label) }
}

data class VideoSource(val url: String)
class VideoUnavailableException : Exception("Video source unavailable")
