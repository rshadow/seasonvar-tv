package ru.seasonvar.tv.data.comments

import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import ru.seasonvar.tv.data.MarkupChangedException
import ru.seasonvar.tv.data.SeriesEntry

object CommentsParser {
    fun first(html: String, entry: SeriesEntry): CommentsPage {
        val document = Jsoup.parse(html)
        if (document.selectFirst(".pgs-sinfo[data-id-season]")?.attr("data-id-season") != entry.id) throw MarkupChangedException()
        val tab = document.selectFirst("[data-tabr=comments]") ?: return CommentsPage(emptyList())
        return parse(tab.selectFirst("[data-cmt=wrap]") ?: throw MarkupChangedException(), 1)
    }

    fun more(json: String, page: Int): CommentsPage {
        val response = try { JSONObject(json) } catch (_: JSONException) { throw MarkupChangedException() }
        val html = response.opt("comm") as? String ?: throw MarkupChangedException()
        if (response.optString("status") != "ok") throw MarkupChangedException()
        return parse(Jsoup.parseBodyFragment(html).body(), page)
    }

    private fun parse(container: Element, page: Int): CommentsPage {
        val pagination = container.select("[data-click=cmtPage][data-page]")
        val current = pagination.filter { it.hasClass("act") }.map { it.attr("data-page").toIntOrNull() }.toSet()
        if (pagination.isNotEmpty() && current != setOf(page) || pagination.isEmpty() && page > 1) throw MarkupChangedException()
        val next = (page + 1).takeIf { candidate -> pagination.any { it.attr("data-page").toIntOrNull() == candidate } }
        val entries = container.select(".svc_comment[data-cmtid]").mapNotNull { node ->
            val id = node.attr("data-cmtid").takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: throw MarkupChangedException()
            val message = node.selectFirst(".svc_msg") ?: return@mapNotNull null
            val parts = partsOf(message)
            if (parts.isEmpty()) return@mapNotNull null
            val author = node.selectFirst(".svc_nick [data-nick=true]")?.text()?.takeIf(String::isNotBlank)
                ?: node.selectFirst(".svc_nick")?.ownText()?.takeIf(String::isNotBlank)
            SeasonComment(id, node.hasClass("svc_comment_sub"), author,
                node.selectFirst(".svc_date")?.text()?.takeIf(String::isNotBlank),
                if (message.hasClass("spoiler")) listOf(CommentPart.Spoiler(parts)) else parts)
        }.distinctBy(SeasonComment::id)
        return CommentsPage(entries, next)
    }

    private fun partsOf(element: Element): List<CommentPart> {
        val parts = mutableListOf<CommentPart>()
        val text = StringBuilder()
        fun flush() {
            val value = text.lines().joinToString("\n") { it.trim() }.trim()
            if (value.isNotEmpty()) parts += CommentPart.Text(value)
            text.clear()
        }
        fun append(node: Node) {
            when (node) {
                is TextNode -> text.append(node.wholeText)
                is Element -> when (node.normalName()) {
                    "script", "style", "summary", "iframe", "object", "embed", "form", "button", "input" -> Unit
                    "br" -> text.append('\n')
                    "details" -> {
                        flush()
                        val body = partsOf(node)
                        if (body.isNotEmpty()) parts += CommentPart.Spoiler(body)
                    }
                    else -> {
                        node.childNodes().forEach(::append)
                        if (node.isBlock) text.append('\n')
                    }
                }
            }
        }
        element.childNodes().forEach(::append)
        flush()
        return parts
    }
}
