package ru.seasonvar.tv.data.update

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

class UpdateFormatException : Exception()
class UpdateHttpException(val status: Int) : Exception()
class UpdateIntegrityException : Exception()
class UpdateSignatureException : Exception()
class UpdatePackageException : Exception()

data class ReleaseVersion(val major: Long, val minor: Long, val patch: Long) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    companion object {
        fun installed(value: String): ReleaseVersion? =
            parse(value.replace(Regex("-dev[0-9a-fA-F]+$"), ""))

        fun parse(value: String): ReleaseVersion? {
            val match = Regex("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matchEntire(value) ?: return null
            val parts = match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
            return ReleaseVersion(parts[0], parts[1], parts[2])
        }
    }
}

data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String)
data class GitHubRelease(val version: String, val asset: ReleaseAsset?) {
    fun newerThan(installed: String): Boolean =
        (ReleaseVersion.parse(version) ?: throw UpdateFormatException()) >
            (ReleaseVersion.installed(installed) ?: throw UpdateFormatException())
}

object GitHubReleaseParser {
    const val API_URL = "https://api.github.com/repos/rshadow/seasonvar-tv/releases/latest"
    const val MAX_APK_SIZE = 100L * 1024 * 1024

    fun parse(json: String): GitHubRelease = try {
        val root = JSONObject(json)
        if (root.getBoolean("draft") || root.getBoolean("prerelease")) throw UpdateFormatException()
        val tag = root.getString("tag_name")
        if (ReleaseVersion.parse(tag) == null || !tag.startsWith("v")) throw UpdateFormatException()
        val version = tag.removePrefix("v")
        val name = "seasonvar-tv-$version-universal.apk"
        val assets = root.getJSONArray("assets")
        val matches = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.optString("name") == name && it.optString("state") == "uploaded" }
        val asset = matches.singleOrNull()?.let {
            val url = it.getString("browser_download_url")
            val digest = it.optString("digest")
            val size = it.getLong("size")
            if (url != "https://github.com/rshadow/seasonvar-tv/releases/download/$tag/$name" ||
                size !in 1..MAX_APK_SIZE || !digest.matches(Regex("sha256:[a-fA-F0-9]{64}"))) null
            else ReleaseAsset(name, url, size, digest.substringAfter(':').lowercase())
        }
        GitHubRelease(version, asset)
    } catch (error: UpdateFormatException) { throw error }
    catch (_: Exception) { throw UpdateFormatException() }

    internal fun allowedDownloadUrl(raw: String): Boolean {
        val url = raw.toHttpUrlOrNull() ?: return false
        return url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            url.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }
}
