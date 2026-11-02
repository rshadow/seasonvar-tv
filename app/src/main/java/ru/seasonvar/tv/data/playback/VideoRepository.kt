package ru.seasonvar.tv.data.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.seasonvar.tv.data.MarkupChangedException
import java.net.URI

class VideoRepository(private val resource: suspend (String, String) -> String) {
    suspend fun resolve(request: PlaybackRequest): VideoSource = withContext(Dispatchers.IO) {
        val origin = try { URI(request.pageUrl) } catch (_: Exception) { throw MarkupChangedException() }
        if (origin.scheme != "https" || origin.host !in setOf("seasonvar.ru", "www.seasonvar.ru") ||
            origin.userInfo != null || origin.port !in listOf(-1, 443)) throw MarkupChangedException()
        val page = resource(request.pageUrl, "https://seasonvar.ru/")
        val playlist = VideoParser.playlistUrl(page, request)
        VideoParser.source(resource(playlist, request.pageUrl), request)
    }
}
