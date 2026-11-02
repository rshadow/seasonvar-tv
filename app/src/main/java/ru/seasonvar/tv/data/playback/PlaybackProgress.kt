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
}

class PlaybackProgress(context: Context) {
    private val preferences = context.getSharedPreferences("playback-progress", Context.MODE_PRIVATE)
    suspend fun read(seasonId: String): ResumePoint? = withContext(Dispatchers.IO) {
        runCatching { ProgressCodec.decode(preferences.getString("season-" + seasonId, null), seasonId) }.getOrNull()
    }
    fun save(point: ResumePoint) {
        preferences.edit().putString("season-" + point.seasonId, ProgressCodec.encode(point)).apply()
    }
}
