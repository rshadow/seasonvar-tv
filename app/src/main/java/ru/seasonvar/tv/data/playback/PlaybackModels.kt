package ru.seasonvar.tv.data.playback

data class PlaybackRequest(
    val seasonId: String,
    val pageUrl: String,
    val translationId: String,
    val episodeId: String,
    val episodeLabel: String,
    val title: String,
)

data class VideoSource(val url: String)
class VideoUnavailableException : Exception("Video source unavailable")
