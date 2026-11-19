package ru.seasonvar.tv.ui

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import ru.seasonvar.tv.ui.catalog.CatalogHeading
import ru.seasonvar.tv.ui.catalog.catalogHeaderMetadata
import ru.seasonvar.tv.ui.catalog.CatalogLayout
import ru.seasonvar.tv.ui.catalog.CatalogCardMetrics
import ru.seasonvar.tv.ui.catalog.rememberCatalogCardMetrics
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.*
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import ru.seasonvar.tv.ui.navigation.CardActivation
import ru.seasonvar.tv.ui.navigation.removalNeighbor
import ru.seasonvar.tv.ui.account.MarkRemovalUiState
import ru.seasonvar.tv.ui.account.MarkRemovalDialog
import ru.seasonvar.tv.data.account.AccountSession
import ru.seasonvar.tv.ui.account.AccountUiState
import ru.seasonvar.tv.ui.account.AccountPane
import ru.seasonvar.tv.ui.account.AccountEntryDetails
import ru.seasonvar.tv.BuildConfig
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.playback.PlaybackRequest
import ru.seasonvar.tv.ui.details.PosterBackdropLayer
import ru.seasonvar.tv.ui.details.PosterBackdropState
import ru.seasonvar.tv.ui.details.PosterBackdropPublisher
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import ru.seasonvar.tv.ui.settings.InterfaceTheme
import ru.seasonvar.tv.ui.settings.SettingsPane
import ru.seasonvar.tv.data.playback.PlaybackSpeed
import ru.seasonvar.tv.ui.player.PlayerClockPosition
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.CatalogFilters
import ru.seasonvar.tv.data.PublicFeed
import ru.seasonvar.tv.data.network.DnsProvider
import ru.seasonvar.tv.ui.details.SeriesDetailsPane
import ru.seasonvar.tv.ui.details.SeriesDetailsViewModel
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import ru.seasonvar.tv.ui.navigation.Section
import ru.seasonvar.tv.ui.navigation.MainFocus
import ru.seasonvar.tv.ui.navigation.MainStartupNavigation
import ru.seasonvar.tv.ui.navigation.MainSearchNavigation
import ru.seasonvar.tv.ui.navigation.syncMainAccountNavigation
import ru.seasonvar.tv.ui.navigation.MainContentTarget
import ru.seasonvar.tv.ui.navigation.requestMainContentFocus
import ru.seasonvar.tv.ui.navigation.rememberMainCatalogPositions
import ru.seasonvar.tv.ui.navigation.MenuGroup
import ru.seasonvar.tv.ui.navigation.BackNavigation
import ru.seasonvar.tv.ui.navigation.backNavigationKeys
import ru.seasonvar.tv.ui.catalog.DescriptionState
import ru.seasonvar.tv.ui.catalog.LoadFailure
import ru.seasonvar.tv.ui.catalog.CatalogUiState
import ru.seasonvar.tv.data.account.PersonalFeed
import ru.seasonvar.tv.ui.search.SearchUiState
import java.time.format.DateTimeFormatter

private data class RemovalFocusOrigin(val catalogKey: String, val keys: List<String>, val selected: String)

private fun personalTitle(feed: PersonalFeed) = when (feed) {
    PersonalFeed.RESUME -> R.string.resume_title
    PersonalFeed.UPDATES -> R.string.personal_updates_title
    PersonalFeed.NO_UPDATES -> R.string.personal_no_updates_title
    PersonalFeed.WANT -> R.string.personal_want_title
    PersonalFeed.WATCHED -> R.string.personal_watched_title
    PersonalFeed.HISTORY -> R.string.history_title
}

internal fun sectionTitle(item: Section): Int = when (item) {
    Section.CATALOG -> R.string.browse_catalog_title
    Section.SEARCH -> R.string.search_title
    Section.SMART_CONTINUE -> R.string.smart_continue_title
    Section.ACCOUNT -> R.string.account_title
    Section.RESUME, Section.PERSONAL_UPDATES, Section.PERSONAL_NO_UPDATES, Section.WANT, Section.WATCHED, Section.HISTORY ->
        personalTitle(requireNotNull(item.personalFeed))
    Section.TODAY -> R.string.today_title
    Section.UPDATES -> R.string.updates_title
    Section.TOP -> R.string.top_title
    Section.NEWEST -> R.string.newest_title
    Section.BEST -> R.string.best_title
    Section.ANNOUNCEMENTS -> R.string.announcements_title
    Section.SETTINGS -> R.string.settings_title
    Section.ABOUT -> R.string.about_title
}

