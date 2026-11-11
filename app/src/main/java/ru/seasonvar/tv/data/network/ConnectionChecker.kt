package ru.seasonvar.tv.data.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.jsoup.Jsoup
import ru.seasonvar.tv.data.MarkupChangedException
import ru.seasonvar.tv.data.SeriesDetailsParser
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.SiteHttpException
import ru.seasonvar.tv.data.account.SessionCookies
import ru.seasonvar.tv.data.playback.PlaybackRequest
import ru.seasonvar.tv.data.playback.VideoParser
import ru.seasonvar.tv.data.settings.ConnectionCheckSettings as Settings
import java.io.IOException
import java.io.InterruptedIOException
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ConnectionTarget { SITE, POSTERS, VIDEO }
enum class ConnectionStatus { CHECKING, AVAILABLE, FAILED }
enum class ConnectionFailure { TIMEOUT, NETWORK, TLS, HTTP, CONTENT, SOURCE }
data class ConnectionResult(val status: ConnectionStatus, val failure: ConnectionFailure? = null) {
    companion object {
        val checking = ConnectionResult(ConnectionStatus.CHECKING)
        val available = ConnectionResult(ConnectionStatus.AVAILABLE)
        fun failed(reason: ConnectionFailure) = ConnectionResult(ConnectionStatus.FAILED, reason)
    }
}

class ConnectionChecker internal constructor(
    private val pac: ru.seasonvar.tv.data.network.pac.PacRouteProvider? = null,
    private val clientFactory: ((NetworkConfiguration) -> OkHttpClient)? = null,
) {
    suspend fun check(configuration: NetworkConfiguration, report: (ConnectionTarget, ConnectionResult) -> Unit) {
        val transports = if (clientFactory == null)
            NetworkClients(Settings.CONNECT_TIMEOUT_MILLIS, Settings.REQUEST_TIMEOUT_MILLIS, pac) else null
        val transport = transports?.forConfiguration(configuration) ?: checkNotNull(clientFactory)(configuration)
        val client = transport.newBuilder().cache(null).cookieJar(SessionCookies())
            .followRedirects(true).followSslRedirects(false).build()
        try {
            coroutineScope {
                val page = async {
                    var html: String? = null
                    val result = outcome {
                        html = fetch(client, Settings.PAGE_URL) { response ->
                            val text = response.boundedBody(Settings.HTML_LIMIT_BYTES).toString(Charsets.UTF_8)
                            if (Jsoup.parse(text).selectFirst(".pgs-sinfo[data-id-season]")
                                    ?.attr("data-id-season") != Settings.SEASON_ID) throw MarkupChangedException()
                            text
                        }
                    }
                    report(ConnectionTarget.SITE, result)
                    html.takeIf { result.status == ConnectionStatus.AVAILABLE }
                }
                launch {
                    report(ConnectionTarget.POSTERS, outcome {
                        fetch(client, Settings.POSTER_URL) { response ->
                            val bytes = response.boundedBody(Settings.POSTER_LIMIT_BYTES)
                            if (response.body.contentType()?.toString()?.substringBefore(';') != "image/jpeg" ||
                                bytes.size < 4 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte() ||
                                bytes[bytes.lastIndex - 1] != 0xff.toByte() || bytes.last() != 0xd9.toByte()) {
                                throw MarkupChangedException()
                            }
                        }
                    })
                }
                launch {
                    val html = page.await()
                    report(ConnectionTarget.VIDEO, if (html == null) ConnectionResult.failed(ConnectionFailure.SOURCE)
                    else outcome {
                        val request = withContext(Dispatchers.Default) { playbackRequest(html) }
                        val playlistUrl = withContext(Dispatchers.Default) { VideoParser.playlistUrl(html, request) }
                        val playlist = fetch(client, playlistUrl) {
                            it.boundedBody(Settings.HTML_LIMIT_BYTES).toString(Charsets.UTF_8)
                        }
                        val url = withContext(Dispatchers.Default) { VideoParser.source(playlist, request).url }
                        fetch(client, url, video = true) { response ->
                            if (response.code == 206 &&
                                response.header("Content-Range")?.startsWith("bytes 0-") != true) throw MarkupChangedException()
                            val prefix = response.body.source().readByteArray(Settings.VIDEO_PREFIX_BYTES)
                            if (prefix.size < 12 || prefix.copyOfRange(4, 8).toString(Charsets.US_ASCII) != "ftyp") {
                                throw MarkupChangedException()
                            }
                        }
                    })
                }
            }
        } finally {
            transports?.close()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun playbackRequest(html: String): PlaybackRequest {
        val entry = SeriesEntry(Settings.SEASON_ID, Settings.PAGE_URL, "Проверка подключения", 3, "", Settings.POSTER_URL)
        val details = SeriesDetailsParser.parse(html, entry)
        val translation = details.translations.firstOrNull { !it.episodes.isNullOrEmpty() }
            ?: throw MarkupChangedException()
        val episode = translation.episodes!!.first()
        return PlaybackRequest(entry.id, entry.url, translation.id, episode.id, episode.label, entry.title)
    }

    private suspend fun outcome(action: suspend () -> Unit): ConnectionResult = try {
        action()
        ConnectionResult.available
    } catch (error: CancellationException) { throw error }
    catch (error: Exception) {
        ConnectionResult.failed(when (error) {
            is InterruptedIOException -> ConnectionFailure.TIMEOUT
            is SSLException -> ConnectionFailure.TLS
            is SiteHttpException -> ConnectionFailure.HTTP
            is IOException -> ConnectionFailure.NETWORK
            else -> ConnectionFailure.CONTENT
        })
    }

    private suspend fun <T> fetch(client: OkHttpClient, url: String, video: Boolean = false,
                                  read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache, no-store")
            .header("Referer", Settings.PAGE_URL).apply {
                if (video) header("Range", "bytes=0-${Settings.VIDEO_PREFIX_BYTES - 1}")
                    .header("Accept-Encoding", "identity")
            }.build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (it.code != 200 && !(video && it.code == 206)) throw SiteHttpException(it.code)
                        read(it)
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private fun Response.boundedBody(limit: Long): ByteArray {
        val buffer = Buffer()
        val source = body.source()
        while (buffer.size <= limit) {
            if (source.read(buffer, minOf(8_192, limit + 1 - buffer.size)) == -1L) return buffer.readByteArray()
        }
        throw MarkupChangedException()
    }
}
