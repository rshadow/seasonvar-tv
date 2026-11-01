package ru.seasonvar.tv.data

import java.time.LocalDate

enum class PublicFeed { TODAY, UPDATES }

data class SeriesEntry(
    val id: String,
    val url: String,
    val title: String,
    val season: Int?,
    val update: String,
    val posterUrl: String,
) {
    val key: String get() = url + "|" + update
}

data class CatalogPage(val date: LocalDate, val entries: List<SeriesEntry>)
data class SeriesDescription(val paragraphs: List<String>)

class MarkupChangedException : Exception("Unexpected Seasonvar markup")
class SiteHttpException(val status: Int) : Exception("HTTP " + status)

interface HtmlSource {
    suspend fun get(url: String): String
    suspend fun updates(): String
}
