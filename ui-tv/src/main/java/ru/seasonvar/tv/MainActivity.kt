package ru.seasonvar.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import ru.seasonvar.tv.ui.account.AccountViewModel
import ru.seasonvar.tv.ui.account.PersonalListViewModel
import ru.seasonvar.tv.data.account.PersonalFeed
import ru.seasonvar.tv.data.account.PersonalListFilters
import ru.seasonvar.tv.data.account.SessionStorageException
import ru.seasonvar.tv.data.playback.markOwnerId
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.seasonvar.tv.data.PublicFeed
import ru.seasonvar.tv.ui.player.PlayerActivity
import ru.seasonvar.tv.ui.details.SeriesDetailsViewModel
import ru.seasonvar.tv.ui.SeasonvarApp
import ru.seasonvar.tv.ui.navigation.traceBackKey
import ru.seasonvar.tv.ui.navigation.PersonalStartPreview
import ru.seasonvar.tv.ui.navigation.Section
import ru.seasonvar.tv.ui.catalog.CatalogUiState
import ru.seasonvar.tv.ui.catalog.CatalogViewModel
import ru.seasonvar.tv.ui.search.SearchViewModel
import ru.seasonvar.tv.diagnostics.startupMark
import ru.seasonvar.tv.diagnostics.startupMeasure

class MainActivity : ComponentActivity() {
    private lateinit var updateModel: ru.seasonvar.tv.ui.update.UpdateViewModel
    private lateinit var smartModel: ru.seasonvar.tv.ui.smart.SmartContinueViewModel
    private lateinit var detailsModel: SeriesDetailsViewModel
    private lateinit var browseModel: ru.seasonvar.tv.ui.catalog.BrowseCatalogViewModel
    private lateinit var searchModel: SearchViewModel
    private lateinit var models: Map<PublicFeed, CatalogViewModel>
    private lateinit var personalModels: Map<PersonalFeed, PersonalListViewModel>
    private var visiblePersonalFeed: PersonalFeed? = null
    private var historyRevision = 0L
    private var markRevision = 0L
    private lateinit var listRefresh: ru.seasonvar.tv.ui.catalog.ListRefreshCoordinator

