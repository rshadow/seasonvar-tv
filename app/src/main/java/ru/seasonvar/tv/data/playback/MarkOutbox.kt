package ru.seasonvar.tv.data.playback

import android.content.Context
import org.json.JSONObject
import ru.seasonvar.tv.data.account.AccountUser
import ru.seasonvar.tv.data.account.PlaybackMark
import java.security.MessageDigest

internal fun markOwner(user: AccountUser?): String? {
    val id = user?.id?.takeIf { it.matches(Regex("[1-9][0-9]*")) } ?: return null
    return MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

data class PendingMark(val owner: String, val mark: PlaybackMark, val revision: Long)

object MarkOutboxCodec {
    fun encode(value: PendingMark): String = JSONObject()
        .put("owner", value.owner).put("revision", value.revision)
        .put("season", value.mark.seasonId).put("page", value.mark.pageUrl)
        .put("episode", value.mark.episode).put("translation", value.mark.translationId)
        .put("seconds", value.mark.positionSeconds).toString()

    fun decode(json: String): PendingMark? = runCatching {
        val value = JSONObject(json)
        val owner = value.getString("owner")
        val revision = value.getLong("revision")
        require(owner.matches(Regex("[0-9a-f]{64}")) && revision > 0)
        PendingMark(owner, PlaybackMark(value.getString("season"), value.getString("page"),
            value.getString("episode"), value.getString("translation"), value.getLong("seconds")), revision)
    }.getOrNull()
}

interface MarkOutbox {
    fun all(): List<PendingMark>
    fun put(value: PendingMark)
    fun remove(value: PendingMark)
}

class PreferencesMarkOutbox(context: Context) : MarkOutbox {
    private val preferences = context.getSharedPreferences("account-mark-outbox", Context.MODE_PRIVATE)
    private fun key(value: PendingMark) = value.owner + "-" + value.mark.seasonId
    override fun all() = preferences.all.values.filterIsInstance<String>().mapNotNull(MarkOutboxCodec::decode)
    override fun put(value: PendingMark) { preferences.edit().putString(key(value), MarkOutboxCodec.encode(value)).apply() }
    override fun remove(value: PendingMark) {
        val current = preferences.getString(key(value), null)?.let(MarkOutboxCodec::decode)
        if (current == value) preferences.edit().remove(key(value)).apply()
    }
}
