package ru.seasonvar.tv.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class UpdateRepository(
    private val source: UpdateSource,
    private val directory: File,
    private val verifyApk: (File, GitHubRelease) -> Unit,
) {
    suspend fun latest(): GitHubRelease? = withContext(Dispatchers.IO) {
        source.latest()?.let(GitHubReleaseParser::parse)
    }

    suspend fun download(release: GitHubRelease, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val asset = release.asset ?: throw UpdateFormatException()
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException()
        val partial = File(directory, "update.part")
        val ready = File(directory, "update.apk")
        partial.delete()
        ready.delete()
        try {
            source.download(asset, partial, progress)
            currentCoroutineContext().ensureActive()
            if (partial.length() != asset.size) throw UpdateIntegrityException()
            val digest = MessageDigest.getInstance("SHA-256")
            partial.inputStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            if (actual != asset.sha256) throw UpdateIntegrityException()
            verifyApk(partial, release)
            currentCoroutineContext().ensureActive()
            if (!partial.renameTo(ready)) throw IOException()
            ready
        } finally { partial.delete() }
    }
}
