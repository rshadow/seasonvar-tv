package ru.seasonvar.tv.data.reviews

import okhttp3.FormBody
import okhttp3.Request
import ru.seasonvar.tv.data.SeasonvarParser
import ru.seasonvar.tv.data.SeriesEntry

object ReviewsRequest {
    fun page(entry: SeriesEntry): String {
        require(entry.id.matches(Regex("[0-9]+")))
        val page = requireNotNull(SeasonvarParser.pageUrl(entry.url))
        require(Regex("/serial-([0-9]+)").find(page)?.groupValues?.get(1) == entry.id)
        return page
    }

    fun more(entry: SeriesEntry, cursor: String): Request {
        require(cursor.matches(Regex("[0-9]+")))
        val page = page(entry)
        return Request.Builder().url("https://seasonvar.ru/ajax.php")
            .header("Referer", page).header("X-Requested-With", "XMLHttpRequest")
            .post(FormBody.Builder().add("review_id_serial", entry.id).add("last_review", cursor).build()).build()
    }
}