internal fun menuGroupTitle(group: MenuGroup): Int = when (group) {
    MenuGroup.CATALOG -> R.string.menu_catalog
    MenuGroup.SMART -> R.string.menu_smart
    MenuGroup.PERSONAL -> R.string.menu_personal
    MenuGroup.SYSTEM -> R.string.menu_system
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SeasonvarApp(
    states: Map<PublicFeed, CatalogUiState>,
    onRefresh: (PublicFeed) -> Unit,
    loadDescription: (suspend (SeriesEntry) -> ru.seasonvar.tv.data.SeriesDescription)? = null,
    cardData: ru.seasonvar.tv.data.cache.CardDataCache? = null,
    browseCatalog: ru.seasonvar.tv.ui.catalog.BrowseCatalogController? = null,
    onFeedVisible: (PublicFeed) -> Unit = {},
    onListVisibilityChange: ((Section?) -> Unit)? = null,
    searchState: SearchUiState = SearchUiState(),
    onSearchQuery: (String) -> Unit = {},
    onSearchSubmit: () -> Unit = {},
    onSearchRecall: (String) -> Unit = {},
    onSearchClearHistory: () -> Unit = {},
    onSearchRemember: () -> Unit = {},
    onSearchVisible: (Boolean) -> Unit = {},
    bypassEnabled: Boolean = true,
    onBypassChange: (Boolean) -> Unit = {},
    dnsProvider: DnsProvider = DnsProvider.QUAD9_DOH,
    onDnsChange: (DnsProvider) -> Unit = {},
    proxy: ru.seasonvar.tv.data.network.ProxyConfiguration = ru.seasonvar.tv.data.network.ProxyConfiguration.Disabled,
    onProxyChange: suspend (ru.seasonvar.tv.data.network.ProxyConfiguration) -> Boolean = { true },
    connectionCheck: ru.seasonvar.tv.ui.settings.ConnectionCheckState = ru.seasonvar.tv.ui.settings.ConnectionCheckState(),
    onConnectionRefresh: () -> Unit = {},
    onConnectionVisible: (Boolean) -> Unit = {},
    imagePrefetchEnabled: Boolean = true,
    onImagePrefetchChange: (Boolean) -> Unit = {},
    listPrefetchEnabled: Boolean = false,
    onListPrefetchChange: (Boolean) -> Unit = {},
    showActors: Boolean = true,
    onShowActorsChange: (Boolean) -> Unit = {},
    interfaceTheme: InterfaceTheme = SeasonvarTheme.interfaceTheme,
    onInterfaceThemeChange: (InterfaceTheme) -> Unit = {},
    autoNextSeasonEnabled: Boolean = true,
    onAutoNextSeasonChange: (Boolean) -> Unit = {},
    autoNextEnabled: Boolean = true,
    onAutoNextChange: (Boolean) -> Unit = {},
    playbackSpeed: PlaybackSpeed = PlaybackSpeed.NORMAL,
    onPlaybackSpeedChange: (PlaybackSpeed) -> Unit = {},
    showPlayerControls: Boolean = true,
    onShowPlayerControlsChange: (Boolean) -> Unit = {},
    playerClockPosition: PlayerClockPosition = PlayerClockPosition.OFF,
    onPlayerClockPositionChange: (PlayerClockPosition) -> Unit = {},
    catalogLayouts: Map<Section, CatalogLayout> = emptyMap(),
    onCatalogLayoutChange: (Section, CatalogLayout) -> Unit = { _, _ -> },
    startPage: Section = Section.TODAY,
    onStartPageChange: (Section) -> Unit = {},
    hiddenSections: Set<Section> = emptySet(),
    onSectionVisibilityChange: (Section, Boolean) -> Unit = { _, _ -> },
    onExit: () -> Unit = {},
    updateModel: ru.seasonvar.tv.ui.update.UpdateViewModel? = null,
    translationSettingsModel: ru.seasonvar.tv.ui.settings.TranslationSettingsViewModel? = null,
    detailsModel: SeriesDetailsViewModel? = null,
    onPlay: (PlaybackRequest) -> Unit = {},
    accountSession: AccountSession = AccountSession(),
    accountState: AccountUiState = AccountUiState(busy = false),
    personalStartPreview: ru.seasonvar.tv.ui.navigation.PersonalStartPreview? = null,
    personalStates: Map<PersonalFeed, CatalogUiState> = emptyMap(),
    smartState: CatalogUiState = CatalogUiState(loading = false),
    onSmartVisible: (Boolean) -> Unit = {},
    onSmartRefresh: () -> Unit = {},
    onLogin: (String, String) -> Unit = { _, _ -> },
    onLogout: () -> Unit = {},
    onCheckAccount: () -> Unit = {},
    personalFilters: Map<PersonalFeed, ru.seasonvar.tv.ui.filters.ListFiltersController> = emptyMap(),
    publicFilters: Map<PublicFeed, ru.seasonvar.tv.ui.filters.ListFiltersController> = emptyMap(),
    historyClear: ru.seasonvar.tv.ui.account.HistoryClearController? = null,
    onPersonalVisible: (PersonalFeed?) -> Unit = {},
    onPersonalRefresh: (PersonalFeed) -> Unit = {},
    onPersonalMore: (PersonalFeed) -> Unit = {},
    removalState: MarkRemovalUiState = MarkRemovalUiState(),
    onRemovalActions: (SeriesEntry) -> Unit = {},
    onRemovalRefresh: () -> Unit = {},
    onRemovalSetStatus: (ru.seasonvar.tv.data.account.AccountListStatus) -> Unit = {},
    onRemovalAsk: () -> Unit = {},
    onRemovalDismiss: () -> Unit = {},
    onRemovalConfirm: () -> Unit = {},
) {
    SeasonvarTheme(interfaceTheme) {
        SeasonvarContent(
            states = states,
            onRefresh = onRefresh,
            loadDescription = loadDescription,
            cardData = cardData,
            browseCatalog = browseCatalog,
            onFeedVisible = onFeedVisible,
            onListVisibilityChange = onListVisibilityChange,
            searchState = searchState,
            onSearchQuery = onSearchQuery,
            onSearchSubmit = onSearchSubmit,
            onSearchRecall = onSearchRecall, onSearchClearHistory = onSearchClearHistory, onSearchRemember = onSearchRemember,
            onSearchVisible = onSearchVisible,
            bypassEnabled = bypassEnabled,
            onBypassChange = onBypassChange,
            dnsProvider = dnsProvider,
            onDnsChange = onDnsChange, proxy = proxy, onProxyChange = onProxyChange,
            connectionCheck = connectionCheck, onConnectionRefresh = onConnectionRefresh,
            onConnectionVisible = onConnectionVisible,
            imagePrefetchEnabled = imagePrefetchEnabled, onImagePrefetchChange = onImagePrefetchChange,
            listPrefetchEnabled = listPrefetchEnabled, onListPrefetchChange = onListPrefetchChange,
            showActors = showActors, onShowActorsChange = onShowActorsChange,
            interfaceTheme = interfaceTheme, onInterfaceThemeChange = onInterfaceThemeChange,
            catalogLayouts = catalogLayouts, onCatalogLayoutChange = onCatalogLayoutChange,
            startPage = startPage, onStartPageChange = onStartPageChange,
            hiddenSections = hiddenSections, onSectionVisibilityChange = onSectionVisibilityChange,
            autoNextEnabled = autoNextEnabled, onAutoNextChange = onAutoNextChange,
            autoNextSeasonEnabled = autoNextSeasonEnabled, onAutoNextSeasonChange = onAutoNextSeasonChange,
            playerClockPosition = playerClockPosition, onPlayerClockPositionChange = onPlayerClockPositionChange,
            playbackSpeed = playbackSpeed, onPlaybackSpeedChange = onPlaybackSpeedChange,
            showPlayerControls = showPlayerControls, onShowPlayerControlsChange = onShowPlayerControlsChange,
            onExit = onExit,
            updateModel = updateModel, translationSettingsModel = translationSettingsModel,
            detailsModel = detailsModel,
            onPlay = onPlay,
            removalState = removalState, onRemovalActions = onRemovalActions,
            onRemovalRefresh = onRemovalRefresh, onRemovalSetStatus = onRemovalSetStatus,
            onRemovalAsk = onRemovalAsk, onRemovalDismiss = onRemovalDismiss, onRemovalConfirm = onRemovalConfirm,
            accountSession = accountSession, accountState = accountState, personalStartPreview = personalStartPreview,
            personalStates = personalStates,
            smartState = smartState,
            onSmartVisible = onSmartVisible, onSmartRefresh = onSmartRefresh,
            onLogin = onLogin, onLogout = onLogout, onCheckAccount = onCheckAccount,
            personalFilters = personalFilters, publicFilters = publicFilters, historyClear = historyClear,
            onPersonalVisible = onPersonalVisible, onPersonalRefresh = onPersonalRefresh, onPersonalMore = onPersonalMore,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeasonvarContent(
    states: Map<PublicFeed, CatalogUiState>,
    onRefresh: (PublicFeed) -> Unit,
    loadDescription: (suspend (SeriesEntry) -> ru.seasonvar.tv.data.SeriesDescription)?,
    cardData: ru.seasonvar.tv.data.cache.CardDataCache?,
    browseCatalog: ru.seasonvar.tv.ui.catalog.BrowseCatalogController? = null,
    onFeedVisible: (PublicFeed) -> Unit = {},
    onListVisibilityChange: ((Section?) -> Unit)? = null,
    searchState: SearchUiState = SearchUiState(),
    onSearchQuery: (String) -> Unit = {},
    onSearchSubmit: () -> Unit = {},
    onSearchRecall: (String) -> Unit = {},
    onSearchClearHistory: () -> Unit = {},
    onSearchRemember: () -> Unit = {},
    onSearchVisible: (Boolean) -> Unit = {},
    bypassEnabled: Boolean = true,
    onBypassChange: (Boolean) -> Unit = {},
    dnsProvider: DnsProvider = DnsProvider.QUAD9_DOH,
    onDnsChange: (DnsProvider) -> Unit = {},
    proxy: ru.seasonvar.tv.data.network.ProxyConfiguration = ru.seasonvar.tv.data.network.ProxyConfiguration.Disabled,
    onProxyChange: suspend (ru.seasonvar.tv.data.network.ProxyConfiguration) -> Boolean = { true },
    connectionCheck: ru.seasonvar.tv.ui.settings.ConnectionCheckState = ru.seasonvar.tv.ui.settings.ConnectionCheckState(),
    onConnectionRefresh: () -> Unit = {},
    onConnectionVisible: (Boolean) -> Unit = {},
    imagePrefetchEnabled: Boolean = true,
    onImagePrefetchChange: (Boolean) -> Unit = {},
    listPrefetchEnabled: Boolean = false,
    onListPrefetchChange: (Boolean) -> Unit = {},
    showActors: Boolean = true,
    onShowActorsChange: (Boolean) -> Unit = {},
    interfaceTheme: InterfaceTheme = SeasonvarTheme.interfaceTheme,
    onInterfaceThemeChange: (InterfaceTheme) -> Unit = {},
    autoNextSeasonEnabled: Boolean = true,
    onAutoNextSeasonChange: (Boolean) -> Unit = {},
    autoNextEnabled: Boolean = true,
    onAutoNextChange: (Boolean) -> Unit = {},
    playbackSpeed: PlaybackSpeed = PlaybackSpeed.NORMAL,
    onPlaybackSpeedChange: (PlaybackSpeed) -> Unit = {},
    showPlayerControls: Boolean = true,
    onShowPlayerControlsChange: (Boolean) -> Unit = {},
    playerClockPosition: PlayerClockPosition = PlayerClockPosition.OFF,
    onPlayerClockPositionChange: (PlayerClockPosition) -> Unit = {},
    catalogLayouts: Map<Section, CatalogLayout> = emptyMap(),
    onCatalogLayoutChange: (Section, CatalogLayout) -> Unit = { _, _ -> },
    startPage: Section = Section.TODAY,
    onStartPageChange: (Section) -> Unit = {},
    hiddenSections: Set<Section> = emptySet(),
    onSectionVisibilityChange: (Section, Boolean) -> Unit = { _, _ -> },
    onExit: () -> Unit = {},
    updateModel: ru.seasonvar.tv.ui.update.UpdateViewModel? = null,
    translationSettingsModel: ru.seasonvar.tv.ui.settings.TranslationSettingsViewModel? = null,
    detailsModel: SeriesDetailsViewModel? = null,
    onPlay: (PlaybackRequest) -> Unit = {},
    accountSession: AccountSession = AccountSession(),
    accountState: AccountUiState = AccountUiState(busy = false),
    personalStartPreview: ru.seasonvar.tv.ui.navigation.PersonalStartPreview? = null,
    personalStates: Map<PersonalFeed, CatalogUiState> = emptyMap(),
    smartState: CatalogUiState = CatalogUiState(loading = false),
    onSmartVisible: (Boolean) -> Unit = {},
    onSmartRefresh: () -> Unit = {},
    onLogin: (String, String) -> Unit = { _, _ -> },
    onLogout: () -> Unit = {},
    onCheckAccount: () -> Unit = {},
    personalFilters: Map<PersonalFeed, ru.seasonvar.tv.ui.filters.ListFiltersController> = emptyMap(),
    publicFilters: Map<PublicFeed, ru.seasonvar.tv.ui.filters.ListFiltersController> = emptyMap(),
    historyClear: ru.seasonvar.tv.ui.account.HistoryClearController? = null,
    onPersonalVisible: (PersonalFeed?) -> Unit = {},
    onPersonalRefresh: (PersonalFeed) -> Unit = {},
    onPersonalMore: (PersonalFeed) -> Unit = {},
    removalState: MarkRemovalUiState = MarkRemovalUiState(),
    onRemovalActions: (SeriesEntry) -> Unit = {},
    onRemovalRefresh: () -> Unit = {},
    onRemovalSetStatus: (ru.seasonvar.tv.data.account.AccountListStatus) -> Unit = {},
    onRemovalAsk: () -> Unit = {},
    onRemovalDismiss: () -> Unit = {},
    onRemovalConfirm: () -> Unit = {},
) {
    val initialStartPage = remember { startPage }
    val startupPendingState = rememberSaveable { mutableStateOf(true) }
    var startupPending by startupPendingState
    var menuOwnsInput by remember { mutableStateOf(true) }
    val signedIn = accountSession.user != null
    val preview = personalStartPreview?.takeIf {
        it.section == initialStartPage && it.section !in hiddenSections && it.isAvailable(accountSession, accountState)
    }
    val sectionState = rememberSaveable {
        mutableStateOf(preview?.section ?: initialStartPage.availableAtStart(signedIn, hiddenSections))
    }
    var section by sectionState
    val previewSection = preview?.section?.takeIf { startupPending || section == it }
    val visibleMenuGroups = remember(signedIn, previewSection, hiddenSections) {
        val groups = Section.menuGroups(signedIn || previewSection != null, hiddenSections)
        if (signedIn || previewSection == null) groups else groups.mapNotNull { (group, pages) ->
            val visible = pages.filter { !it.requiresAccount || it == previewSection }
            visible.takeIf { it.isNotEmpty() }?.let { group to it }
        }
    }
    val visibleMenuPages = remember(visibleMenuGroups) { visibleMenuGroups.flatMap { it.second } }
    val startupTarget = previewSection ?: initialStartPage.atStartup(signedIn, accountState.busy, hiddenSections)
    val waitingForStartPage = startupPending && section != startupTarget
    val browsing = section == Section.CATALOG
    val browseState = browseCatalog?.state?.collectAsStateWithLifecycle()?.value ?: ru.seasonvar.tv.ui.catalog.BrowseCatalogState()
    val browseConditions = remember(browseState.applied) { CatalogFilters.defaults.mapValues { browseState.applied[it.key] } }
    val catalogLayout = if (browsing) CatalogLayout.STRIP else catalogLayouts[section] ?: CatalogLayout.STRIP
    var activeFeed by rememberSaveable { mutableStateOf(section.publicFeed ?: PublicFeed.TODAY) }
    var selectedKeys by rememberSaveable { mutableStateOf(mapOf<String, String>()) }
    var browseSelectedKey by rememberSaveable(browseConditions) { mutableStateOf<String?>(null) }
    val smart = section == Section.SMART_CONTINUE
    val searching = section == Section.SEARCH
    val personalFeed = section.personalFeed
    val catalogKey = if (browsing) "CATALOG" else if (searching) "SEARCH" else if (smart) "SMART_CONTINUE" else if (personalFeed != null) personalFeed.catalogKey else activeFeed.name
    val liveState = if (browsing) browseState.page else if (searching) searchState.results else if (smart) smartState else if (personalFeed != null) personalStates[personalFeed] ?: CatalogUiState(loading = false) else states.getValue(activeFeed)
    val pendingPreview = preview?.takeIf {
        it.section == section && (!signedIn || !it.isReplacedBy(liveState))
    }
    val previewLocked = pendingPreview != null && !signedIn
    val displayUser = accountSession.user?.id ?: pendingPreview?.owner
    val displayVersion = if (previewLocked) 1L else accountSession.version
    val sourceState = when {
        waitingForStartPage -> CatalogUiState()
        pendingPreview != null -> pendingPreview.state.copy(failure = liveState.failure)
        section.requiresAccount && !signedIn -> CatalogUiState()
        else -> liveState
    }
    val selectedKey = if (browsing) browseSelectedKey else selectedKeys[catalogKey]
    val positions = rememberMainCatalogPositions(browseConditions)
    val position = positions.forSection(section, activeFeed)
    val activeFilters = (if (personalFeed != null) personalFilters[personalFeed] else publicFilters[activeFeed])
        ?.state?.collectAsStateWithLifecycle()?.value?.values
    val listConditions = when {
        browsing -> browseConditions
        searching -> searchState.query
        personalFeed != null -> pendingPreview?.filters
            ?: ru.seasonvar.tv.data.account.PersonalListFilters.from(activeFilters.orEmpty())
        smart -> null
        else -> activeFilters
    }
    val state = ru.seasonvar.tv.ui.catalog.parsedCatalogState(sourceState, selectedKey,
        listOf(catalogKey, displayUser, displayVersion, listConditions)) { entries ->
        position.keepSelectionVisible(catalogLayout, catalogKey, selectedKey, entries)
    }
    var searchEditRequest by remember { mutableIntStateOf(0) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    ru.seasonvar.tv.ui.navigation.ListVisibilityEffect(section.takeIf {
        !waitingForStartPage && !previewLocked && !detailsOpen && (it.publicFeed != null || it.personalFeed != null ||
            it == Section.CATALOG || it == Section.SEARCH || it == Section.SMART_CONTINUE)
    }, onListVisibilityChange)
    var detailsSource by remember { mutableStateOf<SeriesEntry?>(null) }
    var removalOrigin by remember { mutableStateOf<RemovalFocusOrigin?>(null) }
    var removalWasVisible by remember { mutableStateOf(false) }
    val listState = position.list
    val gridState = position.grid
    val focus = remember { MainFocus() }
    var returningToList by remember { mutableStateOf(false) }
    val pendingHistoryQueryState = remember { mutableStateOf<String?>(null) }
    var pendingHistoryQuery by pendingHistoryQueryState
    val scope = rememberCoroutineScope()
    val backNavigation = remember { BackNavigation() }
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { backNavigation.reset() }
    val inputModeManager = LocalInputModeManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val selected = remember(state.entries, selectedKey) {
        state.entries.firstOrNull { it.key == selectedKey } ?: state.entries.firstOrNull()
    }
    val latestCatalogKey by rememberUpdatedState(catalogKey)
    val latestSelected by rememberUpdatedState(selected)
    val latestState by rememberUpdatedState(state)
    val latestSearchState by rememberUpdatedState(searchState)
    val latestListState by rememberUpdatedState(listState)
    val latestGridState by rememberUpdatedState(gridState)
    val latestCatalogLayout by rememberUpdatedState(catalogLayout)

    fun selectEntry(key: String?) {
        if (browsing) browseSelectedKey = key
        else selectedKeys = if (key == null) selectedKeys - catalogKey
            else selectedKeys + (catalogKey to key)
    }

    fun selectSection(item: Section) {
        pendingHistoryQuery = null
        detailsOpen = false
        section = item
        val feed = item.publicFeed
        if (feed != null) {
            activeFeed = feed
        }
    }

    MainCatalogActivation(section, waitingForStartPage, browseCatalog,
        onFeedVisible, onSmartVisible, onPersonalVisible, onSearchVisible)
    LaunchedEffect(searchState.query) {
        selectedKeys = selectedKeys - "SEARCH"
        positions.search.list.scrollToItem(0)
        positions.search.grid.scrollToItem(0)
    }
    LaunchedEffect(catalogKey, state.entries) {
        if (state.entries.none { it.key == selectedKey }) {
            val origin = removalOrigin?.takeIf { it.catalogKey == catalogKey && it.selected == selectedKey }
            selectEntry(if (origin != null) removalNeighbor(origin.keys, state.entries.map { it.key }, origin.selected) else selected?.key)
        }
    }
    if (BuildConfig.BUILD_TYPE == "profile" && ru.seasonvar.tv.diagnostics.StartupDiagnostics.active) SideEffect {
        ru.seasonvar.tv.diagnostics.StartupDiagnostics.observe(initialStartPage, startupTarget, section, state, accountState.busy)
    }
    MainStartupNavigation(startupTarget, startupPendingState, sectionState,
        accountSession.user != null, hiddenSections, focus, ::selectSection)
    LaunchedEffect(previewSection, accountState.busy, accountSession.version) {
        if (previewSection == null && !accountState.busy && accountSession.version == 0L && !signedIn && section.requiresAccount)
            selectSection(initialStartPage.availableAtStart(false, hiddenSections))
    }
    LaunchedEffect(accountSession.version) {
        if (accountSession.version == 0L && preview != null) return@LaunchedEffect
        if (accountSession.version == 1L && preview?.section == section && accountSession.user?.id == preview.owner)
            return@LaunchedEffect
        syncMainAccountNavigation(accountSession, hiddenSections, visibleMenuPages, focus, positions,
            currentSection = { section }, menuOwnsInput = { menuOwnsInput },
            onClearPersonal = { selectedKeys = selectedKeys - it; removalOrigin = null },
            onCloseDetails = { detailsOpen = false }, onRequireSignIn = { section = Section.ACCOUNT }, onSelect = ::selectSection)
    }

    fun enterContent() {
        val requestedSection = section
        val restoring = returningToList
        scope.launch {
            try {
                requestMainContentFocus(requestedSection, { section }, {
                    MainContentTarget(latestSelected, latestState, latestListState, latestGridState,
                        latestCatalogLayout, latestSearchState, catalogKey)
                }, focus, inputModeManager, browsing, searching, { detailsOpen })
            } finally { if (restoring) returningToList = false }
        }
    }

    MainSearchNavigation(searching, searchState, pendingHistoryQueryState, focus, keyboard, ::enterContent)

    fun returnToMenu() {
        val requestedSection = section
        detailsOpen = false
        scope.launch {
            repeat(2) { withFrameNanos { } }
            if (section != requestedSection) return@launch
            focus.sectionFocus(section).requestFocus()
        }
    }

    fun closeDetails() {
        // Контейнер удерживает фокус до восстановления карточки ленивого списка.
        returningToList = true
        focus.contentFocus.requestFocus()
        detailsOpen = false
        enterContent()
    }

    fun openSeriesActions(entry: SeriesEntry) {
        backNavigation.reset()
        removalOrigin = RemovalFocusOrigin(catalogKey, state.entries.map { it.key }, if (detailsOpen) selectedKey ?: entry.key else entry.key)
        onRemovalActions(entry)
    }

    fun openDetails(entry: SeriesEntry) {
        if (section.requiresAccount && !signedIn) {
            showShortMessage(context, R.string.personal_start_requires_session)
            return
        }
        ru.seasonvar.tv.ui.details.DetailsPerformance.opened()
        pendingHistoryQuery = null
        if (searching) onSearchRemember()
        backNavigation.reset()
        inputModeManager.requestInputMode(InputMode.Keyboard)
        selectEntry(entry.key)
        detailsSource = entry
        searchEditRequest = 0
        keyboard?.hide()
        detailsOpen = true
    }

    LaunchedEffect(removalState.visible) {
        if (removalWasVisible && !removalState.visible) {
            repeat(2) { withFrameNanos { } }
            if (!detailsOpen && removalOrigin?.catalogKey == catalogKey) enterContent()
            removalOrigin = null
        }
        removalWasVisible = removalState.visible
    }

    fun navigateBack() {
        pendingHistoryQuery = null
        startupPending = false
        if ((searching || section == Section.ACCOUNT) && !detailsOpen && imeVisible) {
            keyboard?.hide()
            backNavigation.reset()
            return
        }
        val fromMenu = menuOwnsInput && !detailsOpen
        val exit = backNavigation.shouldExit(SystemClock.uptimeMillis(), fromMenu)
        if (BuildConfig.DEBUG) android.util.Log.d("SeasonvarInput", "back section=" + section + " exit=" + exit)
        if (exit) onExit()
        else {
            if (fromMenu) showShortMessage(context, R.string.back_again_to_exit)
            if (detailsOpen) closeDetails() else returnToMenu()
        }
    }

    BackHandler { navigateBack() }
    // Ссылка на локальную функцию не различает захваченные контексты в equals.
    val menuEnterContent by rememberUpdatedState { enterContent() }
    val d = SeasonvarTheme.dimensions
    val backdropSource = remember(catalogKey, section, detailsSource?.key, detailsOpen) { PosterBackdropState() }
    val onBackdropSource = remember(backdropSource) { backdropSource::attach }
    Box(
        Modifier.fillMaxSize()
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode !in setOf(
                        android.view.KeyEvent.KEYCODE_WAKEUP, android.view.KeyEvent.KEYCODE_SLEEP, android.view.KeyEvent.KEYCODE_POWER)) {
                    startupPending = false
                }
                false
            }
            .backNavigationKeys(::navigateBack, backNavigation::reset)
            .background(SeasonvarTheme.pageBackground),
    ) {
        val density = LocalDensity.current
        if (SeasonvarTheme.posterBackdropEnabled) BoxWithConstraints(Modifier.fillMaxSize()) {
            PosterBackdropLayer(backdropSource,
                with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) },
                ((d.screenPaddingHorizontal + d.menuWidth + d.contentGap) / maxWidth).coerceIn(0f, 0.6f),
                useFallbackImage = section == Section.ACCOUNT || section == Section.SETTINGS || section == Section.ABOUT)
        }
        Row(Modifier.fillMaxSize().padding(d.screenPaddingHorizontal, d.screenPaddingVertical),
            horizontalArrangement = Arrangement.spacedBy(d.contentGap)) {
            MainMenu(visibleMenuGroups, visibleMenuPages, section, accountSession, focus, browseCatalog != null,
                onMenuFocus = { menuOwnsInput = true; ru.seasonvar.tv.diagnostics.startupMark("first_menu_focus") },
                onSearch = { startupPending = false; pendingHistoryQuery = null; backNavigation.reset(); detailsOpen = false; section = Section.SEARCH; searchEditRequest++ },
                onBrowse = {
                    startupPending = false; backNavigation.reset(); selectSection(Section.CATALOG)
                    scope.launch { repeat(2) { withFrameNanos { } }; if (section == Section.CATALOG) focus.browseFiltersFocus.requestFocus() }
                }, onAccount = { startupPending = false; backNavigation.reset(); selectSection(Section.ACCOUNT) },
                onChoose = { startupPending = false; backNavigation.reset(); selectSection(it); menuEnterContent() },
                onFocused = ::selectSection, onEnter = { selectSection(it); menuEnterContent() })
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxHeight()
                    .focusRequester(focus.contentFocus).focusProperties { canFocus = returningToList }.focusTarget()
                    .onFocusChanged { if (it.hasFocus) menuOwnsInput = false }.focusGroup()
            ) {
                val cardMetrics = rememberCatalogCardMetrics(maxWidth, if (browsing) d.browseCatalogMinCardWidth else d.catalogMinCardWidth)
                if (section == Section.ACCOUNT) {
                    AccountPane(accountSession, accountState, focus.accountFocus, onLogin, onLogout, onCheckAccount)
                } else if (section == Section.SETTINGS) {
                    SettingsPane(
                        connectionCheck = connectionCheck, onConnectionRefresh = onConnectionRefresh,
                        onConnectionVisible = onConnectionVisible,
                        imagePrefetchEnabled = imagePrefetchEnabled, onImagePrefetchChange = onImagePrefetchChange,
                        listPrefetchEnabled = listPrefetchEnabled, onListPrefetchChange = onListPrefetchChange,
                        showActors = showActors, onShowActorsChange = onShowActorsChange,
                        interfaceTheme = interfaceTheme, onInterfaceThemeChange = onInterfaceThemeChange,
                        bypassEnabled = bypassEnabled, settingsContentFocus = focus.settingsContentFocus,
                        translationSettingsModel = translationSettingsModel,
                        menuFocus = focus.settingsFocus,
                        onBypassChange = onBypassChange,
                        dnsProvider = dnsProvider,
                        onDnsChange = onDnsChange, proxy = proxy, onProxyChange = onProxyChange,
                        startPage = startPage, onStartPageChange = onStartPageChange,
                        hiddenSections = hiddenSections, onSectionVisibilityChange = onSectionVisibilityChange,
                        autoNextEnabled = autoNextEnabled, onAutoNextChange = onAutoNextChange,
                        autoNextSeasonEnabled = autoNextSeasonEnabled, onAutoNextSeasonChange = onAutoNextSeasonChange,
                        playerClockPosition = playerClockPosition, onPlayerClockPositionChange = onPlayerClockPositionChange,
                        playbackSpeed = playbackSpeed, onPlaybackSpeedChange = onPlaybackSpeedChange,
                        showPlayerControls = showPlayerControls, onShowPlayerControlsChange = onShowPlayerControlsChange,
                        signedIn = accountSession.user != null,
                        onBack = ::navigateBack,
                        onCloseDialog = { backNavigation.reset(); returnToMenu() },
                        onInteraction = backNavigation::reset,
                    )
                } else if (section == Section.ABOUT) {
                    ru.seasonvar.tv.ui.about.AboutPane(focus.aboutContentFocus, focus.aboutFocus, backNavigation::reset, updateModel)
                } else if (detailsOpen && detailsSource != null && detailsModel != null && (!section.requiresAccount || signedIn)) {
                    SeriesDetailsPane(
                        detailsSource!!, detailsModel,
                        showActors = showActors, onBackdropSource = onBackdropSource,
                        onActions = if (accountSession.user == null) null else ::openSeriesActions,
                        removalDialogOpen = removalState.visible,
                        onPlay = onPlay,
                        menuFocus = focus.sectionFocus(section),
                        onInteraction = backNavigation::reset,
                    )
                } else {
                    MainCatalogContent(state, selected, section, activeFeed, catalogKey, position, focus, catalogLayout, cardMetrics,
                        signedIn, previewLocked, imagePrefetchEnabled && !previewLocked,
                        loadDescription, cardData,
                        displayVersion to displayUser, Triple(bypassEnabled, dnsProvider, proxy),
                        browseState, browseCatalog, searchState, searchEditRequest,
                        filters = if (personalFeed != null) personalFilters[personalFeed]
                            else if (!browsing && !searching && !smart) publicFilters[activeFeed] else null,
                        historyClear = if (personalFeed == PersonalFeed.HISTORY) historyClear else null,
                        onLayoutChange = { backNavigation.reset(); onCatalogLayoutChange(section, catalogLayout.other()) },
                        onEnterContent = ::enterContent,
                        onSearchQuery = { pendingHistoryQuery = null; onSearchQuery(it) },
                        onSearchSubmit = { keyboard?.hide(); onSearchSubmit(); if (state.entries.isNotEmpty()) enterContent() },
                        onSearchResults = { keyboard?.hide(); if (searchState.showHistory) {
                            if (searchState.recentQueries.isNotEmpty()) focus.searchHistoryFocus.requestFocus()
                        } else if (state.entries.isNotEmpty()) enterContent() },
                        onSearchRemember = onSearchRemember,
                        onSearchRecall = { pendingHistoryQuery = it; keyboard?.hide(); onSearchRecall(it) }, onSearchClearHistory = onSearchClearHistory,
                        onRefresh = { if (previewLocked) onCheckAccount() else if (browsing) browseCatalog?.refresh() else if (searching) onSearchSubmit() else if (smart) onSmartRefresh() else if (personalFeed != null) onPersonalRefresh(personalFeed) else onRefresh(activeFeed) },
                        onLoadMore = { personalFeed?.let(onPersonalMore) },
                        onFocused = { if (latestCatalogKey == catalogKey) selectEntry(it.key) }, onOpen = { openDetails(it) },
                        onActions = { selectEntry(it.key); openSeriesActions(it) }, onBackdropSource = onBackdropSource)
                }
            }
        }
    }
    historyClear?.let { ru.seasonvar.tv.ui.account.HistoryClearDialog(it, backNavigation::reset) }
    MarkRemovalDialog(removalState,
        onDismiss = { backNavigation.reset(); onRemovalDismiss() },
        onRefresh = { backNavigation.reset(); onRemovalRefresh() },
        onSelectStatus = { backNavigation.reset(); onRemovalSetStatus(it) },
        onAsk = { backNavigation.reset(); onRemovalAsk() },
        onConfirm = { backNavigation.reset(); onRemovalConfirm() },
        onInteraction = backNavigation::reset)
}

