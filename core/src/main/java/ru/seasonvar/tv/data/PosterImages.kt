package ru.seasonvar.tv.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object PosterImages {
    private val posterPath = Regex("/oblojka/(?:small/|large/)?([0-9]+\\.(?:jpg|jpeg|png|webp))")

    fun largeUrl(source: String): String? {
        val url = source.toHttpUrlOrNull() ?: return null
        if (url.host != "cdn.svstat.net" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.port !in setOf(80, 443)) return null
        val image = posterPath.matchEntire(url.encodedPath)?.groupValues?.get(1) ?: return null
        return url.newBuilder().scheme("https").port(443).encodedPath("/oblojka/large/$image").build().toString()
    }
}
