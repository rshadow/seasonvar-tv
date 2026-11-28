package ru.seasonvar.tv.data.update

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Public release descriptions are independent of APK availability and integrity metadata. */
data class ReleaseNotes(val version: String, val publishedAt: String, val body: String) {
    fun isInstalled(installed: String): Boolean = ReleaseVersion.parse(version) == ReleaseVersion.installed(installed)
}

object ReleaseNotesParser {
    const val API_URL = "https://api.github.com/repos/rshadow/seasonvar-tv/releases"
    const val PAGE_SIZE = 100
    const val MAX_BYTES = 8 * 1024 * 1024

    fun parsePage(json: String): Pair<List<ReleaseNotes>, Boolean> = parse {
        val items = JSONArray(json)
        val notes = (0 until items.length()).mapNotNull { index ->
            val item = items.getJSONObject(index)
            if (item.getBoolean("draft") || item.getBoolean("prerelease") || item.isNull("published_at")) null
            else {
                val tag = item.getString("tag_name")
                if (!tag.startsWith("v") || ReleaseVersion.parse(tag) == null) null
                else note(tag.removePrefix("v"), item.getString("published_at"),
                    if (item.isNull("body")) "" else item.getString("body"))
            }
        }
        notes to (items.length() == PAGE_SIZE)
    }

    fun relevant(notes: List<ReleaseNotes>, installed: String): List<ReleaseNotes> {
        val version = ReleaseVersion.installed(installed) ?: throw UpdateFormatException()
        return notes.filter { (ReleaseVersion.parse(it.version) ?: throw UpdateFormatException()) >= version }
            .distinctBy { it.version }.sortedByDescending { ReleaseVersion.parse(it.version) }
    }

    fun encode(notes: List<ReleaseNotes>): String = JSONObject().put("schema", 1).put("releases",
        JSONArray().apply { notes.forEach { put(JSONObject().put("version", it.version)
            .put("published_at", it.publishedAt).put("body", it.body)) } }).toString()

    fun decode(json: String): List<ReleaseNotes> = parse {
        val root = JSONObject(json)
        if (root.getInt("schema") != 1) throw UpdateFormatException()
        val items = root.getJSONArray("releases")
        (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            note(item.getString("version"), item.getString("published_at"), item.getString("body"))
        }
    }

    private fun note(version: String, date: String, body: String): ReleaseNotes {
        if (ReleaseVersion.parse(version) == null) throw UpdateFormatException()
        Instant.parse(date)
        return ReleaseNotes(version, date, body)
    }

    private inline fun <T> parse(block: () -> T): T = try { block() }
    catch (error: UpdateFormatException) { throw error }
    catch (_: Exception) { throw UpdateFormatException() }
}
