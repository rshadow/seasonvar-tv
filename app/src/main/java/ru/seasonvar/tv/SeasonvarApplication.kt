package ru.seasonvar.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import ru.seasonvar.tv.data.SiteClient
import ru.seasonvar.tv.data.CatalogRepository
import ru.seasonvar.tv.data.network.NetworkSettings
import java.io.File

class SeasonvarApplication : Application(), ImageLoaderFactory {
    val networkSettings by lazy { NetworkSettings(this) }
    val siteClient by lazy { SiteClient(networkSettings) }
    val repository by lazy { CatalogRepository(File(cacheDir, "seasonvar-public"), siteClient) }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).callFactory(siteClient).build()
}