@Composable
internal fun ThemeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    @DrawableRes icon: Int? = null,
    badgeCount: Int = 0,
    loading: Boolean = false,
    loadingLabel: String? = null,
    avatarUrl: String? = null,
    avatarKey: Long = 0,
    enabled: Boolean = true,
    contentAlignment: Alignment = Alignment.Center,
    actionEnabled: Boolean = true,
    checked: Boolean? = null,
    reserveCheckSpace: Boolean = false,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    iconText: String? = null,
    showOutline: Boolean? = null,
) {
    var focused by remember { mutableStateOf(false) }
    var blockedPress by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val c = MaterialTheme.colorScheme
    val d = SeasonvarTheme.dimensions
    val outline = SeasonvarTheme.surfaceOutline
    val hasOutline = showOutline ?: if (icon == null) SeasonvarTheme.textButtonOutline else SeasonvarTheme.iconButtonOutline
    val action = { if (enabled && actionEnabled && !loading) onClick() }
    val loadingDescription = if (loading) loadingLabel ?: stringResource(R.string.today_updating) else null
    val buttonModifier = modifier
        .then(if (hasOutline && !focused && outline != Color.Transparent) Modifier.border(SeasonvarTheme.surfaceOutlineWidth,
            if (actionEnabled) outline else outline.copy(alpha = outline.alpha / 2), RoundedCornerShape(d.buttonCornerRadius)) else Modifier)
        .focusRequester(focus).focusProperties { canFocus = enabled }
        .onFocusChanged { focused = it.isFocused; if (!it.isFocused) blockedPress = false }
        .then(if (!actionEnabled || blockedPress) Modifier.onPreviewKeyEvent { event ->
            if (event.key !in setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar)) false
            else if (event.type == KeyEventType.KeyDown && (!actionEnabled || blockedPress)) {
                blockedPress = true
                true
            } else if (event.type == KeyEventType.KeyUp && blockedPress) {
                blockedPress = false
                true
            } else false
        } else Modifier)
        .then(if (!actionEnabled) Modifier.semantics { disabled() } else Modifier)
        .then(if (checked != null) Modifier.semantics { selected = checked } else Modifier)
        .pointerInput(action, enabled) { detectTapGestures { if (enabled) { focus.requestFocus(); action() } } }
        .then(if (loadingDescription != null) Modifier.semantics {
            stateDescription = loadingDescription
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        } else Modifier)
    val shape = ButtonDefaults.shape(shape = RoundedCornerShape(d.buttonCornerRadius))
    val scale = ButtonDefaults.scale(focusedScale = 1f)
    val colors = ButtonDefaults.colors(
        containerColor = when {
            focused -> c.primary
            !actionEnabled -> Color.Transparent
            icon != null -> Color.Transparent
            active -> c.surface
            else -> SeasonvarTheme.inactiveSurface
        },
        contentColor = if (focused) c.onPrimary else if (!actionEnabled) c.onSurfaceVariant else c.onSurface,
        focusedContainerColor = c.primary,
        focusedContentColor = c.onPrimary,
        disabledContainerColor = Color.Transparent,
        disabledContentColor = c.onSurfaceVariant,
    )
    if (icon != null) {
        IconButton(
            onClick = action, enabled = enabled,
            modifier = buttonModifier.size(d.headerButtonSize),
            shape = shape,
            scale = scale,
            colors = colors,
        ) {
            val textColor = SeasonvarTheme.buttonTextColor({ focused }, if (actionEnabled) c.onSurface else c.onSurfaceVariant)
            val textWeight = SeasonvarTheme.textWeight { focused }
            val rotation = if (loading) {
                val transition = rememberInfiniteTransition(label = "refresh")
                val angle = transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(
                        durationMillis = SeasonvarTheme.refreshRotationDurationMillis,
                        easing = LinearEasing,
                    )),
                    label = "refreshRotation",
                )
                Modifier.graphicsLayer { rotationZ = angle.value }
            } else Modifier
            if (avatarUrl != null) AsyncImage(
                model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(avatarUrl).diskCachePolicy(coil.request.CachePolicy.DISABLED)
                    .memoryCacheKey("account-avatar-$avatarKey").build(),
                contentDescription = text, contentScale = ContentScale.Crop,
                onSuccess = { if (BuildConfig.DEBUG) android.util.Log.d("SeasonvarAccount", "avatar=loaded") },
                onError = { if (BuildConfig.DEBUG) android.util.Log.d("SeasonvarAccount", "avatar=failed") },
                placeholder = painterResource(icon), error = painterResource(icon),
                modifier = Modifier.size(d.headerButtonIconSize).clip(RoundedCornerShape(d.buttonCornerRadius)))
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (iconText != null) Text(iconText, style = MaterialTheme.typography.labelLarge,
                    color = textColor, fontWeight = textWeight, maxLines = 1)
                else Icon(painterResource(icon), contentDescription = text,
                    modifier = Modifier.size(d.headerButtonIconSize).then(rotation))
                if (badgeCount > 0) Box(
                    Modifier.align(Alignment.TopEnd).padding(d.headerButtonBadgeInset)
                        .size(d.headerButtonBadgeSize)
                        .background(if (focused) c.onPrimary else c.primary, RoundedCornerShape(d.headerButtonBadgeSize))
                        .clearAndSetSemantics { },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(badgeCount.toString(), style = MaterialTheme.typography.labelSmall,
                        color = if (focused) c.primary else c.onPrimary, maxLines = 1)
                }
            }
        }
    } else {
        Button(onClick = action, enabled = enabled,
            modifier = buttonModifier.buttonContentSize(),
            shape = shape, scale = scale, colors = colors, contentPadding = contentPadding) {
            val textColor = SeasonvarTheme.buttonTextColor({ focused }, if (actionEnabled) c.onSurface else c.onSurfaceVariant)
            val textWeight = SeasonvarTheme.textWeight { focused }
            if (checked != null || reserveCheckSpace) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                Box(Modifier.size(d.headerButtonIconSize), contentAlignment = Alignment.Center) {
                    if (checked == true) Icon(painterResource(R.drawable.ic_check), contentDescription = null,
                        modifier = Modifier.size(d.headerButtonIconSize))
                }
                Text(text, Modifier.weight(1f), color = textColor, fontWeight = textWeight, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Box(Modifier.fillMaxSize(), contentAlignment = contentAlignment) {
                Text(text, color = textColor, fontWeight = textWeight, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private data class CatalogStrings(
    @get:StringRes val title: Int,
    @get:StringRes val loading: Int,
    @get:StringRes val empty: Int,
)

private fun catalogStrings(feed: PublicFeed): CatalogStrings = when (feed) {
    PublicFeed.TODAY -> CatalogStrings(R.string.today_title, R.string.today_loading, R.string.today_empty)
    PublicFeed.UPDATES -> CatalogStrings(R.string.updates_title, R.string.updates_loading, R.string.updates_empty)
    PublicFeed.TOP -> CatalogStrings(R.string.top_title, R.string.top_loading, R.string.top_empty)
    PublicFeed.NEWEST -> CatalogStrings(R.string.newest_title, R.string.newest_loading, R.string.newest_empty)
    PublicFeed.ANNOUNCEMENTS -> CatalogStrings(R.string.announcements_title, R.string.announcements_loading, R.string.announcements_empty)
    PublicFeed.BEST -> CatalogStrings(R.string.best_title, R.string.best_loading, R.string.best_empty)
}

@Composable
internal fun CatalogPane(
    state: CatalogUiState,
    selected: SeriesEntry?,
    listState: LazyListState,
    cards: MutableMap<String, FocusRequester>,
    feed: PublicFeed?,
    menuFocus: FocusRequester,
    refreshFocus: FocusRequester,
    retryFocus: FocusRequester,
    onRefresh: () -> Unit,
    onFocused: (SeriesEntry) -> Unit,
    onOpen: (SeriesEntry) -> Unit,
    query: String? = null,
    browsing: Boolean = false,
    inlineFilters: (@Composable () -> Unit)? = null,
    searchHeader: (@Composable () -> Unit)? = null,
    historyContent: (@Composable () -> Unit)? = null,
    smart: Boolean = false,
    personalFeed: PersonalFeed? = null,
    catalogKey: String = feed?.name ?: "SEARCH",
    onLoadMore: () -> Unit = {},
    onActions: (SeriesEntry) -> Unit = {},
    cardMetrics: CatalogCardMetrics,
    imagePrefetchEnabled: Boolean = true,
    allowAccountActions: Boolean = false,
    privateActionsEnabled: Boolean = true,
    layout: CatalogLayout = CatalogLayout.STRIP,
    gridState: LazyGridState = rememberLazyGridState(),
    layoutFocus: FocusRequester = remember { FocusRequester() },
    onLayoutChange: () -> Unit = {},
    onEnterContent: () -> Unit = {},
    filters: ru.seasonvar.tv.ui.filters.ListFiltersController? = null,
    historyClear: ru.seasonvar.tv.ui.account.HistoryClearController? = null,
    loadDescription: (suspend (SeriesEntry) -> ru.seasonvar.tv.data.SeriesDescription)? = null,
    preparationContext: Any? = null,
    resourceContext: Any? = null,
    cardData: ru.seasonvar.tv.data.cache.CardDataCache? = null,
    actorName: String? = null,
    onBackdropSource: PosterBackdropPublisher? = null,
) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val engine = ru.seasonvar.tv.ui.catalog.rememberSeriesListEngine(loadDescription, preparationContext, cardData,
        cardDataEnabled = privateActionsEnabled)
    val backdropPublisher = if (SeasonvarTheme.posterBackdropEnabled) onBackdropSource else null
    val theme = SeasonvarTheme.interfaceTheme
    if (BuildConfig.BUILD_TYPE == "profile") LaunchedEffect(catalogKey, layout, backdropPublisher != null, theme) {
        android.util.Log.i("SeasonvarCatalogProfile", "layout=" + layout.storageId + " backdrop=" + (backdropPublisher != null) + " theme=" + theme.storageId)
    }
    val clearState = historyClear?.state?.collectAsStateWithLifecycle()?.value
    val clearFocus = remember { FocusRequester() }
    var clearWasOpen by remember(historyClear) { mutableStateOf(false) }
    LaunchedEffect(clearState?.visible) {
        if (clearWasOpen && clearState?.visible == false) {
            repeat(2) { withFrameNanos { } }
            clearFocus.requestFocus()
        }
        clearWasOpen = clearState?.visible == true
    }
    val filterState = filters?.state?.collectAsStateWithLifecycle()?.value
    val filterFocus = remember { FocusRequester() }
    var filtersWereOpen by remember(filters) { mutableStateOf(false) }
    LaunchedEffect(filterState?.open) {
        if (filtersWereOpen && filterState?.open == false) {
            repeat(2) { withFrameNanos { } }
            filterFocus.requestFocus()
        }
        filtersWereOpen = filterState?.open == true
    }
    val isToday = feed == PublicFeed.TODAY
    val personal = personalFeed != null || smart
    val strings = remember(feed, personalFeed, smart, browsing, actorName) {
        if (actorName != null) CatalogStrings(R.string.actor_series_title, R.string.actor_series_loading, R.string.actor_series_empty)
        else if (browsing) CatalogStrings(R.string.browse_catalog_title, R.string.browse_catalog_loading, R.string.browse_catalog_empty)
        else if (smart) CatalogStrings(R.string.smart_continue_title, R.string.resume_loading, R.string.smart_continue_empty)
        else if (personalFeed != null) CatalogStrings(personalTitle(personalFeed), R.string.resume_loading, R.string.resume_empty)
        else if (feed == null) CatalogStrings(R.string.search_title, R.string.search_loading, R.string.search_empty)
        else catalogStrings(feed)
    }
    val latestLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(catalogKey, layout, listState, gridState, state.entries.size, state.hasMore, state.moreFailure) {
        if (state.hasMore && state.moreFailure == null) snapshotFlow {
            val last = if (layout == CatalogLayout.GRID) gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                else listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            (last ?: -1) >= state.entries.size - 5
        }.collect { nearEnd -> if (nearEnd) latestLoadMore() }
    }
    LaunchedEffect(catalogKey, layout, historyContent != null) {
        if (historyContent != null) return@LaunchedEffect
        val index = state.entries.indexOfFirst { it.key == selected?.key }
        if (index < 0) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        val cardKey = catalogKey + "|" + selected?.key
        if (layout == CatalogLayout.GRID) {
            if (gridState.layoutInfo.visibleItemsInfo.none { it.key == cardKey }) gridState.scrollToItem(index)
        } else if (listState.layoutInfo.visibleItemsInfo.none { it.key == cardKey }) listState.scrollToItem(index)
    }
    val date = remember(state.date) { state.date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) }
    ShortMessageEffect(if (selected == null) null else when {
        state.moreFailure != null -> stringResource(R.string.catalog_more_error)
        state.failure != null -> stringResource(R.string.catalog_refresh_error)
        else -> null
    })
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = d.headerButtonSize),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            val status = if (selected == null) null else when {
                state.cached && !state.loading -> stringResource(R.string.catalog_cached)
                else -> null
            }
            val count = if (historyContent == null && state.loaded) state.entries.size else null
            val metadata = listOf(catalogHeaderMetadata(count, state.hasMore, if (isToday) date else null, status),
                (listOf(filterState?.summary.orEmpty()) + filterState?.activeFields.orEmpty()
                    .filter { it.kind == ru.seasonvar.tv.ui.filters.ListFilterKind.TOGGLE }
                    .map { stringResource(it.activeTitle ?: it.title) }).filter { it.isNotEmpty() }.joinToString(" · "))
                .filter { it.isNotEmpty() }.joinToString(" · ")
            Row(Modifier.weight(1f).padding(end = d.spacingSmall), verticalAlignment = Alignment.CenterVertically) {
                if (searchHeader != null) {
                    Box(Modifier.weight(1f)) { searchHeader() }
                    if (metadata.isNotEmpty()) Text(metadata, style = MaterialTheme.typography.bodySmall,
                        color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = d.menuWidth))
                } else CatalogHeading(if (actorName != null) stringResource(R.string.actor_series_heading, actorName)
                    else stringResource(strings.title), metadata, Modifier.fillMaxWidth())
            }
            if (historyContent == null) Row(horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                val enterCards = Modifier.onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown && selected != null) {
                        onEnterContent(); true
                    } else false
                }
                val layoutLabel = stringResource(if (layout == CatalogLayout.GRID) R.string.catalog_layout_grid else R.string.catalog_layout_strip)
                if (filters != null && filterState != null) ThemeButton(
                    stringResource(if (filterState.activeCount == 0) R.string.filters else R.string.filters_active, filterState.activeCount),
                    filters::open, Modifier.focusRequester(filterFocus)
                        .focusProperties { left = menuFocus; right = layoutFocus }.then(enterCards),
                    icon = R.drawable.ic_filter, badgeCount = filterState.activeCount, actionEnabled = privateActionsEnabled,
                )
                if (historyClear != null) ThemeButton(stringResource(R.string.history_clear_action), historyClear::ask,
                    Modifier.focusRequester(clearFocus).focusProperties { left = menuFocus; right = layoutFocus }.then(enterCards),
                    icon = R.drawable.ic_delete,
                    actionEnabled = privateActionsEnabled && state.loaded && !state.loading && state.entries.isNotEmpty() && clearState?.busy != true)
                if (!browsing) ThemeButton(
                    stringResource(if (layout == CatalogLayout.STRIP) R.string.catalog_show_grid else R.string.catalog_show_strip),
                    onLayoutChange,
                    Modifier.focusRequester(layoutFocus).focusProperties { left = if (historyClear != null) clearFocus else if (filters != null) filterFocus else menuFocus; right = refreshFocus }
                        .semantics { stateDescription = layoutLabel }.then(enterCards),
                    icon = if (layout == CatalogLayout.GRID) R.drawable.ic_catalog_grid else R.drawable.ic_catalog_strip,
                )
                ThemeButton(
                    stringResource(if (state.moreFailure != null) R.string.retry else R.string.refresh), onRefresh,
                    Modifier.focusRequester(refreshFocus).focusProperties { left = layoutFocus; right = FocusRequester.Cancel }.then(enterCards),
                    icon = R.drawable.ic_reload,
                    loading = state.loading || state.loadingMore,
                )
            }
        }
        Spacer(Modifier.height(d.spacingSmall))
        if (inlineFilters != null) { inlineFilters(); Spacer(Modifier.height(d.spacingSmall)) }
        if (historyContent != null) Box(Modifier.weight(1f).fillMaxWidth()) { historyContent() }
        else if (selected == null) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
            ) {
                val message = when {
                    query != null && query.isBlank() -> R.string.search_hint
                    state.loading && !state.loaded -> strings.loading
                    state.failure == LoadFailure.MARKUP -> if (actorName != null) R.string.actor_series_error else if (browsing) R.string.browse_catalog_error else if (personal) R.string.resume_error else if (feed == null) R.string.search_error else R.string.today_markup_error
                    state.failure == LoadFailure.HTTP -> R.string.today_http_error
                    state.failure != null -> R.string.today_network_error
                    else -> if ((filterState?.activeCount ?: 0) > 0) R.string.filters_empty else strings.empty
                }
                Text(stringResource(message), style = MaterialTheme.typography.titleLarge,
                    color = c.onBackground)
                Spacer(Modifier.height(d.spacingMedium))
                Text(
                    stringResource(if (state.loading && !state.loaded) R.string.today_loading_hint
                        else if (state.failure != null) R.string.today_error_hint
                        else if ((filterState?.activeCount ?: 0) > 0) R.string.filters_empty_hint
                        else if (actorName != null) R.string.actor_series_empty_hint
                        else if (smart) R.string.smart_continue_empty_hint
                        else if (personalFeed == PersonalFeed.HISTORY) R.string.history_empty_hint
                        else if (personalFeed == PersonalFeed.RESUME) R.string.resume_empty_hint
                        else if (personal) R.string.personal_empty_hint else if (browsing) R.string.browse_catalog_hint else if (feed == null) {
                            if (query.isNullOrBlank()) R.string.search_input_hint else R.string.search_empty_hint
                        } else R.string.today_empty_hint),
                    style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant,
                )
                if ((state.loaded || !state.loading) && (feed != null || state.failure != null)) {
                    Spacer(Modifier.height(d.spacingLarge))
                    ThemeButton(
                        stringResource(R.string.retry), onRefresh,
                        Modifier.width(d.buttonWidth).height(d.navigationButtonHeight)
                            .focusRequester(retryFocus),
                        loading = state.loading,
                    )
                }
            }
        } else {
            if (layout == CatalogLayout.STRIP)
                SelectedPanel(selected, { if (privateActionsEnabled) engine.description(selected) else DescriptionState(key = selected.key) },
                    Modifier.weight(1f).padding(bottom = d.spacingMedium))
            ru.seasonvar.tv.ui.catalog.SeriesList(state.entries, catalogKey, selected.key, layout,
                listState, gridState, cards, layoutFocus, menuFocus, cardMetrics, imagePrefetchEnabled,
                if (layout == CatalogLayout.GRID) Modifier.fillMaxWidth().weight(1f)
                else Modifier.fillMaxWidth().height(cardMetrics.rowHeight),
                onFocused, onOpen, if (allowAccountActions) onActions else null, engine = engine,
                onBackdropSource = backdropPublisher, descriptionsEnabled = privateActionsEnabled, resourceContext = resourceContext)
        }
    }
    if (filters != null && filterState != null) ru.seasonvar.tv.ui.filters.ListFilterPanel(stringResource(strings.title), filterState, filters)
}

