package ru.seasonvar.tv.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ActorImages {
    const val PLACEHOLDER_URL = "https://cdn.svstat.net/a/0/0.jpg"

    fun photoUrl(source: String): String {
        val url = source.toHttpUrlOrNull() ?: return PLACEHOLDER_URL
        if (url.host != "cdn.svstat.net" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.port !in setOf(80, 443) || !url.encodedPath.startsWith("/a/")) return PLACEHOLDER_URL
        return url.newBuilder().scheme("https").port(443)
            .encodedPath(url.encodedPath.replaceFirst("/a/s/", "/a/")).build().toString()
    }
}