    private val playbackLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.let { data ->
            detailsModel.onPlaybackFinished(data.getStringExtra("season"), data.getStringExtra("translation"), data.getStringExtra("episode"))
        }
    }

    private fun networkChanged() {
        val container = application as SeasonvarApplication
        container.account.invalidateSharedReads()
        container.smartContinue.invalidateIfChanged()
        listRefresh.networkChanged(container.networkSettings.configuration.value)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        startupMark("activity_on_create")
        super.onCreate(savedInstanceState)
        val startupProbe = BuildConfig.BUILD_TYPE == "profile" &&
            intent.getStringExtra("startup_probe_run")?.matches(Regex("[a-z0-9-]{1,80}")) == true
        if (BuildConfig.BUILD_TYPE == "profile") ru.seasonvar.tv.diagnostics.StartupDiagnostics.configure(startupProbe)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val container = application as SeasonvarApplication
        updateModel = startupMeasure("model_update") { ViewModelProvider(this, ru.seasonvar.tv.ui.update.UpdateViewModel.Factory(
            container.updates, BuildConfig.VERSION_NAME))[ru.seasonvar.tv.ui.update.UpdateViewModel::class.java] }
        val translationSettingsModel = startupMeasure("model_translations") { ViewModelProvider(this, ru.seasonvar.tv.ui.settings.TranslationSettingsViewModel.Factory(
            container.translationCatalog, container.interfaceSettings))[ru.seasonvar.tv.ui.settings.TranslationSettingsViewModel::class.java] }
        val connectionCheckModel = startupMeasure("model_connection_checks") { ViewModelProvider(this, ru.seasonvar.tv.ui.settings.ConnectionCheckViewModel.Factory(
            container.networkSettings.configuration, container.pac, container::prepareNetwork))[ru.seasonvar.tv.ui.settings.ConnectionCheckViewModel::class.java] }
        val repository = startupMeasure("repository") { container.repository }
        models = startupMeasure("models_public") { PublicFeed.entries.associateWith { feed ->
            ViewModelProvider(this, CatalogViewModel.Factory(repository, feed, container.listFilterPreferences, container.account) {
                ru.seasonvar.tv.data.playback.markOwner(container.account.session.value.user)
            })
                .get("catalog-" + feed.name, CatalogViewModel::class.java)
        } }
        browseModel = startupMeasure("model_catalog") { ViewModelProvider(this, ru.seasonvar.tv.ui.catalog.BrowseCatalogViewModel.Factory(
            repository, container.listFilterPreferences, container.account) {
                ru.seasonvar.tv.data.playback.markOwner(container.account.session.value.user)
            })[ru.seasonvar.tv.ui.catalog.BrowseCatalogViewModel::class.java] }
        val accountModel = startupMeasure("model_account") { ViewModelProvider(this, AccountViewModel.Factory(container.account, container))[AccountViewModel::class.java] }
        personalModels = startupMeasure("models_personal") { PersonalFeed.entries.associateWith { feed ->
            ViewModelProvider(this, PersonalListViewModel.Factory(container.account, feed, container.listFilterPreferences))
                .get("personal-" + feed.name, PersonalListViewModel::class.java)
        } }
        smartModel = startupMeasure("model_continue") { ViewModelProvider(this, ru.seasonvar.tv.ui.smart.SmartContinueViewModel.Factory(
            container.account, container.smartContinueCache, container.smartContinue))[ru.seasonvar.tv.ui.smart.SmartContinueViewModel::class.java] }
        val historyClearModel = ViewModelProvider(this, ru.seasonvar.tv.ui.account.HistoryClearViewModel.Factory(
            container.account,
            onStarted = { personalModels.getValue(PersonalFeed.HISTORY).invalidate() },
            onCleared = {
                personalModels.getValue(PersonalFeed.HISTORY).historyCleared()
                smartModel.historyCleared()
                (application as SeasonvarApplication).homeChannels.requestRefresh()
            }))[ru.seasonvar.tv.ui.account.HistoryClearViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                container.account.historyRevision.collect { revision ->
                    if (refreshHistoryIfChanged(revision)) smartModel.invalidate()
                }
            }
        }
        searchModel = startupMeasure("model_search") { ViewModelProvider(this, SearchViewModel.Factory(repository, container.searchHistory))[SearchViewModel::class.java] }
        detailsModel = startupMeasure("model_details") { ViewModelProvider(this, SeriesDetailsViewModel.Factory(repository, container.playbackProgress, container.account,
            preferredTranslations = { container.interfaceSettings.preferredTranslations.value }, comments = container.comments))[SeriesDetailsViewModel::class.java] }
        listRefresh = ViewModelProvider(this, ru.seasonvar.tv.ui.catalog.ListRefreshCoordinator.Factory(
            container.networkSettings.configuration.value, container::prepareNetwork, buildMap {
                models.forEach { (feed, model) -> put(ru.seasonvar.tv.ui.navigation.Section.entries.first { it.publicFeed == feed },
                    ru.seasonvar.tv.ui.catalog.ListRefreshCoordinator.Target(model::cancelForNetworkChange, model::refreshIfStarted, model::activate)) }
                personalModels.forEach { (feed, model) -> put(ru.seasonvar.tv.ui.navigation.Section.entries.first { it.personalFeed == feed },
                    ru.seasonvar.tv.ui.catalog.ListRefreshCoordinator.Target(model::cancelForNetworkChange, model::refreshIfStarted, model::activate)) }
                put(ru.seasonvar.tv.ui.navigation.Section.CATALOG, ru.seasonvar.tv.ui.catalog.ListRefreshCoordinator.Target(
                    browseModel::cancelForNetworkChange, browseModel::refreshIfStarted))
                put(ru.seasonvar.tv.ui.navigation.Section.SEARCH, ru.seasonvar.tv.ui.catalog.ListRefreshCoordinator.Target(
                    searchModel::cancelForNetworkChange, searchModel::networkChanged))
                put(ru.seasonvar.tv.ui.navigation.Section.SMART_CONTINUE, ru.seasonvar.tv.ui.catalog.ListRefreshCoordinator.Target(
                    smartModel::cancelForNetworkChange, smartModel::refreshIfStarted, smartModel::preload))
            }, preloadOnStart = container.interfaceSettings.listPrefetchEnabled.value))[ru.seasonvar.tv.ui.catalog.ListRefreshCoordinator::class.java]
        val removalModel = ViewModelProvider(this, ru.seasonvar.tv.ui.account.MarkRemovalViewModel.Factory(
            container.account, container.accountMarks, onRemoved = {}))[ru.seasonvar.tv.ui.account.MarkRemovalViewModel::class.java]
        val probeStart = if (startupProbe) intent.getStringExtra("startup_probe_start")
            ?.let { ru.seasonvar.tv.ui.navigation.Section.fromStorage(it) } else null
        startupMark("models_created")
        if (startupProbe) window.decorView.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
            private var captured = false
            override fun onDraw() {
                if (captured) return
                captured = true
                startupMark("first_on_draw")
                window.decorView.post { window.decorView.viewTreeObserver.removeOnDrawListener(this) }
            }
        })
        setContent {
            val accountSession by container.account.session.collectAsStateWithLifecycle()
            val cachedAccountOwner by container.sessions.personalCacheOwner.collectAsStateWithLifecycle()
            val accountState by accountModel.state.collectAsStateWithLifecycle()
            val removalState by removalModel.state.collectAsStateWithLifecycle()
            val smartState by smartModel.state.collectAsStateWithLifecycle()
            val marksRevision by container.account.marksRevision.collectAsStateWithLifecycle()
            LaunchedEffect(marksRevision) {
                val changes = container.cardData.changesSince(markRevision)
                for (change in changes) {
                    personalModels.forEach { (feed, model) -> model.markChanged(feed, change) }
                    smartModel.markChanged(change, container.cardData.knownDetails(change.entry.id))
                }
                markRevision = marksRevision
            }
            val personalStates = personalModels.mapValues { (_, model) -> model.state.collectAsStateWithLifecycle().value }
            LaunchedEffect(accountSession.version) {
                if (accountSession.version > 0) {
                    models.values.forEach { it.sessionChanged() }
                    browseModel.sessionChanged()
                    searchModel.sessionChanged()
                }
            }
            val search by searchModel.state.collectAsStateWithLifecycle()
            val states = models.mapValues { (_, model) -> model.state.collectAsStateWithLifecycle().value }
            val network by container.networkSettings.configuration.collectAsStateWithLifecycle()
            val connectionCheck by connectionCheckModel.state.collectAsStateWithLifecycle()
            val imagePrefetchEnabled by container.interfaceSettings.imagePrefetchEnabled.collectAsStateWithLifecycle()
            val listPrefetchEnabled by container.interfaceSettings.listPrefetchEnabled.collectAsStateWithLifecycle()
            val showComments by container.interfaceSettings.showComments.collectAsStateWithLifecycle()
            val showActors by container.interfaceSettings.showActors.collectAsStateWithLifecycle()
            val interfaceTheme by container.interfaceSettings.interfaceTheme.collectAsStateWithLifecycle()
            val fontSize by container.interfaceSettings.fontSize.collectAsStateWithLifecycle()
            val catalogLayouts by container.interfaceSettings.catalogLayouts.collectAsStateWithLifecycle()
            val startPage by container.interfaceSettings.startPage.collectAsStateWithLifecycle()
            val hiddenSections by container.interfaceSettings.hiddenSections.collectAsStateWithLifecycle()
            val menuOrder by container.interfaceSettings.menuOrder.collectAsStateWithLifecycle()
            LaunchedEffect(accountState.busy, accountSession.version, hiddenSections) {
                if (!accountState.busy) {
                    withFrameNanos { }
                    listRefresh.preloadMenuLists(accountState.busy, accountSession.user != null, hiddenSections)
                }
            }
            val autoNextEnabled by container.interfaceSettings.autoNextEnabled.collectAsStateWithLifecycle()
            val autoNextSeasonEnabled by container.interfaceSettings.autoNextSeasonEnabled.collectAsStateWithLifecycle()
            val playbackSpeed by container.interfaceSettings.playbackSpeeds.defaultSpeed.collectAsStateWithLifecycle()
            val showPlayerControls by container.interfaceSettings.showPlayerControls.collectAsStateWithLifecycle()
            val playerClockPosition by container.interfaceSettings.playerClockPosition.collectAsStateWithLifecycle()
            var previewEnabled by remember { mutableStateOf(true) }
            var startupPreview by remember { mutableStateOf<PersonalStartPreview?>(null) }
            val firstSection = remember { probeStart ?: startPage }
            LaunchedEffect(previewEnabled) {
                if (previewEnabled && firstSection.requiresAccount && firstSection !in hiddenSections)
                    startupPreview = loadPersonalStartPreview(firstSection, container)
            }
            val currentPreview = startupPreview?.takeIf {
                previewEnabled && it.isAvailable(accountSession, accountState) &&
                    (it.owner == cachedAccountOwner || it.owner == accountSession.user?.id)
            }
            val livePreviewList = currentPreview?.let {
                if (it.section == Section.SMART_CONTINUE) smartState else personalStates[it.section.personalFeed]
            }
            val discardPreview = startupPreview != null && (currentPreview == null ||
                accountSession.user != null && currentPreview.isReplacedBy(livePreviewList))
            LaunchedEffect(discardPreview) { if (discardPreview) startupPreview = null }
            SeasonvarApp(
                browseCatalog = browseModel,
                updateModel = updateModel, translationSettingsModel = translationSettingsModel,
                imagePrefetchEnabled = imagePrefetchEnabled, onImagePrefetchChange = container.interfaceSettings::setImagePrefetchEnabled,
                listPrefetchEnabled = listPrefetchEnabled, onListPrefetchChange = container.interfaceSettings::setListPrefetchEnabled,
                showActors = showActors, onShowActorsChange = container.interfaceSettings::setShowActors,
                showComments = showComments, onShowCommentsChange = container.interfaceSettings::setShowComments,
                interfaceTheme = interfaceTheme, onInterfaceThemeChange = container.interfaceSettings::setInterfaceTheme,
                fontSize = fontSize, onFontSizeChange = container.interfaceSettings::setFontSize,
                catalogLayouts = catalogLayouts, onCatalogLayoutChange = container.interfaceSettings::setCatalogLayout,
                startPage = probeStart ?: startPage, onStartPageChange = container.interfaceSettings::setStartPage,
                hiddenSections = hiddenSections, onSectionVisibilityChange = container.interfaceSettings::setSectionVisible,
                menuOrder = menuOrder, onMenuOrderChange = container.interfaceSettings::setMenuOrder,
                autoNextEnabled = autoNextEnabled, onAutoNextChange = container.interfaceSettings::setAutoNextEnabled,
                autoNextSeasonEnabled = autoNextSeasonEnabled, onAutoNextSeasonChange = container.interfaceSettings::setAutoNextSeasonEnabled,
                playbackSpeed = playbackSpeed, onPlaybackSpeedChange = container.interfaceSettings.playbackSpeeds::setDefault,
                showPlayerControls = showPlayerControls, onShowPlayerControlsChange = container.interfaceSettings::setShowPlayerControls,
                playerClockPosition = playerClockPosition, onPlayerClockPositionChange = container.interfaceSettings::setPlayerClockPosition,
                detailsModel = detailsModel,
                removalState = removalState, onRemovalActions = { entry, fromDetails -> removalModel.show(entry, fromDetails = fromDetails) },
                onRemovalRefresh = removalModel::refreshStatus, onRemovalSetStatus = removalModel::selectStatus,
                onMarkEpisode = removalModel::markEpisode,
                onRemovalAsk = removalModel::ask, onRemovalDismiss = removalModel::dismiss, onRemovalConfirm = removalModel::confirm,
                accountSession = accountSession, accountState = accountState,
                personalStartPreview = currentPreview,
                personalStates = personalStates,
                historyClear = historyClearModel,
                personalFilters = personalModels.mapNotNull { (feed, model) -> model.filters?.let { feed to it } }.toMap(),
                publicFilters = models.mapNotNull { (feed, model) -> model.filters?.let { feed to it } }.toMap(),
                smartState = smartState,
                onSmartVisible = smartModel::setVisible, onSmartRefresh = smartModel::refresh,
                onLogin = { login, password -> previewEnabled = false; accountModel.login(login, password) },
                onSessionStorageChoice = accountModel::chooseSessionStorage,
                onLogout = { previewEnabled = false; accountModel.logout() }, onCheckAccount = accountModel::restore,
                onPersonalVisible = { feed ->
                    visiblePersonalFeed = feed
                    feed?.let { personalModels.getValue(it).activate() }
                },
                onPersonalRefresh = { personalModels.getValue(it).retryOrRefresh() },
                onPersonalMore = { personalModels.getValue(it).loadMore() },
                onPlay = { playbackLauncher.launch(PlayerActivity.intent(this, it)) },
                states = states,
                loadDescription = container.repository::description,
                cardData = container.cardData,
                searchState = search,
                onSearchQuery = searchModel::queryChanged,
                onSearchSubmit = searchModel::submit,
                onSearchRecall = searchModel::recall, onSearchClearHistory = searchModel::clearHistory,
                onSearchRemember = searchModel::rememberCurrentQuery,
                onSearchVisible = { visible -> if (visible) searchModel.activate() else searchModel.deactivate() },
                onRefresh = { feed -> models.getValue(feed).refresh() },
                onFeedVisible = { feed -> models.getValue(feed).activate() },
                onListVisibilityChange = { section ->
                    listRefresh.setVisible(section)
                    container.continueForeground = section == ru.seasonvar.tv.ui.navigation.Section.SMART_CONTINUE
                },
                connectionCheck = connectionCheck,
                onConnectionRefresh = connectionCheckModel::refresh,
                onConnectionVisible = connectionCheckModel::setVisible,
                bypassEnabled = network.bypassEnabled,
                onBypassChange = { enabled ->
                    container.networkSettings.setBypassEnabled(enabled)
                    networkChanged()
                },
                onExit = ::finishAndRemoveTask,
                proxy = network.proxy,
                onProxyChange = { proxy -> connectionCheckModel.reconfigure {
                    val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { container.selectProxy(proxy) }.isSuccess
                    }
                    if (saved && proxy != network.proxy) {
                        networkChanged()
                    }
                    saved
                } },
                dnsProvider = network.dnsProvider,
                onDnsChange = { provider ->
                    if (provider != network.dnsProvider) {
                        container.networkSettings.setDnsProvider(provider)
                        networkChanged()
                    }
                },
            )
        }
    }

    private suspend fun loadPersonalStartPreview(section: Section, container: SeasonvarApplication): PersonalStartPreview? {
        return try {
            val owner = container.account.startupOwner() ?: return null
            val ownerKey = markOwnerId(owner) ?: return null
            val feed = section.personalFeed
            val filters = if (feed == null) PersonalListFilters()
                else PersonalListFilters.from(container.listFilterPreferences.read(ownerKey, feed.catalogKey))
            val entries = if (feed == null) container.smartContinueCache.read(ownerKey)
                else container.account.cachedStartupPage(owner, feed, filters)?.entries
            if (entries.isNullOrEmpty() || container.account.startupOwner() != owner) return null
            PersonalStartPreview(section, owner,
                CatalogUiState(entries = entries, loaded = true, loading = false, cached = true), filters)
        } catch (_: SessionStorageException) { null }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        traceBackKey(event, "activity")
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        traceBackKey(event, "activity")
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (!event.isCanceled) onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onPause() {
        if (::searchModel.isInitialized) searchModel.rememberPendingQuery()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        refreshHistoryIfChanged((application as SeasonvarApplication).account.historyRevision.value)
        smartModel.invalidate()
        models.values.forEach { it.refreshIfDateChanged() }
        detailsModel.refreshResume()
        (application as SeasonvarApplication).accountMarks.retry()
        (application as SeasonvarApplication).homeChannels.requestRefresh()
    }

    private fun refreshHistoryIfChanged(revision: Long): Boolean {
        if (revision == historyRevision) return false
        historyRevision = revision
        personalModels.getValue(PersonalFeed.HISTORY).invalidate()
        if (visiblePersonalFeed == PersonalFeed.HISTORY) personalModels.getValue(PersonalFeed.HISTORY).activate()
        return true
    }
}
