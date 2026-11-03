package ru.seasonvar.tv.data.account

import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import ru.seasonvar.tv.data.MarkupChangedException
import ru.seasonvar.tv.data.SeasonvarParser
import java.net.URI

/** Permanent account mark; zero time means the episode is marked as watched. */
data class PlaybackMark(
    val seasonId: String,
    val pageUrl: String,
    val episode: String,
    val translationId: String,
    val positionSeconds: Long,
) {
    init {
        require(seasonId.matches(Regex("[1-9][0-9]*")))
        require(SeasonvarParser.pageUrl(pageUrl) == pageUrl)
        require(Regex("/serial-([0-9]+)").find(URI(pageUrl).path)?.groupValues?.get(1) == seasonId)
        require(episode.isNotBlank() && episode.length <= 80 && !episode.startsWith("-"))
        require(episode.none { it.isISOControl() || it in "|&#" })
        require(translationId.matches(Regex("[0-9]+")) && translationId != "68")
        require(positionSeconds in 0..Int.MAX_VALUE.toLong())
    }

    fun form(): Map<String, String> = linkedMapOf(
        "pauseadd" to "true", "id" to seasonId, "seria" to episode,
        "tran" to translationId, "minute" to (positionSeconds / 60).toString(),
        "second" to (positionSeconds % 60).toString(),
    )
}

class MarkSaveRejectedException : Exception("Seasonvar rejected the mark")
class MarkVerificationException : Exception("Saved mark was not confirmed")

data class PlaybackMarkSnapshot(val mark: PlaybackMark? = null, val status: Int? = null)

object PlaybackMarkParser {
    private val assignment = Regex("""\bvar\s+mark\s*=""")
    private val fragment = Regex("""rewind=(.+)_seriya(?:_na_([0-9]+)_minute_([0-9]+)_sekunde)?""")

    fun parse(html: String, seasonId: String, pageUrl: String): PlaybackMark? = snapshot(html, seasonId, pageUrl).mark

    fun snapshot(html: String, seasonId: String, pageUrl: String): PlaybackMarkSnapshot {
        val doc = Jsoup.parse(html, pageUrl)
        if (doc.selectFirst(".pgs-sinfo[data-id-season]")?.attr("data-id-season") != seasonId) {
            throw MarkupChangedException()
        }
        val declarations = doc.select("script:not([src])").flatMap { script ->
            assignment.findAll(script.data()).map { match -> script.data().substring(match.range.last + 1) }.toList()
        }
        if (declarations.size != 1) throw MarkupChangedException()
        try {
            val value = JSONTokener(declarations.single()).nextValue() as? JSONObject ?: throw MarkupChangedException()
            val href = value.opt("href") as? String ?: throw MarkupChangedException()
            if (href.isBlank()) return PlaybackMarkSnapshot()
            val resolved = URI(pageUrl).resolve(href)
            val target = SeasonvarParser.pageUrl(resolved.toString().substringBefore('#')) ?: throw MarkupChangedException()
            if (Regex("/serial-([0-9]+)").find(URI(target).path)?.groupValues?.get(1) != seasonId) throw MarkupChangedException()
            val match = fragment.matchEntire(resolved.fragment.orEmpty()) ?: throw MarkupChangedException()
            val episode = match.groupValues[1]
            if (episode in setOf("-1", "-2", "-3")) return PlaybackMarkSnapshot(status = episode.toInt())
            val minutes = match.groupValues[2].ifEmpty { "0" }.toLong()
            val seconds = match.groupValues[3].ifEmpty { "0" }.toInt()
            if (minutes > Int.MAX_VALUE / 60 || seconds !in 0..59) throw MarkupChangedException()
            val translation = value.optString("trans", "").ifBlank { "0" }
            return PlaybackMarkSnapshot(PlaybackMark(seasonId, pageUrl, episode, translation, minutes * 60 + seconds))
        } catch (error: MarkupChangedException) {
            throw error
        } catch (_: Exception) {
            throw MarkupChangedException()
        }
    }

    fun requireSuccess(json: String) {
        val accepted = try { JSONObject(json).opt("msg") == "success" } catch (_: Exception) { false }
        if (!accepted) throw MarkSaveRejectedException()
    }
}

internal fun markSupersedes(current: PlaybackMarkSnapshot, desired: PlaybackMark): Boolean {
    if (current.status in setOf(-2, -3)) return true
    val previous = current.mark ?: return false
    if (previous.episode == desired.episode) {
        return previous.positionSeconds == 0L || previous == desired
    }
    val previousNumber = previous.episode.toBigDecimalOrNull() ?: return false
    val desiredNumber = desired.episode.toBigDecimalOrNull() ?: return false
    return previousNumber > desiredNumber
}
