package ru.seasonvar.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import ru.seasonvar.tv.ui.account.AccountViewModel
import ru.seasonvar.tv.ui.account.PersonalListViewModel
import ru.seasonvar.tv.data.account.PersonalFeed
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.seasonvar.tv.data.PublicFeed
import ru.seasonvar.tv.ui.player.PlayerActivity
import ru.seasonvar.tv.ui.details.SeriesDetailsViewModel
import ru.seasonvar.tv.ui.SeasonvarApp
import ru.seasonvar.tv.ui.navigation.traceBackKey
import ru.seasonvar.tv.ui.catalog.CatalogViewModel
import ru.seasonvar.tv.ui.search.SearchViewModel

class MainActivity : ComponentActivity() {
    private lateinit var updateModel: ru.seasonvar.tv.ui.update.UpdateViewModel
    private lateinit var smartModel: ru.seasonvar.tv.ui.smart.SmartContinueViewModel
    private lateinit var detailsModel: SeriesDetailsViewModel
    private lateinit var searchModel: SearchViewModel
    private lateinit var models: Map<PublicFeed, CatalogViewModel>

    private val playbackLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.let { data ->
            detailsModel.onPlaybackFinished(data.getStringExtra("season"), data.getStringExtra("translation"), data.getStringExtra("episode"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val container = application as SeasonvarApplication
        updateModel = ViewModelProvider(this, ru.seasonvar.tv.ui.update.UpdateViewModel.Factory(
            container.updates, BuildConfig.VERSION_NAME))[ru.seasonvar.tv.ui.update.UpdateViewModel::class.java]
        val translationSettingsModel = ViewModelProvider(this, ru.seasonvar.tv.ui.settings.TranslationSettingsViewModel.Factory(
            container.translationCatalog, container.interfaceSettings))[ru.seasonvar.tv.ui.settings.TranslationSettingsViewModel::class.java]
        val repository = container.repository
        models = PublicFeed.entries.associateWith { feed ->
            ViewModelProvider(this, CatalogViewModel.Factory(repository, feed))
                .get("catalog-" + feed.name, CatalogViewModel::class.java)
        }
        val accountModel = ViewModelProvider(this, AccountViewModel.Factory(container.account))[AccountViewModel::class.java]
        val personalModels = PersonalFeed.entries.associateWith { feed ->
            ViewModelProvider(this, PersonalListViewModel.Factory(container.account, repository, feed))
                .get("personal-" + feed.name, PersonalListViewModel::class.java)
        }
        smartModel = ViewModelProvider(this, ru.seasonvar.tv.ui.smart.SmartContinueViewModel.Factory(
            container.account, repository, container.smartContinueCache,
            { container.interfaceSettings.preferredTranslations.value }))[ru.seasonvar.tv.ui.smart.SmartContinueViewModel::class.java]
        searchModel = ViewModelProvider(this, SearchViewModel.Factory(repository, container.searchHistory))[SearchViewModel::class.java]
        detailsModel = ViewModelProvider(this, SeriesDetailsViewModel.Factory(repository, container.playbackProgress, container.account,
            preferredTranslations = { container.interfaceSettings.preferredTranslations.value }))[SeriesDetailsViewModel::class.java]
        val details = detailsModel
        val removalModel = ViewModelProvider(this, ru.seasonvar.tv.ui.account.MarkRemovalViewModel.Factory(
            container.account, container.accountMarks, { target ->
                personalModels.values.forEach { it.markRemoved(target) }
                details.markRemoved(target)
            }, onChanged = { target, status ->
                personalModels.forEach { (feed, model) ->
                    val keepsEntry = when (feed) {
                        PersonalFeed.HISTORY -> true
                        PersonalFeed.WANT -> status == ru.seasonvar.tv.data.account.AccountListStatus.WANT_TO_WATCH
                        PersonalFeed.WATCHED -> status == ru.seasonvar.tv.data.account.AccountListStatus.WATCHED
                        else -> status == ru.seasonvar.tv.data.account.AccountListStatus.WATCHING
                    }
                    if (!keepsEntry) model.markRemoved(target)
                }
                details.markChanged(target)
            }))[ru.seasonvar.tv.ui.account.MarkRemovalViewModel::class.java]
        setContent {
            val accountSession by container.account.session.collectAsStateWithLifecycle()
            val accountState by accountModel.state.collectAsStateWithLifecycle()
            val removalState by removalModel.state.collectAsStateWithLifecycle()
            val smartState by smartModel.state.collectAsStateWithLifecycle()
            val smartDescription by smartModel.description.collectAsStateWithLifecycle()
            val marksRevision by container.account.marksRevision.collectAsStateWithLifecycle()
            LaunchedEffect(marksRevision) {
                if (marksRevision > 0) {
                    smartModel.invalidate()
                    personalModels.values.forEach { it.refreshIfActive() }
                    models.values.forEach { it.refreshIfStarted() }
                    searchModel.marksChanged()
                    detailsModel.refreshResume()
                }
            }
            val personalStates = personalModels.mapValues { (_, model) -> model.state.collectAsStateWithLifecycle().value }
            val personalDescriptions = personalModels.mapValues { (_, model) -> model.description.collectAsStateWithLifecycle().value }
            LaunchedEffect(accountSession.version) {
                if (accountSession.version > 0) {
                    models.values.forEach { it.sessionChanged() }
                    searchModel.sessionChanged()
                }
            }
            val search by searchModel.state.collectAsStateWithLifecycle()
            val searchDescription by searchModel.description.collectAsStateWithLifecycle()
            val states = models.mapValues { (_, model) -> model.state.collectAsStateWithLifecycle().value }
            val descriptions = models.mapValues { (_, model) -> model.description.collectAsStateWithLifecycle().value }
            val network by container.networkSettings.configuration.collectAsStateWithLifecycle()
            val catalogLayouts by container.interfaceSettings.catalogLayouts.collectAsStateWithLifecycle()
            val startPage by container.interfaceSettings.startPage.collectAsStateWithLifecycle()
            val autoNextEnabled by container.interfaceSettings.autoNextEnabled.collectAsStateWithLifecycle()
            val autoNextSeasonEnabled by container.interfaceSettings.autoNextSeasonEnabled.collectAsStateWithLifecycle()
            SeasonvarApp(
                updateModel = updateModel, translationSettingsModel = translationSettingsModel,
                catalogLayouts = catalogLayouts, onCatalogLayoutChange = container.interfaceSettings::setCatalogLayout,
                startPage = startPage, onStartPageChange = container.interfaceSettings::setStartPage,
                autoNextEnabled = autoNextEnabled, onAutoNextChange = container.interfaceSettings::setAutoNextEnabled,
                autoNextSeasonEnabled = autoNextSeasonEnabled, onAutoNextSeasonChange = container.interfaceSettings::setAutoNextSeasonEnabled,
                detailsModel = detailsModel,
                removalState = removalState, onRemovalActions = { removalModel.show(it) },
                onRemovalRefresh = removalModel::refreshStatus, onRemovalSetStatus = removalModel::selectStatus,
                onRemovalAsk = removalModel::ask, onRemovalDismiss = removalModel::dismiss, onRemovalConfirm = removalModel::confirm,
                accountSession = accountSession, accountState = accountState,
                personalStates = personalStates, personalDescriptions = personalDescriptions,
                smartState = smartState, smartDescription = smartDescription,
                onSmartVisible = smartModel::setVisible, onSmartRefresh = smartModel::refresh,
                onSmartSelect = smartModel::select,
                onLogin = accountModel::login, onLogout = accountModel::logout, onCheckAccount = accountModel::restore,
                onPersonalVisible = { personalModels.getValue(it).activate() },
                onPersonalRefresh = { personalModels.getValue(it).retryOrRefresh() },
                onPersonalMore = { personalModels.getValue(it).loadMore() },
                onPersonalSelect = { feed, entry, retry -> personalModels.getValue(feed).select(entry, retry) },
                onPlay = { playbackLauncher.launch(PlayerActivity.intent(this, it)) },
                states = states,
                descriptions = descriptions,
                searchState = search,
                searchDescription = searchDescription,
                onSearchQuery = searchModel::queryChanged,
                onSearchSubmit = searchModel::submit,
                onSearchRecall = searchModel::recall, onSearchClearHistory = searchModel::clearHistory,
                onSearchRemember = searchModel::rememberCurrentQuery,
                onSearchSelect = searchModel::select,
                onSearchVisible = { visible -> if (visible) searchModel.activate() else searchModel.deactivate() },
                onRefresh = { feed -> models.getValue(feed).refresh() },
                onSelect = { feed, entry, retry -> models.getValue(feed).select(entry, retry) },
                onFeedVisible = { feed -> models.getValue(feed).activate() },
                bypassEnabled = network.bypassEnabled,
                onBypassChange = { enabled ->
                    container.networkSettings.setBypassEnabled(enabled)
                    smartModel.invalidate()
                    models.values.forEach { it.networkChanged() }
                    searchModel.networkChanged()
                    personalModels.values.forEach { it.refreshIfActive() }
                    models.values.forEach { it.refreshIfStarted() }
                    searchModel.marksChanged()
                },
                onExit = ::finishAndRemoveTask,
                dnsProvider = network.dnsProvider,
                onDnsChange = { provider ->
                    if (provider != network.dnsProvider) {
                        container.networkSettings.setDnsProvider(provider)
                        smartModel.invalidate()
                        models.values.forEach { it.networkChanged() }
                        searchModel.networkChanged()
                        personalModels.values.forEach { it.refreshIfActive() }
                    models.values.forEach { it.refreshIfStarted() }
                    searchModel.marksChanged()
                    }
                },
            )
        }
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
        smartModel.invalidate()
        models.values.forEach { it.refreshIfDateChanged() }
        detailsModel.refreshResume()
        (application as SeasonvarApplication).accountMarks.retry()
    }
}
