package ru.seasonvar.tv.data.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

class UpdateInstaller(private val context: Context) {
    @Suppress("DEPRECATION")
    fun verify(file: File, release: GitHubRelease) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: throw UpdatePackageException()
        verifyUpdateIdentity(
            UpdateIdentity(installed.packageName, installed.versionName.orEmpty(), code(installed), signatures(installed)),
            UpdateIdentity(archive.packageName, archive.versionName.orEmpty(), code(archive), signatures(archive)), release.version)
    }

    fun permissionGranted(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(): Intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        android.net.Uri.parse("package:" + context.packageName))

    fun installIntent(file: File): Intent {
        if (!file.isFile || file.name != "update.apk" || file.parentFile?.canonicalFile != File(context.cacheDir, "updates").canonicalFile) {
            throw UpdatePackageException()
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("Seasonvar TV update", uri) }
    }

    @Suppress("DEPRECATION")
    private fun code(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { it.toCharsString() }?.toSet().orEmpty()
}

internal data class UpdateIdentity(val packageName: String, val versionName: String, val versionCode: Long, val signatures: Set<String>)

internal fun verifyUpdateIdentity(installed: UpdateIdentity, archive: UpdateIdentity, version: String) {
    if (archive.packageName != installed.packageName || archive.versionName != version ||
        archive.versionCode <= installed.versionCode) throw UpdatePackageException()
    if (installed.signatures.isEmpty() || archive.signatures != installed.signatures) throw UpdateSignatureException()
}
