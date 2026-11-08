package ru.seasonvar.tv.data

import java.time.LocalDate

enum class PublicFeed { TODAY, UPDATES, TOP, NEWEST, BEST, ANNOUNCEMENTS }

data class ProgressCounter(val marked: Int, val total: Int)
data class SeasonEpisodeProgress(val seasonId: String, val counter: ProgressCounter)
data class AccountProgress(val seasons: ProgressCounter? = null, val episodes: SeasonEpisodeProgress? = null)
data class AccountMark(val progress: String, val status: String, val completedSeason: Boolean = false)

data class EntrySelection(
    val episodeId: String? = null,
    val episodeLabel: String? = null,
    val translationId: String? = null,
    val translationLabel: String? = null,
    val positionMillis: Long? = null,
) {
    val hasEpisode get() = !episodeId.isNullOrBlank() || !episodeLabel.isNullOrBlank()
    val hasTranslation get() = !translationId.isNullOrBlank() || !translationLabel.isNullOrBlank()
}

/** Parsed list record; id/url identify the exact season, while optional metadata may be absent. */
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
    val accountMark: AccountMark? = null,
    val accountProgress: AccountProgress? = null,
    val historyVisitedAt: String? = null,
    val initialSelection: EntrySelection = EntrySelection(),
    val accountListMark: ru.seasonvar.tv.data.account.AccountListMark? = null,
    val expectedReleaseDate: String? = null,
    val isWatching: Boolean = false,
) {
    val largePosterUrl: String? get() = PosterImages.largeUrl(posterUrl)
    val subtitle: String get() = update.ifBlank { seasonCountLabel.orEmpty() }
    val key: String get() = sourceKey ?: (url + "|" + update)
    val fullTitle: String get() = listOfNotNull(title, originalTitle).distinct().joinToString(" / ")
}

data class CatalogPage(val date: LocalDate, val entries: List<SeriesEntry>)
data class SeriesDescription(val paragraphs: List<String>)

class MarkupChangedException : Exception("Unexpected Seasonvar markup")
class SiteHttpException(val status: Int) : Exception("HTTP " + status)

interface HtmlSource {
    suspend fun bestFilters(filters: BestListFilters): String = throw UnsupportedOperationException()
    suspend fun get(url: String): String
    suspend fun sidebar(feed: PublicFeed): String
}
