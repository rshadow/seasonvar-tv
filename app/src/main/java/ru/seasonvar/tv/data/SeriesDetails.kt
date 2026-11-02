package ru.seasonvar.tv.data

data class SeasonOption(val id: String, val url: String, val label: String, val number: Int?)
data class Episode(val id: String, val label: String)
data class Translation(val id: String, val label: String, val episodes: List<Episode>?)
data class SeriesDetails(
    val entry: SeriesEntry,
    val serialId: String,
    val paragraphs: List<String>,
    val facts: List<Pair<String, String>>,
    val actors: List<String>,
    val seasons: List<SeasonOption>,
    val translations: List<Translation>,
    val defaultTranslationId: String?,
)

// Last local playback position; account synchronization is a separate feature.
data class ResumePoint(val seasonId: String, val translationId: String, val episodeId: String, val positionMillis: Long)
