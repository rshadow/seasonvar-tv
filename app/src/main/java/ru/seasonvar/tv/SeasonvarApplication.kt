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
import kotlinx.coroutines.flow.combine
import ru.seasonvar.tv.diagnostics.startupMark
import ru.seasonvar.tv.diagnostics.startupMeasure

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
    val pac by lazy { ru.seasonvar.tv.data.network.pac.PacCoordinator(this, networkSettings, applicationScope) }
    val siteClient by lazy { SiteClient(networkSettings, sessions, pac) }
    val account by lazy { ru.seasonvar.tv.data.account.AccountRepository(sessions, siteClient, htmlCache, cardData) }
    val translationCatalog by lazy { ru.seasonvar.tv.data.TranslationCatalogRepository(siteClient) }
    val searchHistory by lazy { ru.seasonvar.tv.data.SearchHistory(ru.seasonvar.tv.data.PreferencesSearchHistoryStore(this)) }
    val repository by lazy { CatalogRepository(File(cacheDir, "seasonvar-public"), siteClient,
        responseCache = htmlCache, cardCache = cardData) }
    val comments by lazy { ru.seasonvar.tv.data.comments.CommentsRepository(htmlCache, siteClient) }
    val reviews by lazy { ru.seasonvar.tv.data.reviews.ReviewsRepository(htmlCache, siteClient) }
    internal val posterBackdrops by lazy { ru.seasonvar.tv.data.images.PosterBackdropCache() }

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
        ru.seasonvar.tv.data.update.GitHubUpdateSource(configure = pac::githubClient), File(cacheDir, "updates"), updateInstaller::verify) }
    val playbackProgress by lazy { PlaybackProgress(this) }
    @Volatile internal var continueForeground = false
    val smartContinueCache by lazy { ru.seasonvar.tv.data.smart.PreferencesSmartContinueCache(this) }
    val smartContinue by lazy {
        lateinit var shared: ru.seasonvar.tv.data.smart.SharedSmartContinueSource
        val calculator = ru.seasonvar.tv.data.smart.SmartContinueRepository(account, repository,
            { interfaceSettings.preferredTranslations.value }, { shared.foreground && continueForeground })
        shared = ru.seasonvar.tv.data.smart.SharedSmartContinueSource({
            val session = account.session.value
            ru.seasonvar.tv.data.smart.SmartContinueKey(session.user?.id, session.version,
                account.marksRevision.value, account.historyRevision.value, account.readGeneration,
                interfaceSettings.preferredTranslations.value.toList(), account.historyReset.value)
        }, calculator, applicationScope,
            combine(account.session, account.marksRevision, account.historyRevision,
                interfaceSettings.preferredTranslations) { _, _, _, _ -> Unit },
            prepare = { prepareNetwork(networkSettings.configuration.value) })
        shared
    }

    val listFilterPreferences by lazy {
        val store = getSharedPreferences("list-filters", MODE_PRIVATE)
        ru.seasonvar.tv.ui.filters.ListFilterPreferences(
            { store.getString(it, null) }, { key, value -> store.edit().putString(key, value).apply() })
    }
    val accountMarks by lazy { ru.seasonvar.tv.data.playback.AccountMarkWriter(applicationScope, account,
        ru.seasonvar.tv.data.playback.PreferencesMarkOutbox(this)) }
    val playbackHistory by lazy { ru.seasonvar.tv.data.playback.PlaybackHistoryWriter(applicationScope, account) }
    val homeChannels by lazy { ru.seasonvar.tv.data.home.HomeChannels(this, applicationScope) }

    override fun onCreate() {
        startupMark("application_on_create")
        super.onCreate()
        val process = if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName()
            else java.io.File("/proc/self/cmdline").readText(Charsets.UTF_8).substringBefore('\u0000')
        if (process != packageName) {
            if (android.os.Build.VERSION.SDK_INT >= 28) android.webkit.WebView.setDataDirectorySuffix("pac-parser")
            return
        }
        startupMeasure("application_pac_start") { pac.start() }
        startupMeasure("application_channels_start") { homeChannels.start() }
        startupMark("application_created")
    }

    suspend fun selectProxy(proxy: ru.seasonvar.tv.data.network.ProxyConfiguration) {
        if (proxy is ru.seasonvar.tv.data.network.ProxyConfiguration.Pac || proxy == ru.seasonvar.tv.data.network.ProxyConfiguration.AntiZapret)
            pac.activate(networkSettings.configuration.value.copy(proxy = proxy))
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { networkSettings.setProxy(proxy) }
    }

    suspend fun prepareNetwork(configuration: ru.seasonvar.tv.data.network.NetworkConfiguration) {
        pac.prepare(configuration)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { siteClient.prepareNetwork(configuration) }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).callFactory(siteClient)
            .decoderDispatcher(kotlinx.coroutines.Dispatchers.Default).build()
}
