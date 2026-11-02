package ru.seasonvar.tv.data

import java.time.LocalDate

enum class PublicFeed { TODAY, UPDATES, TOP, NEWEST, BEST }

data class SeriesEntry(
    val id: String,
    val url: String,
    val title: String,
    val season: Int?,
    val update: String,
    val posterUrl: String,
    val isNew: Boolean = false,
    val originalTitle: String? = null,
    val seasonCountLabel: String? = null,
    val previewDescription: String? = null,
    val sourceKey: String? = null,
) {
    val subtitle: String get() = update.ifBlank { seasonCountLabel.orEmpty() }
    val key: String get() = sourceKey ?: (url + "|" + update)
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
