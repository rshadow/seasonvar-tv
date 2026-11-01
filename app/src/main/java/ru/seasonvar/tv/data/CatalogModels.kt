package ru.seasonvar.tv.data

import java.time.LocalDate

enum class PublicFeed { TODAY, UPDATES, TOP }

data class SeriesEntry(
    val id: String,
    val url: String,
    val title: String,
    val season: Int?,
    val update: String,
    val posterUrl: String,
    val isNew: Boolean = false,
    val originalTitle: String? = null,
) {
    val key: String get() = url + "|" + update
    val fullTitle: String get() = listOfNotNull(title, originalTitle).distinct().joinToString(" / ")
}

data class CatalogPage(val date: LocalDate, val entries: List<SeriesEntry>)
data class SeriesDescription(val paragraphs: List<String>)

class MarkupChangedException : Exception("Unexpected Seasonvar markup")
class SiteHttpException(val status: Int) : Exception("HTTP " + status)

interface HtmlSource {
    suspend fun get(url: String): String
    suspend fun sidebar(feed: PublicFeed): String
}
