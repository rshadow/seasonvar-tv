package ru.seasonvar.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import ru.seasonvar.tv.data.playback.VideoRepository
import ru.seasonvar.tv.data.playback.PlaybackProgress
import ru.seasonvar.tv.data.SiteClient
import ru.seasonvar.tv.data.CatalogRepository
import ru.seasonvar.tv.data.network.NetworkSettings
import java.io.File

class SeasonvarApplication : Application(), ImageLoaderFactory {
    private val applicationScope by lazy { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO) }
    internal val interfaceSettings by lazy { ru.seasonvar.tv.ui.settings.InterfaceSettings(this) }
    val networkSettings by lazy { NetworkSettings(this) }
    val sessions by lazy { ru.seasonvar.tv.data.account.SessionManager(ru.seasonvar.tv.data.account.EncryptedSessionStore(this)) }
    val siteClient by lazy { SiteClient(networkSettings, sessions) }
    val account by lazy { ru.seasonvar.tv.data.account.AccountRepository(sessions, siteClient) }
    val repository by lazy { CatalogRepository(File(cacheDir, "seasonvar-public"), siteClient, cacheToken = sessions::cacheToken) }

    val videos by lazy { VideoRepository { url, referer ->
        if (BuildConfig.DEBUG) android.util.Log.d("SeasonvarPlayback", "request=" +
            if (java.net.URI(url).path.startsWith("/serial-")) "page" else "playlist")
        siteClient.resource(url, referer)
    } }
    val playbackProgress by lazy { PlaybackProgress(this) }
    val accountMarks by lazy { ru.seasonvar.tv.data.playback.AccountMarkWriter(applicationScope, account,
        ru.seasonvar.tv.data.playback.PreferencesMarkOutbox(this)) }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).callFactory(siteClient).build()
}
