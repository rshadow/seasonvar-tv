package ru.seasonvar.tv.data.home

import java.net.URI
import java.security.MessageDigest

enum class HomeChannel(val storageId: String, val personal: Boolean) {
    CONTINUE("continue", true), WANT("want", true), NEWEST("newest", false);
    companion object {
        fun decode(values: Set<String>) = entries.filter { it.storageId in values }.toSet()
    }
}

object HomeLink {
    fun token(channel: HomeChannel, season: String): String = MessageDigest.getInstance("SHA-256")
        .digest((channel.storageId + ":" + season).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun uri(token: String) = "seasonvar://home/play/$token"
    fun parse(value: String?): String? = runCatching {
        val uri = URI(value ?: return null)
        if (uri.scheme != "seasonvar" || uri.rawAuthority != "home" || uri.rawQuery != null || uri.rawFragment != null) return null
        uri.rawPath.removePrefix("/play/").takeIf { uri.rawPath == "/play/$it" && it.matches(Regex("[0-9a-f]{64}")) }
    }.getOrNull()
}
