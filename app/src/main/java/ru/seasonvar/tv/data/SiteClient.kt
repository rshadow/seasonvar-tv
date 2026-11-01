package ru.seasonvar.tv.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.Callback
import okhttp3.Request
import ru.seasonvar.tv.data.network.NetworkClients
import ru.seasonvar.tv.data.network.NetworkSettings
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SiteClient(private val settings: NetworkSettings) : HtmlSource, Call.Factory {
    private val clients = NetworkClients()

    override fun newCall(request: Request): Call =
        clients.forConfiguration(settings.configuration.value).newCall(request)

    override suspend fun get(url: String): String = execute(Request.Builder().url(url).build())

    override suspend fun updates(): String = execute(updatesRequest())

    private suspend fun execute(initial: Request): String {
        var request = initial
        repeat(5) {
            val result = newCall(request.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Android TV) SeasonvarTV/" + ru.seasonvar.tv.BuildConfig.VERSION_NAME)
                .header("Accept-Language", "ru-RU,ru;q=0.9")
                .build()).awaitResult()
            if (result.status in setOf(301, 302, 303, 307, 308)) {
                if (request.method != "GET") throw IOException("Unexpected AJAX redirect")
                val destination = java.net.URI(request.url.toString()).resolve(result.location ?: "")
                if (destination.scheme != "https" ||
                    destination.host !in setOf("seasonvar.ru", "www.seasonvar.ru") ||
                    destination.userInfo != null || destination.port !in listOf(-1, 443)
                ) throw IOException("Unexpected redirect")
                request = request.newBuilder().url(destination.toASCIIString()).build()
            } else {
                if (result.status != 200) throw SiteHttpException(result.status)
                return result.body
            }
        }
        throw IOException("Too many redirects")
    }

    companion object {
        internal fun updatesRequest(): Request = Request.Builder()
            .url("https://seasonvar.ru/ajax.php?mode=new")
            .header("Referer", "https://seasonvar.ru/")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "text/html")
            .post(FormBody.Builder()
                .add("ganre", "").add("country", "").add("block", "0").add("main", "1")
                .build())
            .build()
    }

    private data class Result(val status: Int, val location: String?, val body: String)

    private suspend fun Call.awaitResult(): Result = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (ru.seasonvar.tv.BuildConfig.DEBUG) android.util.Log.w("SeasonvarNetwork", e.javaClass.simpleName + ": " + e.message)
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val body = it.body ?: throw IOException("Empty body")
                        val source = body.source()
                        source.request(8L * 1024 * 1024 + 1)
                        if (source.buffer.size > 8L * 1024 * 1024) {
                            throw IOException("Response too large")
                        }
                        Result(it.code, it.header("Location"), source.buffer.readString(
                            body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                        ))
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }
}
