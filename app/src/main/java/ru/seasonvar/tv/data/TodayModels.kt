package ru.seasonvar.tv.data

import java.time.LocalDate

data class TodayEntry(
    val id: String,
    val url: String,
    val title: String,
    val season: Int?,
    val update: String,
    val posterUrl: String,
) {
    val key: String get() = url + "|" + update
}

data class TodayPage(val date: LocalDate, val entries: List<TodayEntry>)
data class SeriesDescription(val paragraphs: List<String>)

class MarkupChangedException : Exception("Unexpected Seasonvar markup")
class SiteHttpException(val status: Int) : Exception("HTTP " + status)

interface HtmlSource {
    suspend fun get(url: String): String
}
