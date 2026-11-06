package ru.seasonvar.tv.data.smart

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.seasonvar.tv.data.*

interface SmartContinueCache {
    suspend fun read(owner: String): List<SeriesEntry>?
    suspend fun write(owner: String, entries: List<SeriesEntry>)
    suspend fun clear(owner: String)
}

object SmartContinueCodec {
    fun encode(entries: List<SeriesEntry>): String = JSONObject().put("historyOnly", true).put("entries", JSONArray(entries.map { e ->
        JSONObject().put("id", e.id).put("url", e.url).put("title", e.title).put("original", e.originalTitle)
            .put("poster", e.posterUrl).put("season", e.season).put("key", e.sourceKey).put("subtitle", e.update)
            .put("episode", e.initialSelection.episodeId).put("episodeLabel", e.initialSelection.episodeLabel)
            .put("translation", e.initialSelection.translationId).put("voiceLabel", e.initialSelection.translationLabel)
            .put("position", e.initialSelection.positionMillis)
    })).toString()

    fun decode(value: String?): List<SeriesEntry>? = runCatching {
        val document = JSONObject(value ?: return null)
        require(document.getBoolean("historyOnly"))
        val array = document.getJSONArray("entries")
        List(array.length()) { index ->
            val o = array.getJSONObject(index)
            val id = o.getString("id"); val url = o.getString("url")
            require(SeasonvarParser.pageUrl(url) == url && Regex("/serial-([0-9]+)").find(url)?.groupValues?.get(1) == id)
            val position = o.getLong("position"); require(position >= 0)
            val key = o.getString("key"); require(key.matches(Regex("smart:[0-9]+")))
            SeriesEntry(id, url, o.getString("title"), if (o.isNull("season")) null else o.getInt("season"),
                o.getString("subtitle"), o.getString("poster"), originalTitle = o.optString("original").takeIf { it.isNotBlank() },
                sourceKey = key, initialSelection = EntrySelection(o.getString("episode"), o.getString("episodeLabel"),
                    o.getString("translation"), o.getString("voiceLabel"), position))
        }.also { require(it.map { e -> e.key }.distinct().size == it.size) }
    }.getOrNull()
}

class PreferencesSmartContinueCache(context: Context) : SmartContinueCache {
    private val preferences = context.getSharedPreferences("smart-continue", Context.MODE_PRIVATE)
    override suspend fun read(owner: String): List<SeriesEntry>? = withContext(Dispatchers.IO) {
        SmartContinueCodec.decode(preferences.getString(owner, null))
    }
    override suspend fun write(owner: String, entries: List<SeriesEntry>) = withContext(Dispatchers.IO) {
        preferences.edit().putString(owner, SmartContinueCodec.encode(entries)).apply()
    }
    override suspend fun clear(owner: String) = withContext(Dispatchers.IO) { preferences.edit().remove(owner).apply() }
}
