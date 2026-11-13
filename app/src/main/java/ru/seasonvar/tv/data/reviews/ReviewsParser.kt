package ru.seasonvar.tv.data.reviews

import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.TextNode
import ru.seasonvar.tv.data.MarkupChangedException
import ru.seasonvar.tv.data.SeriesEntry
import java.security.MessageDigest

object ReviewsParser {
    private val countPattern = Regex("\\((\\d+)\\)")
    private val assignment = Regex("\\bvar\\s+review\\s*=")

    fun count(doc: Document): Int? = doc.selectFirst("[data-tabt=reviews]")?.text()
        ?.let { countPattern.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    fun first(html: String, entry: SeriesEntry): ReviewsPage {
        val doc = Jsoup.parse(html, entry.url)
        if (doc.selectFirst(".pgs-sinfo[data-id-season]")?.attr("data-id-season") != entry.id) throw MarkupChangedException()
        val section = doc.selectFirst("[data-tabr=reviews]") ?: throw MarkupChangedException()
        val entries = posts(section)
        if (entries.isEmpty() && (count(doc) ?: 0) > 0) throw MarkupChangedException()
        val more = section.selectFirst("[data-click=reviewShow]") != null
        val cursor = if (more) doc.select("script:not([src])").firstNotNullOfOrNull { script ->
            assignment.find(script.data())?.let { match ->
                runCatching {
                    (JSONTokener(script.data().substring(match.range.last + 1)).nextValue() as? JSONObject)
                        ?.optString("last")?.takeIf { it.matches(Regex("[0-9]+")) }
                }.getOrNull()
            }
        } ?: throw MarkupChangedException() else null
        return ReviewsPage(entries, count(doc)?.coerceAtLeast(entries.size), cursor)
    }

    fun more(html: String): List<SeriesReview> {
        if (html.isBlank()) return emptyList()
        val body = Jsoup.parseBodyFragment(html).body()
        if (body.ownText().isNotBlank() || body.children().any { !it.hasClass("pgs-review-post") }) throw MarkupChangedException()
        val entries = posts(body)
        if (entries.isEmpty() || body.selectFirst("[data-click=reviewShow]") != null) throw MarkupChangedException()
        return entries
    }

    private fun posts(section: org.jsoup.nodes.Element): List<SeriesReview> = uniqueReviews(
        section.select(".pgs-review-post").map { post ->
            val author = post.selectFirst(".pgs-review-name a")?.text()?.takeIf { it.isNotBlank() }
            val date = post.selectFirst(".pgs-review-date")?.text()?.takeIf { it.isNotBlank() }
            val content = post.clone()
            content.select(".pgs-review-name,.pgs-review-date,script,style,img,iframe,object,embed,form,button,input,textarea").remove()
            content.select("br").forEach { it.before(TextNode("\n")); it.remove() }
            content.select("p,div,li,blockquote").forEach { it.appendChild(TextNode("\n\n")) }
            val text = content.wholeText().replace('\u00a0', ' ').replace("\r\n", "\n")
                .lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()
            if (text.isEmpty()) throw MarkupChangedException()
            val identity = MessageDigest.getInstance("SHA-256")
                .digest(listOf(author.orEmpty(), date.orEmpty(), text).joinToString("\u0000").toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            SeriesReview(identity, author, date, text)
        })
}
