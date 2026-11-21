package ru.seasonvar.tv.data.comments

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import ru.seasonvar.tv.data.HtmlSource
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.cache.HtmlResponseCache
import ru.seasonvar.tv.data.reviews.ReviewsRequest

class CommentsRepository(private val cache: HtmlResponseCache, private val source: HtmlSource) {
    suspend fun load(entry: SeriesEntry, page: Int): CommentsPage = withContext(Dispatchers.IO) {
        val lease = cache.capture()
        val result = if (page == 1) {
            val url = ReviewsRequest.page(entry)
            val cached = cache.read("series-${entry.id}.html", lease)
            val parsed = cached?.let { runCatching { CommentsParser.first(it.html, entry) }.getOrNull() }
            parsed ?: CommentsParser.first(source.get(url), entry)
        } else CommentsParser.more(source.moreComments(entry, page), page)
        if (!cache.isCurrent(lease)) throw CancellationException("Comment cache scope changed")
        result
    }

    companion object {
        internal fun moreRequest(entry: SeriesEntry, page: Int): Request {
            require(page > 1)
            return Request.Builder().url("https://seasonvar.ru/ajax.php")
                .header("Referer", ReviewsRequest.page(entry)).header("X-Requested-With", "XMLHttpRequest")
                .post(FormBody.Builder().add("pageComm", page.toString()).add("seasId", entry.id).build()).build()
        }
    }
}