@Composable
internal fun SelectedPanel(entry: SeriesEntry, description: () -> DescriptionState, modifier: Modifier) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    ru.seasonvar.tv.ui.catalog.SelectedPanelLayout(modifier.fillMaxWidth(), text = {
        Column {
            Text(entry.fullTitle, style = MaterialTheme.typography.headlineSmall, color = c.onBackground,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(d.spacingSmall))
            if (entry.subtitle.isNotBlank()) Text(entry.subtitle, style = MaterialTheme.typography.bodyMedium,
                color = c.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            AccountEntryDetails(entry)
            entry.season?.let {
                Text(stringResource(R.string.season_number, it), color = c.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(d.spacingSmall))
            SelectedDescription(entry, description)
        }
    }, poster = { Poster(entry.id, entry.posterUrl, entry.isNew, entry.isWatching, Modifier, large = true) })
}

@Composable
private fun SelectedDescription(entry: SeriesEntry, description: () -> DescriptionState) {
    val observer = ru.seasonvar.tv.ui.catalog.SelectedPanelDiagnostics.current()
    val state = description()
    val start = if (observer != null) android.os.Debug.threadCpuTimeNanos() else 0L
    val text = when {
        state.key != entry.key || state.loading -> entry.previewDescription ?: stringResource(R.string.description_loading)
        state.failed -> entry.previewDescription ?: stringResource(R.string.description_error)
        state.description?.paragraphs.isNullOrEmpty() -> entry.previewDescription ?: stringResource(R.string.description_empty)
        else -> state.description.paragraphs.joinToString("\n\n")
    }
    observer?.textPrepared(android.os.Debug.threadCpuTimeNanos() - start)
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 4, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun SeriesThumbnail(
    entry: SeriesEntry,
    requester: FocusRequester,
    up: FocusRequester,
    metrics: CatalogCardMetrics,
    left: FocusRequester?,
    right: FocusRequester,
    content: () -> ru.seasonvar.tv.ui.catalog.SeriesCardAppearance?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onPosterLoaded: ((Bitmap) -> Unit)? = null,
) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val activation = remember { CardActivation() }
    var holdJob by remember { mutableStateOf<Job?>(null) }
    val latestLongClick by rememberUpdatedState(onLongClick)
    val latestClick by rememberUpdatedState(onClick)
    DisposableEffect(Unit) { onDispose { holdJob?.cancel(); activation.cancel() } }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(d.cardCornerRadius)
    val outline = if (SeasonvarTheme.cardOutline) SeasonvarTheme.surfaceOutline else Color.Transparent
    val borderWidth = if (!focused && outline != Color.Transparent) SeasonvarTheme.surfaceOutlineWidth else d.focusBorderWidth
    val navigationObserver = ru.seasonvar.tv.ui.catalog.CatalogNavigationDiagnostics.current()
    val drawsFocus = focused
    Column(
        modifier.then(if (navigationObserver == null) Modifier else Modifier.drawWithContent {
            drawContent()
            if (drawsFocus) navigationObserver.drawn(entry.key, System.nanoTime())
        }).size(metrics.width, metrics.height)
            .focusRequester(requester)
            .focusProperties {
                this.up = up
                this.right = right
                if (left != null) this.left = left
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    ru.seasonvar.tv.ui.catalog.CatalogNavigationDiagnostics.current()?.focused(entry.key, entry.title, System.nanoTime())
                    onFocused()
                } else { holdJob?.cancel(); activation.cancel() }
            }
            .onPreviewKeyEvent { event ->
                if (onLongClick == null || event.nativeKeyEvent.keyCode !in setOf(
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER,
                        android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)) false
                else {
                    if (event.type == KeyEventType.KeyDown && activation.press()) {
                        holdJob = scope.launch {
                            delay(android.view.ViewConfiguration.getLongPressTimeout().toLong())
                            if (activation.hold()) latestLongClick?.invoke()
                        }
                    } else if (event.type == KeyEventType.KeyUp) {
                        holdJob?.cancel()
                        if (event.nativeKeyEvent.isCanceled) activation.cancel()
                        else if (activation.release()) latestClick()
                    }
                    true
                }
            }
            .border(borderWidth, if (focused) c.primary else outline, shape)
            .background(c.surface, shape)
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) stringResource(R.string.mark_actions_title) else null)
            .padding(d.cardPadding),
    ) {
        SeriesThumbnailBody(entry.key, content, metrics, { focused }, onPosterLoaded)
    }
}

