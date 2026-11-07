package ru.seasonvar.tv.data.playback

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.seasonvar.tv.data.ResumePoint

object ProgressCodec {
    fun encode(point: ResumePoint): String = JSONObject()
        .put("season", point.seasonId).put("translation", point.translationId)
        .put("episode", point.episodeId).put("position", point.positionMillis).toString()

    fun decode(json: String?, seasonId: String): ResumePoint? = runCatching {
        val value = JSONObject(json ?: return null)
        if (value.getString("season") != seasonId) return null
        val position = value.getLong("position")
        if (position <= 0) return null
        ResumePoint(seasonId, value.getString("translation"), value.getString("episode"), position)
    }.getOrNull()

    fun decodeHome(json: String?, seasonId: String, completed: Boolean?): ru.seasonvar.tv.data.home.LocalPlayback? = runCatching {
        val point = decode(json, seasonId) ?: run {
            val value = JSONObject(json ?: return null)
            if (completed == null || value.getString("season") != seasonId || value.getLong("position") != 0L) return null
            ResumePoint(seasonId, value.getString("translation"), value.getString("episode"), 0)
        }
        ru.seasonvar.tv.data.home.LocalPlayback(point, completed == true)
    }.getOrNull()
}

class PlaybackProgress(context: Context) {
    private val preferences = context.getSharedPreferences("playback-progress", Context.MODE_PRIVATE)
    suspend fun read(seasonId: String): ResumePoint? = withContext(Dispatchers.IO) {
        runCatching { ProgressCodec.decode(preferences.getString("season-" + seasonId, null), seasonId) }.getOrNull()
    }
    suspend fun latest(seasons: List<String>): ru.seasonvar.tv.data.home.LocalPlayback? = withContext(Dispatchers.IO) {
        seasons.reversed().mapNotNull { id ->
            runCatching {
                val raw = preferences.getString("season-$id", null) ?: return@runCatching null
                val completed = if (preferences.contains("completed-$id")) preferences.getBoolean("completed-$id", false) else null
                ProgressCodec.decodeHome(raw, id, completed)?.let { preferences.getLong("updated-$id", 0) to it }
            }.getOrNull()
        }.maxByOrNull { it.first }?.second
    }
    fun save(point: ResumePoint, completed: Boolean = false) {
        preferences.edit().putString("season-" + point.seasonId, ProgressCodec.encode(point))
            .putLong("updated-" + point.seasonId, System.currentTimeMillis())
            .putBoolean("completed-" + point.seasonId, completed).apply()
    }
}
