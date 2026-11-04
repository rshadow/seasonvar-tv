package ru.seasonvar.tv.data.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class UpdateRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "test APK contents".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun asset() = JSONObject().put("name", "seasonvar-tv-0.4.0-universal.apk").put("state", "uploaded")
        .put("browser_download_url", "https://github.com/rshadow/seasonvar-tv/releases/download/v0.4.0/seasonvar-tv-0.4.0-universal.apk")
        .put("size", bytes.size).put("digest", "sha256:$hash")
    private fun json(asset: JSONObject? = asset()): JSONObject = JSONObject().put("tag_name", "v0.4.0")
        .put("draft", false).put("prerelease", false).put("assets", JSONArray().apply { asset?.let { put(it) } })
    private fun release() = GitHubReleaseParser.parse(json().toString())

    @Test fun version_comparison_is_numeric_and_rejects_nonstable_or_malformed_tags() {
        assertTrue(ReleaseVersion.parse("0.10.0")!! > ReleaseVersion.parse("0.9.9")!!)
        assertTrue(ReleaseVersion.parse("1.0.0")!! > ReleaseVersion.parse("0.99.99")!!)
        assertEquals(ReleaseVersion.parse("0.4.0"), ReleaseVersion.parse("v0.4.0"))
        listOf("0.4", "0.4.0-beta.1", "01.2.3", "1.2.3+build", "999999999999999999999.0.0", "garbage").forEach {
            assertNull(it, ReleaseVersion.parse(it))
        }
        assertFalse(release().newerThan("0.4.0"))
        assertFalse(release().newerThan("0.5.0"))
        assertTrue(release().newerThan("0.3.1"))
    }

    @Test fun picks_only_the_exact_universal_apk_with_digest_from_this_repository() {
        val document = json()
        document.getJSONArray("assets").put(asset().put("name", "seasonvar-tv-0.4.0-arm64.apk"))
        val release = GitHubReleaseParser.parse(document.toString())
        assertEquals("0.4.0", release.version)
        assertEquals(hash, release.asset!!.sha256)
        assertEquals(bytes.size.toLong(), release.asset.size)
    }

    @Test fun newer_release_without_a_safe_asset_remains_visible_but_not_installable() {
        val invalid = listOf(
            asset().put("browser_download_url", "https://example.com/app.apk"),
            asset().put("browser_download_url", "http://github.com/rshadow/seasonvar-tv/releases/download/v0.4.0/seasonvar-tv-0.4.0-universal.apk"),
            asset().put("digest", JSONObject.NULL), asset().put("digest", "sha256:bad"),
            asset().put("size", 0), asset().put("size", GitHubReleaseParser.MAX_APK_SIZE + 1),
            asset().put("state", "new"), asset().put("name", "seasonvar-tv-0.3.1-universal.apk"),
        )
        (invalid.map { json(it) } + json(null)).forEach {
            val release = GitHubReleaseParser.parse(it.toString())
            assertTrue(release.newerThan("0.3.1"))
            assertNull(release.asset)
        }
        val duplicate = json().apply { getJSONArray("assets").put(asset()) }
        assertNull(GitHubReleaseParser.parse(duplicate.toString()).asset)
    }

    @Test fun drafts_prereleases_and_invalid_responses_are_not_an_empty_or_current_result() {
        listOf(json().put("draft", true).toString(), json().put("prerelease", true).toString(),
            json().put("tag_name", "v0.4.0-rc.1").toString(), "<html>Blocked</html>", "{}").forEach { content ->
            assertThrows(UpdateFormatException::class.java) { GitHubReleaseParser.parse(content) }
        }
    }

    @Test fun redirects_allow_only_https_github_download_hosts() {
        listOf("https://github.com/a", "https://release-assets.githubusercontent.com/a?signature=example",
            "https://objects.githubusercontent.com/a").forEach { assertTrue(GitHubReleaseParser.allowedDownloadUrl(it)) }
        listOf("http://github.com/a", "https://github.com.evil.test/a", "https://github.com:8443/a",
            "https://user:pass@github.com/a", "file:///tmp/a", "https://example.org/a").forEach {
            assertFalse(it, GitHubReleaseParser.allowedDownloadUrl(it))
        }
    }

    private fun repository(content: ByteArray = bytes, verify: (File, GitHubRelease) -> Unit = { _, _ -> },
        interrupted: Boolean = false): Pair<UpdateRepository, File> {
        val directory = temporary.newFolder()
        val source = object : UpdateSource {
            override suspend fun latest(): String = json().toString()
            override suspend fun download(asset: ReleaseAsset, destination: File, progress: (Int) -> Unit) {
                destination.writeBytes(content)
                if (interrupted) throw CancellationException()
                progress(100)
            }
        }
        return UpdateRepository(source, directory, verify) to directory
    }

    @Test fun download_checks_bytes_before_verifying_package_and_exposes_only_complete_apk() = runBlocking {
        var verified = false
        val (repository, directory) = repository(verify = { file, release ->
            assertArrayEquals(bytes, file.readBytes())
            assertEquals("0.4.0", release.version)
            assertFalse(File(file.parent, "update.apk").exists())
            verified = true
        })
        val progress = mutableListOf<Int>()
        val file = repository.download(repository.latest()!!, progress::add)
        assertTrue(verified)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(listOf("update.apk"), directory.list()!!.toList())
        assertEquals(listOf(100), progress)
    }

    @Test fun truncated_and_modified_downloads_never_reach_package_verification() = runBlocking {
        listOf(bytes.copyOf(bytes.size - 1), bytes.copyOf().apply { this[0] = 0 }).forEach { bad ->
            var verified = false
            val (repository, directory) = repository(bad, { _, _ -> verified = true })
            try { repository.download(release()) {}; fail("Invalid APK accepted") }
            catch (_: UpdateIntegrityException) { }
            assertFalse(verified)
            assertTrue(directory.list()!!.isEmpty())
        }
    }

    @Test fun signature_or_package_failure_removes_partial_file() = runBlocking {
        listOf(UpdateSignatureException(), UpdatePackageException()).forEach { error ->
            val (repository, directory) = repository(verify = { _, _ -> throw error })
            try { repository.download(release()) {}; fail("Invalid APK accepted") }
            catch (actual: Exception) { assertEquals(error.javaClass, actual.javaClass) }
            assertTrue(directory.list()!!.isEmpty())
        }
    }

    @Test fun cancellation_cleans_up_without_publishing_a_ready_file() = runBlocking {
        val (repository, directory) = repository(interrupted = true)
        try { repository.download(release()) {}; fail("Expected cancellation") }
        catch (_: CancellationException) { }
        assertTrue(directory.list()!!.isEmpty())
    }
}