@Composable
private fun SeriesThumbnailBody(key: String, content: () -> ru.seasonvar.tv.ui.catalog.SeriesCardAppearance?,
    metrics: CatalogCardMetrics, focused: () -> Boolean, onPosterLoaded: ((Bitmap) -> Unit)?) {
    val prepared = content()
    if (BuildConfig.DEBUG) {
        val observer = ru.seasonvar.tv.ui.catalog.LocalThumbnailObserver.current
        if (observer != null) SideEffect { observer(key, prepared != null) }
    }
    if (prepared == null) return
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    Poster(prepared.seasonId, prepared.posterUrl, prepared.isNew, prepared.isWatching,
        Modifier.fillMaxWidth().height(metrics.posterHeight), onBitmap = onPosterLoaded)
    Spacer(Modifier.height(d.spacingSmall))
    Text(prepared.title, modifier = Modifier.height(metrics.titleHeight),
        style = MaterialTheme.typography.labelMedium, color = SeasonvarTheme.textColor(focused, c.onSurface),
        minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = SeasonvarTheme.textWeight(focused))
    Text(prepared.subtitle, modifier = Modifier.height(metrics.subtitleHeight),
        style = MaterialTheme.typography.labelSmall, color = SeasonvarTheme.textColor(focused, c.onSurface),
        minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = SeasonvarTheme.textWeight(focused))
}

