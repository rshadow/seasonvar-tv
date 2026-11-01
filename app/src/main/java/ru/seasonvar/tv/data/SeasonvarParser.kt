package ru.seasonvar.tv.data

import org.jsoup.Jsoup
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

object SeasonvarParser {
    private val datePattern = Regex("""\d{2}\.\d{2}\.\d{4}""")
    private val seasonPattern = Regex("""\((\d+)\s*сезон\)""", RegexOption.IGNORE_CASE)
    private val pagePattern = Regex("""/serial-\d+[^/]*\.html""")
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.uuuu")
        .withResolverStyle(ResolverStyle.STRICT)

    fun today(html: String, day: LocalDate): CatalogPage {
        val doc = Jsoup.parse(html, "https://seasonvar.ru/")
        val blocks = doc.select("div.news")
        if (blocks.isEmpty()) throw MarkupChangedException()
        val entries = mutableListOf<SeriesEntry>()
        for (block in blocks) {
            val header = block.selectFirst("div.news-head")?.text()
                ?: throw MarkupChangedException()
            val date = try {
                LocalDate.parse(datePattern.find(header)?.value, dateFormat)
            } catch (_: Exception) {
                throw MarkupChangedException()
            }
            if (date != day) continue
            val anchors = block.select("a[href][data-id]")
            for (anchor in anchors) {
                val id = anchor.attr("data-id")
                val url = pageUrl(anchor.attr("href")) ?: throw MarkupChangedException()
                val name = anchor.selectFirst("div.news_n") ?: throw MarkupChangedException()
                val original = name.selectFirst("span")?.text()?.removePrefix("/")?.trim().orEmpty()
                val title = name.ownText().ifBlank { original }
                val info = anchor.selectFirst("div.news-w") ?: throw MarkupChangedException()
                val update = anchor.select("span.news_s").joinToString("; ") { it.text() }
                if (!id.matches(Regex("\\d+")) || title.isBlank() || update.isBlank()) {
                    throw MarkupChangedException()
                }
                val season = seasonPattern.find(info.ownText())?.groupValues?.get(1)?.toIntOrNull()
                entries += SeriesEntry(
                    id = id,
                    url = url,
                    title = title,
                    season = season,
                    update = update,
                    posterUrl = "https://cdn.svstat.net/oblojka/" + id + ".jpg",
                    originalTitle = original.takeIf { it.isNotBlank() },
                )
            }
        }
        return CatalogPage(day, entries.distinctBy { it.key })
    }

    fun sidebar(html: String, day: LocalDate): CatalogPage {
        val body = Jsoup.parseBodyFragment(html, "https://seasonvar.ru/").body()
        val anchors = body.children()
        if (body.ownText().isNotBlank() || anchors.any {
            it.tagName() != "a" || !it.hasClass("pst") || !it.hasClass("rside-p") || !it.hasAttr("href")
        }) throw MarkupChangedException()
        val entries = anchors.map { anchor ->
            val url = pageUrl(anchor.attr("href")) ?: throw MarkupChangedException()
            val id = Regex("/serial-(\\d+)").find(URI(url).path)?.groupValues?.get(1)
                ?: throw MarkupChangedException()
            val original = anchor.selectFirst(".rside-t_en")?.text().orEmpty()
            val title = anchor.selectFirst(".rside-t")?.text().orEmpty().ifBlank { original }
            val info = anchor.selectFirst(".rside-ss") ?: throw MarkupChangedException()
            val update = info.select("span").joinToString("; ") { it.text() }
            if (title.isBlank() || update.isBlank()) throw MarkupChangedException()
            val season = Regex("Сезон\\s+(\\d+)", RegexOption.IGNORE_CASE)
                .find(info.ownText())?.groupValues?.get(1)?.toIntOrNull()
            SeriesEntry(id, url, title,
                season, update, "https://cdn.svstat.net/oblojka/" + id + ".jpg",
                isNew = anchor.hasClass("rside-p-new"),
                originalTitle = original.takeIf { it.isNotBlank() })
        }
        return CatalogPage(day, entries.distinctBy { it.key })
    }

    fun description(html: String, entry: SeriesEntry): SeriesDescription {
        val doc = Jsoup.parse(html, entry.url)
        val info = doc.selectFirst(".pgs-sinfo[data-id-season]")
            ?: throw MarkupChangedException()
        if (info.attr("data-id-season") != entry.id) throw MarkupChangedException()
        val paragraphs = doc.select(".pgs-sinfo-info > p").map { it.text() }
            .filter { it.isNotBlank() }
        val fallback = doc.selectFirst("meta[name=description]")?.attr("content").orEmpty()
        return SeriesDescription(
            paragraphs.ifEmpty { if (fallback.isBlank()) emptyList() else listOf(fallback) }
        )
    }

    private fun pageUrl(path: String): String? = runCatching {
        val uri = URI("https://seasonvar.ru/").resolve(path)
        if (uri.host !in setOf("seasonvar.ru", "www.seasonvar.ru") ||
            uri.userInfo != null || uri.port !in listOf(-1, 443) ||
            uri.scheme !in setOf("https", "http") || !pagePattern.matches(uri.path)
        ) return null
        URI("https", null, uri.host, -1, uri.path, uri.query, null).toASCIIString()
    }.getOrNull()
}
