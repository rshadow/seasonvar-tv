package ru.seasonvar.tv.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

interface UpdateSource {
    suspend fun latest(): String?
    suspend fun download(asset: ReleaseAsset, destination: File, progress: (Int) -> Unit)
}

class GitHubUpdateSource internal constructor(
    // GitHub requests never share Seasonvar cookies, headers or DPI transport.
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.MINUTES).build(),
) : UpdateSource {
    override suspend fun latest(): String? = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(GitHubReleaseParser.API_URL)
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Seasonvar-TV").build()).awaitResponse().use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw UpdateHttpException(response.code)
            if (response.body.contentLength() > 1024 * 1024) throw UpdateFormatException()
            val bytes = java.io.ByteArrayOutputStream()
            response.body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > 1024 * 1024) throw UpdateFormatException()
                    bytes.write(buffer, 0, count)
                }
            }
            bytes.toString("UTF-8")
        }
    }

    override suspend fun download(asset: ReleaseAsset, destination: File, progress: (Int) -> Unit) =
        withContext(Dispatchers.IO) {
            var url = asset.url
            repeat(6) { redirect ->
                if (!GitHubReleaseParser.allowedDownloadUrl(url)) throw UpdateFormatException()
                client.newCall(Request.Builder().url(url).header("User-Agent", "Seasonvar-TV").build())
                    .awaitResponse().use { response ->
                        if (response.code in setOf(301, 302, 303, 307, 308)) {
                            if (redirect == 5) throw UpdateFormatException()
                            url = response.header("Location")?.let { response.request.url.resolve(it)?.toString() }
                                ?: throw UpdateFormatException()
                        } else {
                            if (!response.isSuccessful) throw UpdateHttpException(response.code)
                            val length = response.body.contentLength()
                            if (length != -1L && length != asset.size) throw UpdateIntegrityException()
                            response.body.byteStream().use { input -> destination.outputStream().use { output ->
                                val buffer = ByteArray(32 * 1024)
                                var received = 0L
                                var lastPercent = -1
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    received += count
                                    if (received > asset.size || received > GitHubReleaseParser.MAX_APK_SIZE) throw UpdateIntegrityException()
                                    output.write(buffer, 0, count)
                                    val percent = (received * 100 / asset.size).toInt()
                                    if (percent != lastPercent) { lastPercent = percent; progress(percent) }
                                }
                            } }
                            return@withContext
                        }
                    }
            }
        }
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