@Composable
private fun Poster(seasonId: String, posterUrl: String, isNew: Boolean, isWatching: Boolean, modifier: Modifier, large: Boolean = false,
    onBitmap: ((Bitmap) -> Unit)? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val largeUrl = if (large) ru.seasonvar.tv.data.PosterImages.largeUrl(posterUrl) else null
    var fallback by remember(posterUrl, largeUrl) { mutableStateOf(false) }
    val source = if (!fallback && largeUrl != null) largeUrl else posterUrl
    val request = remember(context, source) {
        coil.request.ImageRequest.Builder(context).data(source)
            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
            .networkCachePolicy(coil.request.CachePolicy.ENABLED)
            .build()
    }
    val placeholder = painterResource(R.drawable.poster_placeholder)
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    Box(modifier.clip(RoundedCornerShape(
        topStart = d.cardCornerRadius, topEnd = d.cardCornerRadius))) {
        AsyncImage(
            model = request,
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = if (large) ContentScale.Inside else ContentScale.Crop,
            placeholder = placeholder, error = placeholder,
            onSuccess = { state ->
                val width = state.result.drawable.intrinsicWidth
                val height = state.result.drawable.intrinsicHeight
                (state.result.drawable as? BitmapDrawable)?.bitmap?.let { onBitmap?.invoke(it) }
                if (BuildConfig.DEBUG && large) android.util.Log.d("SeasonvarPoster",
                    "source=" + state.result.dataSource + " decoded=" + width + "x" + height)
            },
            onError = { if (largeUrl != null && source == largeUrl && largeUrl != posterUrl) fallback = true },
        )
        if (isNew) {
            Box(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(d.badgeHeight)
                    .background(c.surface)
                    .padding(horizontal = d.badgePaddingHorizontal),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.series_new_badge),
                    modifier = Modifier.offset(x = d.badgeTextOffsetHorizontal, y = d.badgeTextOffsetVertical),
                    color = c.onSurface,
                    style = MaterialTheme.typography.labelMedium.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both,
                        ),
                    ),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        val mark = ru.seasonvar.tv.ui.account.cardMarkState(seasonId)
        val watching = mark?.let { it.status == ru.seasonvar.tv.data.account.AccountListStatus.WATCHING } ?: isWatching
        if (watching) {
            Box(
                modifier = Modifier.align(Alignment.TopStart)
                    .padding(start = d.watchBadgeInset,
                        top = if (isNew) d.badgeHeight + d.watchBadgeInset else d.watchBadgeInset)
                    .size(d.watchBadgeSize)
                    .background(c.surface, RoundedCornerShape(d.buttonCornerRadius)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_watching),
                    contentDescription = stringResource(R.string.series_watching_badge),
                    tint = c.onSurface, modifier = Modifier.size(d.watchBadgeIconSize))
            }
        }

    }
}

