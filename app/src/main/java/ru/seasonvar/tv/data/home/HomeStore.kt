package ru.seasonvar.tv.data.home

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.SeasonvarParser

data class HomeCard(val channel: HomeChannel, val entry: SeriesEntry, val owner: String?)

class HomeStore(context: Context) {
    private val preferences = context.getSharedPreferences("home-channels", Context.MODE_PRIVATE)
    private val mutableEnabled = MutableStateFlow(HomeChannel.decode(preferences.getStringSet("enabled", emptySet()).orEmpty()))
    val enabled = mutableEnabled.asStateFlow()
    fun setEnabled(channel: HomeChannel, enabled: Boolean) {
        val next = if (enabled) mutableEnabled.value + channel else mutableEnabled.value - channel
        preferences.edit().putStringSet("enabled", next.map { it.storageId }.toSet()).apply()
        mutableEnabled.value = next
    }
    fun read(token: String): HomeCard? = decode(preferences.getString("card-$token", null))
    fun cards(channel: HomeChannel): Map<String, HomeCard> = preferences.all.mapNotNull { (key, value) ->
        if (!key.startsWith("card-") || value !is String) null
        else decode(value)?.takeIf { it.channel == channel }?.let { key.removePrefix("card-") to it }
    }.toMap()
    fun write(token: String, card: HomeCard) {
        val e = card.entry
        preferences.edit().putString("card-$token", JSONObject().put("channel", card.channel.storageId)
            .put("owner", card.owner).put("id", e.id).put("url", e.url).put("title", e.title)
            .put("poster", e.posterUrl).put("season", e.season).put("subtitle", e.update).toString()).commit()
    }
    fun remove(token: String) { preferences.edit().remove("card-$token").commit() }
    private fun decode(raw: String?): HomeCard? = runCatching {
        val o = JSONObject(raw ?: return null)
        val channel = HomeChannel.entries.single { it.storageId == o.getString("channel") }
        val id = o.getString("id"); val url = o.getString("url")
        require(SeasonvarParser.pageUrl(url) == url && Regex("/serial-([0-9]+)").find(url)?.groupValues?.get(1) == id)
        HomeCard(channel, SeriesEntry(id, url, o.getString("title"),
            if (o.isNull("season")) null else o.getInt("season"), o.optString("subtitle"), o.getString("poster")),
            if (o.isNull("owner")) null else o.getString("owner"))
    }.getOrNull()
}
