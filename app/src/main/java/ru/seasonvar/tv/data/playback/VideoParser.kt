package ru.seasonvar.tv.data.playback

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import ru.seasonvar.tv.data.MarkupChangedException
import java.net.URI
import java.util.Base64

object VideoParser {
    private val declaration = Regex("""\bvar\s+pl\s*=\s*\{([^;]+)\}""")
    private val member = Regex("""["']?(\d+)["']?\s*:\s*(["'])(.*?)\2""")
    private val assignment = Regex("""\bpl\[\s*["']?(\d+)["']?\s*\]\s*=\s*(["'])(.*?)\2""")
    private val videoUrl = Regex("""(?:https?:)?//[^\s,\[\]]+""")

    fun playlistUrl(html: String, request: PlaybackRequest): String {
        val info = Jsoup.parse(html, request.pageUrl).selectFirst(".pgs-sinfo[data-id-season]")
            ?: throw MarkupChangedException()
        if (info.attr("data-id-season") != request.seasonId) throw MarkupChangedException()
        val scripts = Jsoup.parse(html).select("script:not([src])").joinToString("\n") { it.data() }
        val paths = linkedMapOf<String, String>()
        declaration.findAll(scripts).forEach { block -> member.findAll(block.groupValues[1]).forEach {
            paths[it.groupValues[1]] = unescape(it.groupValues[3])
        } }
        assignment.findAll(scripts).forEach { paths[it.groupValues[1]] = unescape(it.groupValues[3]) }
        val path = paths[request.translationId] ?: throw VideoUnavailableException()
        val uri = URI(request.pageUrl).resolve(path)
        if (uri.host !in setOf("seasonvar.ru", "www.seasonvar.ru") || uri.userInfo != null ||
            uri.port !in listOf(-1, 443) || uri.scheme !in setOf("https", "http")) throw MarkupChangedException()
        return uri.toASCIIString().replaceFirst("http:", "https:")
    }

    fun source(json: String, request: PlaybackRequest): VideoSource {
        val leaves = mutableListOf<JSONObject>()
        fun flatten(array: JSONArray, depth: Int) {
            if (depth > 8) throw MarkupChangedException()
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: throw MarkupChangedException()
                val folder = row.optJSONArray("folder") ?: row.optJSONArray("playlist")
                if (folder != null) flatten(folder, depth + 1) else leaves += row
            }
        }
        try { flatten(JSONArray(json), 0) } catch (_: org.json.JSONException) { throw MarkupChangedException() }
        val matches = leaves.filter { clean(it.optString("id")) == clean(request.episodeLabel) }
        if (matches.size != 1) throw VideoUnavailableException()
        val value = matches.single().optString("file")
        if (value.isBlank()) throw VideoUnavailableException()
        val decoded = when {
            value.startsWith("#2") -> try {
                // Public PlayerJS 9.97.2 marker bk0="ololo", not an authentication key.
                val encoded = value.drop(2).replaceFirst("//b2xvbG8=", "")
                String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) { throw MarkupChangedException() }
            value.startsWith("#") -> throw VideoUnavailableException()
            else -> value
        }
        val found = videoUrl.find(decoded)?.value ?: throw VideoUnavailableException()
        val url = when {
            found.startsWith("//") -> "https:" + found
            found.startsWith("http:") -> "https:" + found.drop(5)
            else -> found
        }
        val uri = try { URI(url) } catch (_: Exception) { throw MarkupChangedException() }
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null ||
            uri.port !in listOf(-1, 443)) throw MarkupChangedException()
        return VideoSource(uri.toASCIIString())
    }

    private fun clean(value: String) = Jsoup.parseBodyFragment(value).text().trim()
    private fun unescape(value: String) = value.replace("\\/", "/").replace("\\u0026", "&")
}
