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
    internal val applicationScope by lazy { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO) }
    internal val interfaceSettings by lazy { ru.seasonvar.tv.ui.settings.InterfaceSettings(this) }
    val networkSettings by lazy { NetworkSettings(this) }
    val sessions: ru.seasonvar.tv.data.account.SessionManager by lazy {
        ru.seasonvar.tv.data.account.SessionManager(ru.seasonvar.tv.data.account.EncryptedSessionStore(this)) {
            val current = sessions.currentCacheScope()
            if (current?.private == true && current.owner != null) htmlCache.selectPrivateOwner(current.owner)
            else if (current?.private == false) htmlCache.clearPrivate()
            cardData.clearPrivate()
        }
    }
    val htmlCache by lazy { ru.seasonvar.tv.data.cache.HtmlResponseCache(File(cacheDir, "seasonvar-public"),
        scope = sessions::cacheScope, currentScope = sessions::currentCacheScope) }
    val cardData by lazy { ru.seasonvar.tv.data.cache.CardDataCache(htmlCache) }
    internal val listResourcePermits = kotlinx.coroutines.sync.Semaphore(ru.seasonvar.tv.data.images.LIST_RESOURCE_CONCURRENCY)
    val siteClient by lazy { SiteClient(networkSettings, sessions) }
    val account by lazy { ru.seasonvar.tv.data.account.AccountRepository(sessions, siteClient, htmlCache, cardData) }
    val translationCatalog by lazy { ru.seasonvar.tv.data.TranslationCatalogRepository(siteClient) }
    val searchHistory by lazy { ru.seasonvar.tv.data.SearchHistory(ru.seasonvar.tv.data.PreferencesSearchHistoryStore(this)) }
    val repository by lazy { CatalogRepository(File(cacheDir, "seasonvar-public"), siteClient,
        responseCache = htmlCache, cardCache = cardData) }

    val nextSeasons by lazy { ru.seasonvar.tv.data.playback.NextSeasonResolver(
        load = { repository.details(it, force = true) },
        preferences = { interfaceSettings.preferredTranslations.value }) }

    val videos by lazy { VideoRepository { url, referer ->
        if (BuildConfig.DEBUG) android.util.Log.d("SeasonvarPlayback", "request=" +
            if (java.net.URI(url).path.startsWith("/serial-")) "page" else "playlist")
        siteClient.resource(url, referer)
    } }
    val updateInstaller by lazy { ru.seasonvar.tv.data.update.UpdateInstaller(this) }
    val updates by lazy { ru.seasonvar.tv.data.update.UpdateRepository(
        ru.seasonvar.tv.data.update.GitHubUpdateSource(), File(cacheDir, "updates"), updateInstaller::verify) }
    val playbackProgress by lazy { PlaybackProgress(this) }
    val smartContinueCache by lazy { ru.seasonvar.tv.data.smart.PreferencesSmartContinueCache(this) }
    val listFilterPreferences by lazy {
        val store = getSharedPreferences("list-filters", MODE_PRIVATE)
        ru.seasonvar.tv.ui.filters.ListFilterPreferences(
            { store.getString(it, null) }, { key, value -> store.edit().putString(key, value).apply() })
    }
    val accountMarks by lazy { ru.seasonvar.tv.data.playback.AccountMarkWriter(applicationScope, account,
        ru.seasonvar.tv.data.playback.PreferencesMarkOutbox(this)) }
    val playbackHistory by lazy { ru.seasonvar.tv.data.playback.PlaybackHistoryWriter(applicationScope, account) }
    val homeChannels by lazy { ru.seasonvar.tv.data.home.HomeChannels(this, applicationScope) }

    override fun onCreate() { super.onCreate(); homeChannels.start() }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).callFactory(siteClient).build()
}
