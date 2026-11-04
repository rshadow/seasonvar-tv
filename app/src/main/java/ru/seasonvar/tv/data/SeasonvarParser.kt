package ru.seasonvar.tv.data

import org.jsoup.Jsoup
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONException
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
                    initialSelection = EntrySelectionParser.updates(anchor.select("span.news_s").map { it.text() }),
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
                originalTitle = original.takeIf { it.isNotBlank() },
                initialSelection = EntrySelectionParser.updates(info.select("span").map { it.text() }))
        }
        return CatalogPage(day, entries.distinctBy { it.key })
    }

    fun announcements(html: String, day: LocalDate): CatalogPage {
        val content = Jsoup.parse(html, "https://seasonvar.ru/")
            .selectFirst(".content .content-wrap") ?: throw MarkupChangedException()
        if (content.selectFirst(".pgs-head")?.text() != "Скоро на сайте") throw MarkupChangedException()
        val panel = content.selectFirst(".pgs-search > .tabs-result > li.act") ?: throw MarkupChangedException()
        if (panel.ownText().isNotBlank() || panel.children().any {
                !it.hasClass("pgs-search-wrap") && !it.hasClass("pgs-msg")
            }) throw MarkupChangedException()
        val entries = panel.children().filter { it.hasClass("pgs-search-wrap") }.map { row ->
            val url = pageUrl(row.selectFirst("a.pst[href]")?.attr("href").orEmpty())
                ?: throw MarkupChangedException()
            val id = Regex("/serial-([0-9]+)").find(URI(url).path)?.groupValues?.get(1)
                ?: throw MarkupChangedException()
            val info = row.selectFirst(".pgs-search-info") ?: throw MarkupChangedException()
            val links = info.children().filter { it.tagName() == "a" && it.hasAttr("href") }
            if (links.isEmpty() || links.any { pageUrl(it.attr("href")) != url }) throw MarkupChangedException()
            val original = links.getOrNull(1)?.text()?.takeIf { it.isNotBlank() }
            val title = links.first().text().ifBlank { original.orEmpty() }
            val release = info.selectFirst("span")?.text()?.takeIf { it.isNotBlank() }
                ?: throw MarkupChangedException()
            val expected = release.substringAfter(':', release).trim()
            if (title.isBlank() || expected.isBlank()) throw MarkupChangedException()
            val season = Regex("([0-9]+)\\s*сезон", RegexOption.IGNORE_CASE)
                .find(info.ownText())?.groupValues?.get(1)?.toIntOrNull()
            SeriesEntry(
                id = id, url = url, title = title, season = season, update = release,
                posterUrl = "https://cdn.svstat.net/oblojka/" + id + ".jpg",
                originalTitle = original, expectedReleaseDate = expected, sourceKey = "announcements:$id",
                previewDescription = info.selectFirst("p")?.text()?.takeIf { it.isNotBlank() },
            )
        }
        return CatalogPage(day, entries)
    }

    fun best(html: String, day: LocalDate): CatalogPage {
        val doc = Jsoup.parse(html, "https://seasonvar.ru/")
        val content = doc.selectFirst(".content .content-wrap") ?: throw MarkupChangedException()
        if (content.selectFirst(".pgs-head")?.text() != "Топ сериалов") throw MarkupChangedException()
        val results = content.selectFirst(".pgs-search #search-res") ?: throw MarkupChangedException()
        val rows = results.children()
        if (results.ownText().isNotBlank() || rows.any { !it.hasClass("pgs-search-wrap") }) {
            throw MarkupChangedException()
        }
        val entries = rows.map { row ->
            val url = pageUrl(row.selectFirst("a.pst[href]")?.attr("href").orEmpty())
                ?: throw MarkupChangedException()
            val info = row.selectFirst(".pgs-search-info") ?: throw MarkupChangedException()
            val links = info.children().filter { it.tagName() == "a" && it.hasAttr("href") }
            if (links.isEmpty() || links.any { pageUrl(it.attr("href")) != url }) throw MarkupChangedException()
            val names = links.map { it.text() }.filter { it.isNotBlank() }
            if (names.isEmpty()) throw MarkupChangedException()
            val id = Regex("/serial-(\\d+)").find(URI(url).path)?.groupValues?.get(1)
                ?: throw MarkupChangedException()
            val count = Regex("\\((\\d+\\s+сезон(?:а|ов)?)\\)")
                .find(info.ownText())?.groupValues?.get(1)
            SeriesEntry(
                id = id, url = url, title = names.first(), season = null, update = "",
                posterUrl = "https://cdn.svstat.net/oblojka/" + id + ".jpg",
                originalTitle = names.getOrNull(1), seasonCountLabel = count,
                previewDescription = info.selectFirst("p")?.text()?.takeIf { it.isNotBlank() },
            )
        }
        return CatalogPage(day, entries.distinctBy { it.key })
    }

    fun search(json: String, query: String): List<SeriesEntry> {
        try {
            val root = JSONObject(json)
            if (root.opt("query") != query) throw MarkupChangedException()
            if (listOf("suggestions", "data", "id").all { root.has(it) && root.isNull(it) }) {
                return emptyList()
            }
            val suggestions = root.getJSONObject("suggestions")
            val names = suggestions.getJSONArray("valu")
            val paths = root.getJSONArray("data")
            val ids = root.getJSONArray("id")
            val ratings = suggestions.opt("kp")
            if (names.length() != paths.length() ||
                (ratings != null && (ratings !is JSONArray || ratings.length() != names.length()))
            ) throw MarkupChangedException()
            val rows = (0 until names.length()).map { index ->
                if (ratings is JSONArray && ratings.get(index) !is String) throw MarkupChangedException()
                val rawName = names.get(index) as? String ?: throw MarkupChangedException()
                val path = paths.get(index) as? String ?: throw MarkupChangedException()
                rawName to path
            }
            if (ids.length() != rows.count { it.second.isNotBlank() }) throw MarkupChangedException()
            var idIndex = 0
            return rows.mapNotNull { (rawName, path) ->
                // Site separators have an empty path and no corresponding ID.
                if (path.isBlank()) return@mapNotNull null
                val id = ids.get(idIndex++) as? String ?: throw MarkupChangedException()
                if (!id.matches(Regex("\\d+"))) throw MarkupChangedException()
                if (path.startsWith("actor/") || path.startsWith("tag/")) return@mapNotNull null
                val url = pageUrl(path) ?: throw MarkupChangedException()
                if (!id.matches(Regex("\\d+")) ||
                    Regex("/serial-(\\d+)").find(URI(url).path)?.groupValues?.get(1) != id
                ) throw MarkupChangedException()
                val name = Jsoup.parseBodyFragment(rawName).text().trim()
                val match = seasonPattern.find(name)
                val title = name.replace(seasonPattern, "").trim()
                val parts = title.split(Regex("""\s*/\s+"""), limit = 2)
                val preferred = parts.first().ifBlank { parts.getOrNull(1).orEmpty() }
                if (preferred.isBlank()) throw MarkupChangedException()
                SeriesEntry(id, url, preferred, match?.groupValues?.get(1)?.toIntOrNull(), "",
                    "https://cdn.svstat.net/oblojka/" + id + ".jpg",
                    originalTitle = parts.getOrNull(1)?.takeIf { it.isNotBlank() && it != preferred })
            }.distinctBy { it.key }
        } catch (_: JSONException) {
            throw MarkupChangedException()
        }
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

    internal fun pageUrl(path: String): String? = runCatching {
        val uri = URI("https://seasonvar.ru/").resolve(path)
        if (uri.host !in setOf("seasonvar.ru", "www.seasonvar.ru") ||
            uri.userInfo != null || uri.port !in listOf(-1, 443) ||
            uri.scheme !in setOf("https", "http") || !pagePattern.matches(uri.path)
        ) return null
        URI("https", null, uri.host, -1, uri.path, uri.query, null).toASCIIString()
    }.getOrNull()
}
