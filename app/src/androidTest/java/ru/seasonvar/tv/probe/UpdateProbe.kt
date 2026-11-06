package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import ru.seasonvar.tv.BuildConfig
import ru.seasonvar.tv.MainActivity
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.data.PublicFeed
import ru.seasonvar.tv.data.update.*
import ru.seasonvar.tv.ui.SeasonvarApp
import ru.seasonvar.tv.ui.catalog.CatalogUiState
import ru.seasonvar.tv.ui.catalog.DescriptionState
import ru.seasonvar.tv.ui.navigation.Section
import ru.seasonvar.tv.ui.update.UpdateViewModel
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Emulator-only previews never change account marks or the release channel. */
object UpdateProbe {
    fun run(probe: Instrumentation, arguments: Bundle) = with(probe) {
        val result = Bundle()
        try {
            val app = targetContext.applicationContext as SeasonvarApplication
            if (arguments.getString("mode") == "update-preview") {
                check(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.FINGERPRINT.contains("generic"))
                val fixture = File("/data/local/tmp/seasonvar-update-fixture.apk")
                val info = checkNotNull(targetContext.packageManager.getPackageArchiveInfo(fixture.path, 0))
                val version = checkNotNull(info.versionName)
                val hash = MessageDigest.getInstance("SHA-256").digest(fixture.readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                val release = GitHubRelease(version, ReleaseAsset("fixture.apk", "https://github.com/test", fixture.length(), hash))
                val scenario = arguments.getString("scenario") ?: "new"
                val source = object : UpdateSource {
                    override suspend fun latest(): String? = error("Preview supplies metadata locally")
                    override suspend fun download(asset: ReleaseAsset, destination: File, progress: (Int) -> Unit) {
                        progress(25); delay(1000); progress(75); delay(1000)
                        fixture.copyTo(destination, overwrite = true); progress(100)
                    }
                }
                val repository = UpdateRepository(source, File(targetContext.cacheDir, "updates")) { file, data ->
                    if (scenario == "signature") throw UpdateSignatureException()
                    app.updateInstaller.verify(file, data)
                }
                val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                runOnMainSync {
                    val model = UpdateViewModel(BuildConfig.VERSION_NAME, {
                        delay(1000)
                        when (scenario) {
                            "network" -> throw IOException()
                            "current" -> release.copy(version = BuildConfig.VERSION_NAME)
                            else -> release
                        }
                    }, repository::download,
                        cachedNotes = { if (scenario == "notes-network") listOf(
                            ReleaseNotes(BuildConfig.VERSION_NAME, "2026-11-04T12:00:00Z", "- Сохранённое описание установленной версии")) else emptyList() },
                        loadNotes = {
                            delay(1500)
                            if (scenario == "notes-network") throw IOException()
                            listOf(ReleaseNotes(BuildConfig.VERSION_NAME, "2026-11-04T12:00:00Z",
                                "- Удобнее выбирать сериал.\n- Улучшено управление с пульта.\n\nНастройки и вход сохраняются при обновлении.")) +
                                if (scenario == "current") emptyList() else listOf(ReleaseNotes(version, "2026-11-07T12:00:00Z",
                                    "- Добавлены описания обновлений.\n- Исправлена загрузка обложек.\n\n" +
                                        (1..12).joinToString("\n") { "- Пункт $it: описание изменений остаётся доступно целиком." }))
                        })
                    activity.setContent {
                        SeasonvarApp(states = PublicFeed.entries.associateWith { CatalogUiState() },
                            descriptions = PublicFeed.entries.associateWith { DescriptionState() },
                            onRefresh = {}, onSelect = { _, _, _ -> }, startPage = Section.ABOUT, updateModel = model)
                    }
                }
                result.putBoolean("preview_ready", true)
                sendStatus(Activity.RESULT_OK, result)
                return@with
            }
            runBlocking {
                val release = checkNotNull(app.updates.latest())
                result.putString("latest_version", release.version)
                result.putBoolean("asset_available", release.asset != null)
                var inspected = false
                val repository = UpdateRepository(GitHubUpdateSource(), File(targetContext.cacheDir, "update-probe")) { file, data ->
                    val info = checkNotNull(targetContext.packageManager.getPackageArchiveInfo(file.path, 0))
                    check(info.packageName == targetContext.packageName && info.versionName == data.version)
                    inspected = true
                }
                val file = repository.download(release) {}
                result.putBoolean("download_verified", inspected && file.isFile)
                file.delete()
                result.putBoolean("probe_success", true)
            }
            finish(Activity.RESULT_OK, result)
        } catch (error: Exception) {
            result.putBoolean("probe_success", false)
            result.putString("failure_type", error.javaClass.simpleName)
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
