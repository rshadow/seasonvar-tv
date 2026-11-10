package ru.seasonvar.tv.data.account

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.security.MessageDigest
import ru.seasonvar.tv.data.MarkupChangedException
import ru.seasonvar.tv.data.SeasonvarParser
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.parseEntries
import ru.seasonvar.tv.data.EntrySelectionParser

object HistoryParser {
    fun pageUrl(raw: String): String? {
        val url = raw.toHttpUrlOrNull() ?: return null
        if (!siteOrigin(url) || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null ||
            url.encodedPath != "/" || url.queryParameterValues("mod") != listOf("history") ||
            url.queryParameterNames.any { it !in setOf("mod", "page") }) return null
        val pages = url.queryParameterValues("page")
        if (pages.size > 1) return null
        val page = if (pages.isEmpty()) 1 else pages.single()?.takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull() ?: return null
        if (page < 1) return null
        return PersonalFeed.HISTORY.url + if (page == 1) "" else "&page=$page"
    }

    fun parse(html: String, requestedUrl: String = PersonalFeed.HISTORY.url, windowed: Boolean = false): AccountPage {
        val user = AccountParser.user(html) ?: throw SessionExpiredException()
        val canonical = pageUrl(requestedUrl) ?: throw MarkupChangedException()
        val current = canonical.toHttpUrlOrNull()!!.queryParameter("page")?.toInt() ?: 1
        val doc = Jsoup.parse(html, "https://seasonvar.ru/")
        val result = doc.selectFirst(".pgs-history [data-result=history]") ?: throw MarkupChangedException()
        val list = result.selectFirst(".pgs-history-c") ?: throw MarkupChangedException()
        if (list.children().any { it.tagName() !in setOf("a", "br") } || list.ownText().isNotBlank()) throw MarkupChangedException()
        val occurrences = mutableMapOf<String, Int>()
        val anchors = list.select("a")
        val entries = anchors.asSequence().map { link ->
            val url = SeasonvarParser.pageUrl(link.attr("href").substringBefore('#')) ?: throw MarkupChangedException()
            val id = Regex("/serial-([0-9]+)").find(url)?.groupValues?.get(1) ?: throw MarkupChangedException()
            val name = link.selectFirst("b")?.text()?.trim().orEmpty()
            val at = link.selectFirst("span")?.text()?.trim().orEmpty()
            if (name.isBlank() || at.isBlank()) throw MarkupChangedException()
            val names = name.split(Regex("\\s*/\\s+"), limit = 2)
            val title = names.first().ifBlank { names.getOrNull(1).orEmpty() }
            val season = Regex("([0-9]+)\\s*сезон", RegexOption.IGNORE_CASE).find(link.ownText())?.groupValues?.get(1)?.toIntOrNull()
            val selection = EntrySelectionParser.text(link.ownText())
            val episode = selection.episodeLabel
            val fingerprint = MessageDigest.getInstance("SHA-256").digest("$url|$episode|$at".toByteArray(Charsets.UTF_8))
                .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            val occurrence = (occurrences[fingerprint] ?: 0) + 1
            occurrences[fingerprint] = occurrence
            SeriesEntry(id, url, title, season, "", "https://cdn.svstat.net/oblojka/$id.jpg",
                originalTitle = names.getOrNull(1)?.takeIf { it.isNotBlank() && it != title },
                sourceKey = "history:$current:$fingerprint:$occurrence", historyVisitedAt = at, initialSelection = selection)
        }
        val pages = result.select(".pgs-history-p a[href]").map { a ->
            pageUrl(a.absUrl("href")) ?: throw MarkupChangedException()
        }.distinct()
        val next = pages.map { it to (it.toHttpUrlOrNull()!!.queryParameter("page")?.toInt() ?: 1) }
            .filter { it.second > current }.minByOrNull { it.second }
        if (next != null && (next.second != current + 1 || anchors.isEmpty())) throw MarkupChangedException()
        return AccountPage(user, entries.parseEntries(windowed), next?.first)
    }
}
