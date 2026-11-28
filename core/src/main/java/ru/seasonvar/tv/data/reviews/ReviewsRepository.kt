package ru.seasonvar.tv.data.reviews

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import ru.seasonvar.tv.data.HtmlSource
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.cache.CacheLease
import ru.seasonvar.tv.data.cache.HtmlResponseCache

class ReviewsRepository(private val cache: HtmlResponseCache, private val source: HtmlSource) {
    suspend fun first(entry: SeriesEntry, force: Boolean = false): ReviewsPage = withContext(Dispatchers.IO) {
        val url = ReviewsRequest.page(entry)
        val lease = cache.capture()
        val name = "series-${entry.id}.html"
        if (!force) cache.read(name, lease)?.let { saved ->
            runCatching { ReviewsParser.first(saved.html, entry) }.getOrNull()?.let {
                requireCurrent(lease)
                return@withContext it
            }
        }
        val html = source.get(url)
        val page = ReviewsParser.first(html, entry)
        requireCurrent(lease)
        cache.save(name, html, lease)
        page
    }

    suspend fun more(entry: SeriesEntry, cursor: String, force: Boolean = false): List<SeriesReview> = withContext(Dispatchers.IO) {
        ReviewsRequest.page(entry)
        require(cursor.matches(Regex("[0-9]+")))
        val lease = cache.capture()
        val name = "reviews-more-${entry.id}-$cursor.html"
        if (!force) cache.read(name, lease)?.let { saved ->
            runCatching { ReviewsParser.more(saved.html) }.getOrNull()?.let {
                requireCurrent(lease)
                return@withContext it
            }
        }
        val html = source.moreReviews(entry, cursor)
        val entries = ReviewsParser.more(html)
        requireCurrent(lease)
        cache.save(name, html, lease)
        entries
    }

    private fun requireCurrent(lease: CacheLease) {
        if (!cache.isCurrent(lease)) throw CancellationException("Review cache scope changed")
    }
}
