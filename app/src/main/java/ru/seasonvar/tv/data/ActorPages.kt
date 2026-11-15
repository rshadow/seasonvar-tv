package ru.seasonvar.tv.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.security.MessageDigest
import java.time.LocalDate

object ActorPages {
    fun url(source: String): String? {
        val url = "https://seasonvar.ru/".toHttpUrl().resolve(source) ?: return null
        if (url.host !in setOf("seasonvar.ru", "www.seasonvar.ru") ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.port !in setOf(80, 443) ||
            url.pathSegments.size != 2 || url.pathSegments[0] != "actor" || url.pathSegments[1].isBlank()) return null
        return url.newBuilder().scheme("https").port(443).host("seasonvar.ru").query(null).fragment(null).build().toString()
    }

    internal fun cacheName(url: String): String = "actor-" + MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + ".html"

    fun parse(html: String, actorUrl: String, day: LocalDate, windowed: Boolean = false): CatalogPage {
        val requested = url(actorUrl) ?: throw MarkupChangedException()
        val doc = Jsoup.parse(html, requested)
        val search = doc.selectFirst(".pgs-search") ?: doc
        val actor = search.selectFirst(".pgs-search-actor[href]")
        val items = search.select(".pgs-search-wrap")
        if (actor != null) {
            if (url(actor.attr("href")) != requested) throw MarkupChangedException()
        } else {
            val heading = search.selectFirst(".pgs-search-title") ?: throw MarkupChangedException()
            val name = requested.toHttpUrl().pathSegments[1]
            if (heading.selectFirst("b")?.text() != "Найдено по запросу «$name»:") throw MarkupChangedException()
            if (search.selectFirst(".pgs-search-actor-wrap") == null) throw MarkupChangedException()
            if (items.isEmpty() && (heading.ownText().trim() != "0" ||
                search.selectFirst(".pgs-msg")?.text() != "Сожалеем, по вашему запросу ничего не найдено")) throw MarkupChangedException()
        }
        val entries = items.asSequence().map { item ->
            val info = item.selectFirst(".pgs-search-info") ?: throw MarkupChangedException()
            val links = info.select("a[href]")
            val title = links.firstOrNull()?.text()?.takeIf { it.isNotBlank() } ?: throw MarkupChangedException()
            val page = SeasonvarParser.pageUrl(links[0].attr("href")) ?: throw MarkupChangedException()
            if (links.any { SeasonvarParser.pageUrl(it.attr("href")) != page }) throw MarkupChangedException()
            val id = Regex("/serial-([0-9]+)").find(page)?.groupValues?.get(1) ?: throw MarkupChangedException()
            SeriesEntry(id, page, title, null, "", "https://cdn.svstat.net/oblojka/$id.jpg",
                originalTitle = links.drop(1).firstOrNull()?.text()?.takeIf { it.isNotBlank() && it != title },
                previewDescription = info.selectFirst("p")?.text()?.takeIf { it.isNotBlank() })
        }.distinctBy { it.key }
        return CatalogPage(day, entries.parseEntries(windowed))
    }
}
