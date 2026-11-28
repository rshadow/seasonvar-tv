package ru.seasonvar.tv.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup

object SeriesDetailsParser {
    private val seasonNumber = Regex("""(\d+)\s*сезон""", RegexOption.IGNORE_CASE)
    private val episodeAssignment = Regex("""\b(?:var\s+)?arEpisodes\s*=""")

    fun parse(html: String, entry: SeriesEntry): SeriesDetails = parse(Jsoup.parse(html, entry.url), entry)

    internal fun parse(doc: org.jsoup.nodes.Document, entry: SeriesEntry): SeriesDetails {
        val info = doc.selectFirst(".pgs-sinfo[data-id-season]") ?: throw MarkupChangedException()
        if (info.attr("data-id-season") != entry.id) throw MarkupChangedException()
        val serialId = info.attr("data-id-serial")
        if (!serialId.matches(Regex("[0-9]+"))) throw MarkupChangedException()
        val facts = info.select(".pgs-sinfo_list span").mapNotNull { span ->
            val label = span.previousSibling()?.toString()?.substringAfterLast('>')
                ?.substringBefore(':')?.trim().orEmpty()
            val value = span.text().trim()
            if (label.isBlank() || value.isBlank()) null else label to value
        }
        val original = facts.firstOrNull { it.first == "Оригинал" }?.second ?: entry.originalTitle
        val seasons = doc.select(".pgs-seaslist a[href]").map { anchor ->
            val url = SeasonvarParser.pageUrl(anchor.attr("href")) ?: throw MarkupChangedException()
            val id = Regex("/serial-([0-9]+)").find(url)?.groupValues?.get(1) ?: throw MarkupChangedException()
            val number = seasonNumber.find(anchor.text())?.groupValues?.get(1)?.toIntOrNull()
            SeasonOption(id, url, number?.let { "Сезон $it" } ?: anchor.text().removePrefix(">>>").trim(), number)
        }.distinctBy { it.id }.toMutableList()
        if (seasons.none { it.id == entry.id }) {
            seasons.add(0, SeasonOption(entry.id, entry.url, entry.season?.let { "Сезон $it" } ?: "Текущий сезон", entry.season))
        }
        val episodeJson = doc.select("script:not([src])").firstNotNullOfOrNull { script ->
            episodeAssignment.find(script.data())?.let { match ->
                try {
                    when (val value = JSONTokener(script.data().substring(match.range.last + 1)).nextValue()) {
                        is JSONObject -> value.keys().asSequence().associateWith { key ->
                            value.optJSONObject(key) ?: throw MarkupChangedException()
                        }
                        is JSONArray -> (0 until value.length()).mapNotNull { index ->
                            value.optJSONObject(index)?.let { index.toString() to it }
                        }.toMap()
                        else -> throw MarkupChangedException()
                    }
                } catch (_: Exception) { throw MarkupChangedException() }
            }
        }
        val translationNodes = doc.select("ul.pgs-trans li[data-translate]")
        val translations = translationNodes.map { node ->
            val id = node.attr("data-translate")
            if (!id.matches(Regex("[0-9]+")) || node.text().isBlank()) throw MarkupChangedException()
            Translation(id, node.text(), episodeJson?.get(id)?.let(::episodes))
        }.distinctBy { it.id }.ifEmpty {
            if (episodeJson?.containsKey("0") == true) listOf(Translation("0", "Стандартный", episodes(episodeJson.getValue("0"))))
            else emptyList()
        }
        val default = translationNodes.firstOrNull { it.hasClass("act") || it.hasClass("active") }
            ?.attr("data-translate") ?: translations.firstOrNull { it.id == "0" }?.id ?: translations.firstOrNull()?.id
        val poster = info.selectFirst(".pgs-sinfo-img img[src]")?.absUrl("src").orEmpty()
        return SeriesDetails(
            entry.copy(originalTitle = original, season = seasons.first { it.id == entry.id }.number,
                posterUrl = poster.ifBlank { entry.posterUrl }),
            serialId, SeasonvarParser.description(doc, entry).paragraphs, facts,
            info.select(".pgs-sinfo-actor a").mapNotNull { actor ->
                actor.text().takeIf { it.isNotBlank() }?.let { name ->
                    SeriesActor(name, ActorImages.photoUrl(actor.selectFirst("img[src]")?.absUrl("src").orEmpty()),
                        ActorPages.url(actor.attr("href")))
                }
            }.distinctBy { it.name },
            seasons, translations, default,
            doc.selectFirst(".pgs-player-block:not(.error)")?.text()?.takeIf { it.isNotBlank() },
            reviewCount = ru.seasonvar.tv.data.reviews.ReviewsParser.count(doc),
        )
    }

    private fun episodes(json: JSONObject): List<Episode> {
        val byName = linkedMapOf<String, Pair<Episode, String?>>()
        for (key in json.keys()) {
            if (key.isEmpty()) continue
            val value = json.optJSONObject(key) ?: throw MarkupChangedException()
            val name = value.optString("n").takeIf { it.isNotBlank() } ?: throw MarkupChangedException()
            if (name in byName) throw MarkupChangedException()
            byName[name] = Episode(key, Jsoup.parseBodyFragment(name).text()) to value.optString("next").takeIf { it.isNotBlank() }
        }
        if (byName.isEmpty()) return emptyList()
        var next: String? = json.optJSONObject("")?.optString("next")?.takeIf { it.isNotBlank() }
            ?: byName.keys.singleOrNull() ?: throw MarkupChangedException()
        val ordered = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        while (next != null) {
            if (!seen.add(next)) throw MarkupChangedException()
            val (episode, following) = byName[next] ?: throw MarkupChangedException()
            ordered += episode
            next = following
        }
        if (ordered.size != byName.size) throw MarkupChangedException()
        return ordered
    }
}
