package ru.seasonvar.tv.data.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateTransportTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun source(respond: (okhttp3.Request) -> Response): GitHubUpdateSource =
        GitHubUpdateSource(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .addInterceptor { respond(it.request()) }.build())
    private fun response(request: okhttp3.Request, code: Int, content: ByteArray = byteArrayOf()) = Response.Builder()
        .request(request).code(code).message("test").protocol(Protocol.HTTP_1_1).body(content.toResponseBody()).build()
    private fun asset() = ReleaseAsset("test.apk", "https://github.com/rshadow/seasonvar-tv/test.apk", 4, "unused")

    @Test fun latest_is_anonymous_and_404_means_no_published_release() = runBlocking {
        val source = source { request ->
            assertEquals(GitHubReleaseParser.API_URL, request.url.toString())
            assertNull(request.header("Cookie"))
            assertNull(request.header("Authorization"))
            assertNull(request.header("Referer"))
            assertEquals("application/vnd.github+json", request.header("Accept"))
            response(request, 404)
        }
        assertNull(source.latest())
    }

    @Test fun release_history_is_anonymous_paginated_and_missing_repository_is_an_error() = runBlocking {
        val source = source { request ->
            assertEquals(ReleaseNotesParser.API_URL, request.url.newBuilder().query(null).build().toString())
            assertEquals("2", request.url.queryParameter("page"))
            assertEquals("100", request.url.queryParameter("per_page"))
            assertNull(request.header("Cookie"))
            assertNull(request.header("Authorization"))
            assertNull(request.header("Referer"))
            response(request, 200, "[]".toByteArray())
        }
        assertEquals("[]", source.releases(2))
        try { source { response(it, 404) }.releases(1); fail("Missing repository accepted") }
        catch (error: UpdateHttpException) { assertEquals(404, error.status) }
        try { source { response(it, 200, ByteArray(4 * 1024 * 1024 + 1)) }.releases(1); fail("Unbounded page accepted") }
        catch (_: UpdateFormatException) { }
    }

    @Test fun rate_limit_is_an_error_not_no_updates() = runBlocking {
        val source = source { response(it, 403) }
        try { source.latest(); fail("Expected HTTP failure") }
        catch (error: UpdateHttpException) { assertEquals(403, error.status) }
    }

    @Test fun bounded_metadata_prevents_unlimited_response_reading() = runBlocking {
        val source = source { response(it, 200, ByteArray(1024 * 1024 + 1)) }
        try { source.latest(); fail("Oversized metadata accepted") }
        catch (_: UpdateFormatException) { }
    }

    @Test fun download_follows_github_asset_redirect_and_reports_progress() = runBlocking {
        var calls = 0
        val source = source { request ->
            calls++
            assertNull(request.header("Cookie"))
            if (calls == 1) response(request, 302).newBuilder()
                .header("Location", "https://release-assets.githubusercontent.com/test.apk?signature=example").build()
            else response(request, 200, byteArrayOf(1, 2, 3, 4))
        }
        val file = temporary.newFile()
        val progress = mutableListOf<Int>()
        source.download(asset(), file) { progress.add(it) }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), file.readBytes())
        assertEquals(2, calls)
        assertEquals(listOf(100), progress)
    }

    @Test fun external_or_cleartext_redirects_are_rejected_before_a_second_request() = runBlocking {
        listOf("https://example.org/payload.apk", "http://github.com/payload.apk").forEach { location ->
            var calls = 0
            val source = source { calls++; response(it, 302).newBuilder().header("Location", location).build() }
            try { source.download(asset(), temporary.newFile()) {}; fail("Unsafe redirect accepted") }
            catch (_: UpdateFormatException) { }
            assertEquals(1, calls)
        }
    }

    @Test fun wrong_response_size_is_rejected() = runBlocking {
        val source = source { response(it, 200, byteArrayOf(1, 2, 3)) }
        try { source.download(asset(), temporary.newFile()) {}; fail("Wrong size accepted") }
        catch (_: UpdateIntegrityException) { }
    }

    @Test fun redirect_loop_is_bounded() = runBlocking {
        var calls = 0
        val source = source { calls++; response(it, 302).newBuilder().header("Location", it.url.toString()).build() }
        try { source.download(asset(), temporary.newFile()) {}; fail("Loop accepted") }
        catch (_: UpdateFormatException) { }
        assertEquals(6, calls)
    }

    @Test fun update_preserves_identity_and_requires_same_signers_and_increasing_code() {
        val installed = UpdateIdentity("ru.seasonvar.tv", "0.3.1", 3, setOf("public-certificate-A"))
        val newer = installed.copy(versionName = "0.4.0", versionCode = 4)
        verifyUpdateIdentity(installed, newer, "0.4.0")
        listOf(newer.copy(packageName = "other.app"), newer.copy(versionName = "0.5.0"),
            newer.copy(versionCode = 3), newer.copy(versionCode = 2)).forEach {
            assertThrows(UpdatePackageException::class.java) { verifyUpdateIdentity(installed, it, "0.4.0") }
        }
        listOf(newer.copy(signatures = emptySet()), newer.copy(signatures = setOf("public-certificate-B"))).forEach {
            assertThrows(UpdateSignatureException::class.java) { verifyUpdateIdentity(installed, it, "0.4.0") }
        }
    }
}
