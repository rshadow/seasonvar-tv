package ru.seasonvar.tv.data.update

import kotlinx.coroutines.CancellationException
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

    suspend fun cachedNotes(): List<ReleaseNotes> = withContext(Dispatchers.IO) {
        val file = File(directory, "release-notes.json")
        try {
            if (!file.isFile || file.length() > ReleaseNotesParser.MAX_BYTES) emptyList()
            else ReleaseNotesParser.decode(file.readText())
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { emptyList() }
    }

    suspend fun notes(): List<ReleaseNotes> = withContext(Dispatchers.IO) {
        val notes = mutableListOf<ReleaseNotes>()
        var received = 0
        for (page in 1..100) {
            currentCoroutineContext().ensureActive()
            val json = source.releases(page)
            received += json.toByteArray(Charsets.UTF_8).size
            if (received > ReleaseNotesParser.MAX_BYTES) throw UpdateFormatException()
            val (items, more) = ReleaseNotesParser.parsePage(json)
            notes += items
            if (!more) {
                currentCoroutineContext().ensureActive()
                saveNotes(notes)
                return@withContext notes
            }
        }
        throw UpdateFormatException()
    }

    private fun saveNotes(notes: List<ReleaseNotes>) {
        val partial = File(directory, "release-notes.part")
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return
            val json = ReleaseNotesParser.encode(notes)
            if (json.toByteArray(Charsets.UTF_8).size > ReleaseNotesParser.MAX_BYTES) return
            partial.writeText(json)
            partial.renameTo(File(directory, "release-notes.json"))
        } catch (_: IOException) { /* A cache write failure must not hide downloaded descriptions. */ }
        finally { partial.delete() }
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
