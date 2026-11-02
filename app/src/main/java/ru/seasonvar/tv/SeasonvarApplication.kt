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
    val networkSettings by lazy { NetworkSettings(this) }
    val siteClient by lazy { SiteClient(networkSettings) }
    val repository by lazy { CatalogRepository(File(cacheDir, "seasonvar-public"), siteClient) }

    val videos by lazy { VideoRepository { url, referer ->
        if (BuildConfig.DEBUG) android.util.Log.d("SeasonvarPlayback", "request=" +
            if (java.net.URI(url).path.startsWith("/serial-")) "page" else "playlist")
        siteClient.resource(url, referer)
    } }
    val playbackProgress by lazy { PlaybackProgress(this) }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).callFactory(siteClient).build()
}
