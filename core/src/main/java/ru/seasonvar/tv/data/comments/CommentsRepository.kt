package ru.seasonvar.tv.data.comments

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONException
import org.json.JSONObject
import ru.seasonvar.tv.data.HtmlSource
import ru.seasonvar.tv.data.MarkupChangedException
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.cache.HtmlResponseCache
import ru.seasonvar.tv.data.reviews.ReviewsRequest

class CommentRejectedException(val reason: String?) : Exception()

class CommentsRepository(private val cache: HtmlResponseCache, private val source: HtmlSource) {
    suspend fun load(entry: SeriesEntry, page: Int): CommentsPage = withContext(Dispatchers.IO) {
        val lease = cache.capture()
        val result = if (page == 1) {
            val url = ReviewsRequest.page(entry)
            val cached = cache.read("series-${entry.id}.html", lease)
            val parsed = cached?.let { runCatching { CommentsParser.first(it.html, entry) }.getOrNull() }
            parsed ?: CommentsParser.first(source.get(url), entry)
        } else readPage(entry, page)
        if (!cache.isCurrent(lease)) throw CancellationException("Comment cache scope changed")
        result
    }

    suspend fun refresh(entry: SeriesEntry, page: Int): CommentsPage = withContext(Dispatchers.IO) {
        val lease = cache.capture()
        val result = readPage(entry, page)
        if (!cache.isCurrent(lease)) throw CancellationException("Comment cache scope changed")
        result
    }

    private suspend fun readPage(entry: SeriesEntry, page: Int) = CommentsParser.more(source.moreComments(entry, page), page)

    companion object {
        internal fun moreRequest(entry: SeriesEntry, page: Int): Request {
            require(page >= 1)
            return request(entry, FormBody.Builder().add("pageComm", page.toString()).add("seasId", entry.id).build())
        }

        internal fun publishRequest(entry: SeriesEntry, parentId: String?, text: String): Request {
            require(text.isNotBlank() && (parentId == null || parentId.matches(Regex("[0-9]+"))))
            val form = FormBody.Builder().add("seasId", entry.id)
                .add("txtComm", text).add("parentId", parentId.orEmpty()).build()
            return request(entry, object : RequestBody() {
                override fun contentType() = form.contentType()
                override fun contentLength() = form.contentLength()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) = form.writeTo(sink)
            })
        }

        internal fun requirePublished(json: String) {
            val response = try { JSONObject(json) } catch (_: JSONException) { throw MarkupChangedException() }
            when (response.optString("status")) {
                "ok" -> Unit
                "error" -> throw CommentRejectedException(org.jsoup.Jsoup.parse(response.optString("msg")).text()
                    .take(240).takeIf(String::isNotBlank))
                else -> throw MarkupChangedException()
            }
        }

        private fun request(entry: SeriesEntry, body: RequestBody): Request = Request.Builder().url("https://seasonvar.ru/ajax.php")
            .header("Referer", ReviewsRequest.page(entry)).header("X-Requested-With", "XMLHttpRequest")
            .post(body).build()
    }
}
