package ru.seasonvar.tv.data.network

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.settings.ConnectionCheckSettings as Settings
import java.io.IOException
import java.util.Collections

class ConnectionCheckerTest {
    private val html = """<div class="pgs-sinfo" data-id-season="32752" data-id-serial="42"></div>
        <script>var arEpisodes = {"0":{"one":{"n":"1"}}};
        var pl = {"0":"/playls2/connection/default.txt"};</script>"""
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
    private val video = ByteArray(1024).also { "ftyp".toByteArray(Charsets.US_ASCII).copyInto(it, 4) }
    private val playlist = """[{"id":"1","file":"https://media.example.test/episode.mp4"}]"""

    private fun response(request: Request, code: Int, body: ResponseBody): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body).build()

    private fun fixture(request: Request): Response = when (request.url.host) {
        "cdn.svstat.net" -> response(request, 200, jpeg.toResponseBody("image/jpeg".toMediaType()))
        "media.example.test" -> response(request, 206, video.toResponseBody("video/mp4".toMediaType()))
            .newBuilder().header("Content-Range", "bytes 0-1023/1000000").build()
        else -> response(request, 200,
            (if (request.url.encodedPath.startsWith("/playls2/")) playlist else html).toResponseBody())
    }

    private fun check(respond: (Request) -> Response): Map<ConnectionTarget, ConnectionResult> = runBlocking {
        val results = mutableMapOf<ConnectionTarget, ConnectionResult>()
        val client = OkHttpClient.Builder().addInterceptor { respond(it.request()) }.build()
        try { ConnectionChecker { client }.check(NetworkConfiguration()) { target, result -> results[target] = result } }
        finally { client.dispatcher.executorService.shutdown() }
        results
    }

    @Test fun downloads_real_bodies_without_conditionals_and_resolves_the_video_without_account_state() {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val results = check { request -> requests += request; fixture(request) }
        assertTrue(results.values.all { it == ConnectionResult.available })
        assertEquals(4, requests.size)
        requests.forEach {
            assertEquals("GET", it.method)
            assertNull(it.header("If-None-Match")); assertNull(it.header("If-Modified-Since"))
            assertEquals("no-cache, no-store", it.header("Cache-Control"))
            assertNull(it.header("Cookie"))
        }
        val media = requests.single { it.url.host == "media.example.test" }
        assertEquals("bytes=0-1023", media.header("Range"))
        assertEquals("identity", media.header("Accept-Encoding"))
    }

    @Test fun a_200_blocking_page_is_red_and_video_dependency_is_finalized_without_a_request() {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val results = check { request ->
            requests += request
            if (request.url.host == "seasonvar.ru") response(request, 200, "Access denied".toResponseBody()) else fixture(request)
        }
        assertEquals(ConnectionFailure.CONTENT, results[ConnectionTarget.SITE]?.failure)
        assertEquals(ConnectionFailure.SOURCE, results[ConnectionTarget.VIDEO]?.failure)
        assertEquals(ConnectionResult.available, results[ConnectionTarget.POSTERS])
        assertEquals(2, requests.size)
    }

    @Test fun poster_rejects_cached_304_wrong_content_and_a_body_over_the_limit() {
        for (poster in listOf<(Request) -> Response>(
            { response(it, 304, ByteArray(0).toResponseBody()) },
            { response(it, 200, "not a poster".toResponseBody("image/jpeg".toMediaType())) },
            { response(it, 200, ByteArray(Settings.POSTER_LIMIT_BYTES.toInt() + 1).toResponseBody("image/jpeg".toMediaType())) },
        )) {
            val results = check { if (it.url.host == "cdn.svstat.net") poster(it) else fixture(it) }
            assertEquals(ConnectionStatus.FAILED, results[ConnectionTarget.POSTERS]?.status)
            assertFalse(results.values.any { it.status == ConnectionStatus.CHECKING })
        }
    }

    @Test fun ignored_range_reads_only_a_prefix_and_always_closes_the_video_body() {
        var read = 0L
        var closed = false
        val body = object : ResponseBody() {
            private val stream = object : Source {
                override fun timeout() = Timeout.NONE
                override fun close() { closed = true }
                override fun read(sink: Buffer, byteCount: Long): Long {
                    check(read < 8192) { "The complete video must not be read" }
                    val data = video.copyOf(byteCount.toInt())
                    sink.write(data); read += data.size; return data.size.toLong()
                }
            }.buffer()
            override fun contentType() = "video/mp4".toMediaType()
            override fun contentLength() = 100_000_000L
            override fun source() = stream
        }
        val results = check { if (it.url.host == "media.example.test") response(it, 200, body) else fixture(it) }
        assertEquals(ConnectionResult.available, results[ConnectionTarget.VIDEO])
        assertTrue(read in Settings.VIDEO_PREFIX_BYTES..8192L)
        assertTrue(closed)
    }

    @Test fun network_failure_of_one_target_does_not_cancel_other_targets() {
        val results = check { if (it.url.host == "cdn.svstat.net") throw IOException("Fixture") else fixture(it) }
        assertEquals(ConnectionFailure.NETWORK, results[ConnectionTarget.POSTERS]?.failure)
        assertEquals(ConnectionResult.available, results[ConnectionTarget.SITE])
        assertEquals(ConnectionResult.available, results[ConnectionTarget.VIDEO])
    }
}
