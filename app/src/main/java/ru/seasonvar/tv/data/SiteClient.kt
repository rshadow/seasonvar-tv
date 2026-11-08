package ru.seasonvar.tv.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Callback
import okhttp3.Request
import ru.seasonvar.tv.data.network.NetworkClients
import ru.seasonvar.tv.data.network.NetworkSettings
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SiteClient(private val settings: NetworkSettings, private val sessions: ru.seasonvar.tv.data.account.SessionManager? = null) : HtmlSource, Call.Factory {
    private val clients = NetworkClients()

    private var lastJar: ru.seasonvar.tv.data.account.SessionCookies? = null
    private val sessionClients = mutableMapOf<ru.seasonvar.tv.data.network.NetworkConfiguration, okhttp3.OkHttpClient>()

    @Synchronized
    private fun client(jar: ru.seasonvar.tv.data.account.SessionCookies?): okhttp3.OkHttpClient {
        if (jar == null) return clients.forConfiguration(settings.configuration.value)
        if (lastJar !== jar) { sessionClients.clear(); lastJar = jar }
        return sessionClients.getOrPut(settings.configuration.value) {
            clients.forConfiguration(settings.configuration.value).newBuilder().cookieJar(jar).build()
        }
    }
    override fun newCall(request: Request): Call = client(sessions?.current()).newCall(request)

    val mediaCalls = object : Call.Factory {
        override fun newCall(request: Request): Call =
            clients.forMedia(settings.configuration.value).newCall(request)
    }

    suspend fun resource(url: String, referer: String): String =
        execute(Request.Builder().url(url).header("Referer", referer).build())

    override suspend fun get(url: String): String = execute(Request.Builder().url(url).build())

    override suspend fun bestFilters(filters: BestListFilters): String = execute(bestFiltersRequest(filters))

    override suspend fun sidebar(feed: PublicFeed): String = execute(sidebarRequest(feed))

    suspend fun accountGet(url: String, jar: ru.seasonvar.tv.data.account.SessionCookies): String =
        execute(Request.Builder().url(url).header("Referer", "https://seasonvar.ru/").build(), jar)

    suspend fun accountFilters(filters: ru.seasonvar.tv.data.account.PersonalListFilters,
        jar: ru.seasonvar.tv.data.account.SessionCookies): String = execute(personalFiltersRequest(filters), jar)

    suspend fun accountLogin(login: String, password: String, jar: ru.seasonvar.tv.data.account.SessionCookies): String =
        execute(Request.Builder().url("https://seasonvar.ru/?mod=login")
            .header("Referer", "https://seasonvar.ru/")
            .post(FormBody.Builder().add("login", login).add("password", password).build()).build(), jar, true)

    suspend fun accountMark(mark: ru.seasonvar.tv.data.account.PlaybackMark, jar: ru.seasonvar.tv.data.account.SessionCookies): String =
        execute(markRequest(mark), jar)

    suspend fun accountStatus(change: ru.seasonvar.tv.data.account.AccountStatusChange, jar: ru.seasonvar.tv.data.account.SessionCookies): String =
        execute(statusRequest(change), jar)

    suspend fun accountRemoveMark(mark: ru.seasonvar.tv.data.account.AccountListMark, jar: ru.seasonvar.tv.data.account.SessionCookies): String =
        execute(removeMarkRequest(mark), jar)

    suspend fun accountHistory(event: ru.seasonvar.tv.data.playback.PlaybackHistoryEvent,
        jar: ru.seasonvar.tv.data.account.SessionCookies): Unit {
        val index = jar.scriptCookieNameIndex(event.pageUrl.toHttpUrl(), "svid1")
        execute(historyRequest(event, index), jar, readBody = false)
    }

    private suspend fun execute(initial: Request, jar: ru.seasonvar.tv.data.account.SessionCookies? = sessions?.current(), loginRedirect: Boolean = false,
        readBody: Boolean = true): String {
        var request = initial
        repeat(5) {
            val result = client(jar).newCall(request.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Android TV) SeasonvarTV/" + ru.seasonvar.tv.BuildConfig.VERSION_NAME)
                .header("Accept-Language", "ru-RU,ru;q=0.9")
                .build()).awaitResult(readBody)
            if (result.status in setOf(301, 302, 303, 307, 308)) {
                if (request.method != "GET" && (!loginRedirect || result.status !in setOf(301, 302, 303))) {
                    throw IOException("Unexpected redirect")
                }
                val destination = java.net.URI(request.url.toString()).resolve(result.location ?: "")
                if (destination.scheme != "https" ||
                    destination.host !in setOf("seasonvar.ru", "www.seasonvar.ru") ||
                    destination.userInfo != null || destination.port !in listOf(-1, 443)
                ) throw IOException("Unexpected redirect")
                request = request.newBuilder().url(destination.toASCIIString()).apply {
                    if (request.method != "GET") get()
                }.build()
            } else {
                if (result.status != 200) throw SiteHttpException(result.status)
                return result.body
            }
        }
        throw IOException("Too many redirects")
    }

    companion object {
        internal fun bestFiltersRequest(filters: BestListFilters): Request = Request.Builder().url(filters.url())
            .header("Referer", filters.url()).header("Origin", "https://seasonvar.ru")
            .header("X-Requested-With", "XMLHttpRequest")
            .post(FormBody.Builder().apply { filters.form().forEach { (name, value) -> add(name, value) } }.build()).build()
        internal fun personalFiltersRequest(filters: ru.seasonvar.tv.data.account.PersonalListFilters): Request =
            Request.Builder().url("https://seasonvar.ru/ajax.php")
                .header("Referer", "https://seasonvar.ru/resume").header("Origin", "https://seasonvar.ru")
                .header("X-Requested-With", "XMLHttpRequest")
                .post(FormBody.Builder().apply { filters.form().forEach { (name, value) -> add(name, value) } }.build()).build()

        internal fun historyRequest(event: ru.seasonvar.tv.data.playback.PlaybackHistoryEvent, cookieNameIndex: Int): Request =
            Request.Builder().url("https://seasonvar.ru/plStat.php")
                .header("Referer", event.pageUrl).header("Origin", "https://seasonvar.ru")
                .header("X-Requested-With", "XMLHttpRequest")
                .post(FormBody.Builder().apply { event.form(cookieNameIndex).forEach { (name, value) -> add(name, value) } }.build())
                .build()

        internal fun removeMarkRequest(mark: ru.seasonvar.tv.data.account.AccountListMark): Request = Request.Builder()
            .url("https://seasonvar.ru/jsonMark.php")
            .header("Referer", mark.pageUrl)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json")
            .post(FormBody.Builder().add("delId", mark.seasonId).build())
            .build()

        internal fun markRequest(mark: ru.seasonvar.tv.data.account.PlaybackMark): Request = Request.Builder()
            .url("https://seasonvar.ru/jsonMark.php")
            .header("Referer", mark.pageUrl)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json")
            .post(FormBody.Builder().apply { mark.form().forEach { (name, value) -> add(name, value) } }.build())
            .build()

        internal fun statusRequest(change: ru.seasonvar.tv.data.account.AccountStatusChange): Request = Request.Builder()
            .url("https://seasonvar.ru/jsonMark.php")
            .header("Referer", change.target.pageUrl)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json")
            .post(FormBody.Builder().apply { change.form().forEach { (name, value) -> add(name, value) } }.build())
            .build()

        internal fun sidebarRequest(feed: PublicFeed): Request {
            val mode = when (feed) {
                PublicFeed.UPDATES -> "new"
                PublicFeed.TOP -> "pop"
                PublicFeed.NEWEST -> "newest"
                PublicFeed.TODAY, PublicFeed.BEST, PublicFeed.ANNOUNCEMENTS -> throw IllegalArgumentException("Feed is not a sidebar feed")
            }
            return Request.Builder()
                .url("https://seasonvar.ru/ajax.php?mode=" + mode)
                .header("Referer", "https://seasonvar.ru/")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "text/html")
                .post(FormBody.Builder()
                    .add("ganre", "").add("country", "").add("block", "0").add("main", "1")
                    .build())
                .build()
        }
    }

    private data class Result(val status: Int, val location: String?, val body: String)

    private suspend fun Call.awaitResult(readBody: Boolean): Result = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (ru.seasonvar.tv.BuildConfig.DEBUG) android.util.Log.w("SeasonvarNetwork", e.javaClass.simpleName)
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (!readBody) return@use Result(it.code, it.header("Location"), "")
                        val body = it.body
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