@Composable
internal fun <T> SettingsChoiceDialog(
    @StringRes title: Int,
    options: List<T>,
    selected: T,
    label: (T) -> Int,
    onSelect: (T) -> Unit,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onInteraction: () -> Unit,
    textLabel: ((T) -> String)? = null,
) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val optionFocus = remember(options) { options.associateWith { FocusRequester() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onBack)
        Column(
            Modifier.width(d.dialogWidth).heightIn(max = d.dialogHeight)
                .backNavigationKeys(onBack, onInteraction)
                .background(c.background, RoundedCornerShape(d.cardCornerRadius))
                .padding(d.spacingLarge),
            verticalArrangement = Arrangement.spacedBy(d.spacingMedium),
        ) {
            Text(stringResource(title), color = c.onBackground,
                style = MaterialTheme.typography.headlineMedium)
            Column(
                Modifier.fillMaxWidth().weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(d.spacingMedium),
            ) {
                options.forEach { provider ->
                    key(provider) {
                        var focused by remember { mutableStateOf(false) }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = d.navigationButtonHeight)
                                .focusRequester(optionFocus.getValue(provider))
                                .onFocusChanged { focused = it.isFocused }
                                .border(d.focusBorderWidth, if (focused) c.primary else Color.Transparent,
                                    RoundedCornerShape(d.cardCornerRadius))
                                .selectable(selected = selected == provider, role = Role.RadioButton,
                                    onClick = { onInteraction(); onSelect(provider) })
                                .padding(d.spacingMedium),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(textLabel?.invoke(provider) ?: stringResource(label(provider)), color = SeasonvarTheme.textColor({ focused }, c.onBackground),
                                style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), fontWeight = SeasonvarTheme.textWeight { focused })
                            Spacer(Modifier.width(d.spacingMedium))
                            RadioButton(selected = selected == provider, onClick = null)
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { optionFocus.getValue(selected).requestFocus() }
}

@Preview(device = "spec:width=960dp,height=540dp,dpi=320", showBackground = true)
@Composable
private fun CatalogPreview() {
    SeasonvarApp(
        PublicFeed.entries.associateWith { CatalogUiState() },
        {},
    )
}
