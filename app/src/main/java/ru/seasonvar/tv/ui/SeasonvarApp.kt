package ru.seasonvar.tv.ui

import android.content.ActivityNotFoundException
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.semantics.heading
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
import ru.seasonvar.tv.ui.player.PlayerClockPosition
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.PublicFeed
import ru.seasonvar.tv.data.network.DnsProvider
import ru.seasonvar.tv.ui.details.SeriesDetailsPane
import ru.seasonvar.tv.ui.details.SeriesDetailsViewModel
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import ru.seasonvar.tv.ui.navigation.Section
import ru.seasonvar.tv.ui.navigation.MenuGroup
import ru.seasonvar.tv.ui.navigation.BackNavigation
import ru.seasonvar.tv.ui.navigation.backNavigationKeys
import ru.seasonvar.tv.ui.catalog.DescriptionState
import ru.seasonvar.tv.ui.catalog.LoadFailure
import ru.seasonvar.tv.ui.catalog.CatalogUiState
import ru.seasonvar.tv.data.account.PersonalFeed
import ru.seasonvar.tv.ui.search.SearchUiState
import ru.seasonvar.tv.ui.search.SearchControls
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
    onFeedVisible: (PublicFeed) -> Unit = {},
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
    imagePrefetchEnabled: Boolean = true,
    onImagePrefetchChange: (Boolean) -> Unit = {},
    autoNextSeasonEnabled: Boolean = true,
    onAutoNextSeasonChange: (Boolean) -> Unit = {},
    autoNextEnabled: Boolean = true,
    onAutoNextChange: (Boolean) -> Unit = {},
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
    SeasonvarTheme {
        SeasonvarContent(
            states = states,
            onRefresh = onRefresh,
            loadDescription = loadDescription,
            onFeedVisible = onFeedVisible,
            searchState = searchState,
            onSearchQuery = onSearchQuery,
            onSearchSubmit = onSearchSubmit,
            onSearchRecall = onSearchRecall, onSearchClearHistory = onSearchClearHistory, onSearchRemember = onSearchRemember,
            onSearchVisible = onSearchVisible,
            bypassEnabled = bypassEnabled,
            onBypassChange = onBypassChange,
            dnsProvider = dnsProvider,
            onDnsChange = onDnsChange,
            imagePrefetchEnabled = imagePrefetchEnabled, onImagePrefetchChange = onImagePrefetchChange,
            catalogLayouts = catalogLayouts, onCatalogLayoutChange = onCatalogLayoutChange,
            startPage = startPage, onStartPageChange = onStartPageChange,
            hiddenSections = hiddenSections, onSectionVisibilityChange = onSectionVisibilityChange,
            autoNextEnabled = autoNextEnabled, onAutoNextChange = onAutoNextChange,
            autoNextSeasonEnabled = autoNextSeasonEnabled, onAutoNextSeasonChange = onAutoNextSeasonChange,
            playerClockPosition = playerClockPosition, onPlayerClockPositionChange = onPlayerClockPositionChange,
            onExit = onExit,
            updateModel = updateModel, translationSettingsModel = translationSettingsModel,
            detailsModel = detailsModel,
            onPlay = onPlay,
            removalState = removalState, onRemovalActions = onRemovalActions,
            onRemovalRefresh = onRemovalRefresh, onRemovalSetStatus = onRemovalSetStatus,
            onRemovalAsk = onRemovalAsk, onRemovalDismiss = onRemovalDismiss, onRemovalConfirm = onRemovalConfirm,
            accountSession = accountSession, accountState = accountState,
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
    onFeedVisible: (PublicFeed) -> Unit = {},
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
    imagePrefetchEnabled: Boolean = true,
    onImagePrefetchChange: (Boolean) -> Unit = {},
    autoNextSeasonEnabled: Boolean = true,
    onAutoNextSeasonChange: (Boolean) -> Unit = {},
    autoNextEnabled: Boolean = true,
    onAutoNextChange: (Boolean) -> Unit = {},
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
    val visibleMenuGroups = remember(accountSession.user != null, hiddenSections) {
        Section.menuGroups(accountSession.user != null, hiddenSections)
    }
    val visibleMenuPages = remember(visibleMenuGroups) { visibleMenuGroups.flatMap { it.second } }
    var startupPending by rememberSaveable { mutableStateOf(true) }
    var menuOwnsInput by remember { mutableStateOf(true) }
    var section by rememberSaveable { mutableStateOf(initialStartPage.availableAtStart(accountSession.user != null, hiddenSections)) }
    val catalogLayout = catalogLayouts[section] ?: CatalogLayout.STRIP
    var activeFeed by rememberSaveable { mutableStateOf(section.publicFeed ?: PublicFeed.TODAY) }
    var selectedKeys by rememberSaveable { mutableStateOf(mapOf<String, String>()) }
    val smart = section == Section.SMART_CONTINUE
    val searching = section == Section.SEARCH
    val personalFeed = section.personalFeed
    val catalogKey = if (searching) "SEARCH" else if (smart) "SMART_CONTINUE" else if (personalFeed != null) personalFeed.catalogKey else activeFeed.name
    val state = if (searching) searchState.results else if (smart) smartState else if (personalFeed != null) personalStates[personalFeed] ?: CatalogUiState(loading = false) else states.getValue(activeFeed)
    val selectedKey = selectedKeys[catalogKey]
    var searchEditRequest by remember { mutableIntStateOf(0) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    var detailsSource by remember { mutableStateOf<SeriesEntry?>(null) }
    var removalOrigin by remember { mutableStateOf<RemovalFocusOrigin?>(null) }
    var removalWasVisible by remember { mutableStateOf(false) }
    val todayListState = rememberLazyListState()
    val updatesListState = rememberLazyListState()
    val topListState = rememberLazyListState()
    val newestListState = rememberLazyListState()
    val bestListState = rememberLazyListState()
    val announcementsListState = rememberLazyListState()
    val searchListState = rememberLazyListState()
    val smartListState = rememberLazyListState()
    val personalListStates = PersonalFeed.entries.associateWith { rememberLazyListState() }
    val listState = if (searching) searchListState else if (smart) smartListState else if (personalFeed != null) personalListStates.getValue(personalFeed) else when (activeFeed) {
        PublicFeed.TODAY -> todayListState
        PublicFeed.UPDATES -> updatesListState
        PublicFeed.TOP -> topListState
        PublicFeed.NEWEST -> newestListState
        PublicFeed.BEST -> bestListState
        PublicFeed.ANNOUNCEMENTS -> announcementsListState
    }
    val publicGridStates = PublicFeed.entries.associateWith { rememberLazyGridState() }
    val personalGridStates = PersonalFeed.entries.associateWith { rememberLazyGridState() }
    val searchGridState = rememberLazyGridState()
    val smartGridState = rememberLazyGridState()
    val gridState = if (searching) searchGridState else if (smart) smartGridState else if (personalFeed != null) personalGridStates.getValue(personalFeed)
        else publicGridStates.getValue(activeFeed)
    val layoutFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val smartFocus = remember { FocusRequester() }
    val accountFocus = remember { FocusRequester() }
    val personalFocus = remember { PersonalFeed.entries.associateWith { FocusRequester() } }
    val menuBringIntoView = remember { Section.entries.associateWith { BringIntoViewRequester() } }
    val searchInputFocus = remember { FocusRequester() }
    val searchHistoryFocus = remember { FocusRequester() }
    var pendingHistoryQuery by remember { mutableStateOf<String?>(null) }
    val todayFocus = remember { FocusRequester() }
    val updatesFocus = remember { FocusRequester() }
    val topFocus = remember { FocusRequester() }
    val newestFocus = remember { FocusRequester() }
    val bestFocus = remember { FocusRequester() }
    val announcementsFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val settingsContentFocus = remember { FocusRequester() }
    val aboutFocus = remember { FocusRequester() }
    val aboutContentFocus = remember { FocusRequester() }
    val refreshFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val cards = remember { mutableMapOf<String, FocusRequester>() }
    val scope = rememberCoroutineScope()
    val backNavigation = remember { BackNavigation() }
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
        selectedKeys = if (key == null) selectedKeys - catalogKey
            else selectedKeys + (catalogKey to key)
    }

    fun selectSection(item: Section) {
        pendingHistoryQuery = null
        detailsOpen = false
        section = item
        val feed = item.publicFeed
        if (feed != null) {
            activeFeed = feed
            onFeedVisible(feed)
        }
    }

    fun sectionFocus(item: Section): FocusRequester = when (item) {
        Section.SEARCH -> searchFocus
        Section.SMART_CONTINUE -> smartFocus
        Section.ACCOUNT -> accountFocus
        Section.RESUME, Section.PERSONAL_UPDATES, Section.PERSONAL_NO_UPDATES, Section.WANT, Section.WATCHED, Section.HISTORY ->
            personalFocus.getValue(requireNotNull(item.personalFeed))
        Section.TODAY -> todayFocus
        Section.UPDATES -> updatesFocus
        Section.TOP -> topFocus
        Section.NEWEST -> newestFocus
        Section.BEST -> bestFocus
        Section.ANNOUNCEMENTS -> announcementsFocus
        Section.SETTINGS -> settingsFocus
        Section.ABOUT -> aboutFocus
    }

    DisposableEffect(smart) {
        onSmartVisible(smart)
        onDispose { onSmartVisible(false) }
    }
    DisposableEffect(personalFeed) {
        onPersonalVisible(personalFeed)
        onDispose { onPersonalVisible(null) }
    }
    DisposableEffect(searching) {
        onSearchVisible(searching)
        onDispose { if (searching) onSearchVisible(false) }
    }
    LaunchedEffect(searchState.query) {
        selectedKeys = selectedKeys - "SEARCH"
        searchListState.scrollToItem(0)
        searchGridState.scrollToItem(0)
    }
    LaunchedEffect(catalogKey, state.entries) {
        if (state.entries.none { it.key == selectedKey }) {
            val origin = removalOrigin?.takeIf { it.catalogKey == catalogKey && it.selected == selectedKey }
            selectEntry(if (origin != null) removalNeighbor(origin.keys, state.entries.map { it.key }, origin.selected) else selected?.key)
        }
    }
    val startupTarget = initialStartPage.atStartup(accountSession.user != null, accountState.busy, hiddenSections)
    LaunchedEffect(startupTarget) {
        if (!startupPending) return@LaunchedEffect
        val target = startupTarget ?: Section.TODAY.availableAtStart(accountSession.user != null, hiddenSections)
        if (startupTarget != null) selectSection(target)
        repeat(2) { withFrameNanos { } }
        if (startupPending && section == target) {
            if (startupTarget != null) startupPending = false
            sectionFocus(target).requestFocus()
        }
    }
    LaunchedEffect(Unit) {
        if (section.canHide && section in hiddenSections) {
            selectSection(section.availableAtStart(accountSession.user != null, hiddenSections))
        }
        if (!startupPending) {
            repeat(2) { withFrameNanos { } }
            sectionFocus(section).requestFocus()
        }
    }
    LaunchedEffect(accountSession.version) {
        val menuTarget = section.takeIf { menuOwnsInput }
        val personalKeys = PersonalFeed.entries.map { it.catalogKey }.toSet() + "SMART_CONTINUE"
        selectedKeys = selectedKeys - personalKeys
        removalOrigin = null
        cards.keys.removeAll { it.substringBefore('|') in personalKeys }
        personalListStates.values.forEach { it.requestScrollToItem(0) }
        personalGridStates.values.forEach { it.requestScrollToItem(0) }
        smartListState.requestScrollToItem(0)
        smartGridState.requestScrollToItem(0)
        detailsOpen = false
        if (section.requiresAccount && accountSession.user == null) section = Section.ACCOUNT
        if (section == Section.ACCOUNT && accountSession.user != null) {
            val target = Section.RESUME.availableAtStart(true, hiddenSections)
            selectSection(target)
            repeat(2) { withFrameNanos { } }; sectionFocus(target).requestFocus()
        }
        if (menuTarget != null) {
            repeat(2) { withFrameNanos { } }
            if (menuOwnsInput && section == menuTarget) {
                sectionFocus(menuTarget).requestFocus()
                if (menuTarget in visibleMenuPages) menuBringIntoView.getValue(menuTarget).bringIntoView()
            }
        }
    }

    fun enterContent() {
        val requestedSection = section
        scope.launch {
            repeat(2) { withFrameNanos { } }
            if (section != requestedSection) return@launch
            val selected = latestSelected
            val state = latestState
            val listState = latestListState
            if (section == Section.SEARCH && latestSearchState.showHistory) {
                if (!latestSearchState.historyLoading) {
                    if (latestSearchState.recentQueries.isEmpty()) searchInputFocus.requestFocus() else searchHistoryFocus.requestFocus()
                }
            } else if (section == Section.ACCOUNT) {
                accountFocus.requestFocus()
            } else if (section == Section.SETTINGS) {
                settingsContentFocus.requestFocus()
            } else if (section == Section.ABOUT) {
                aboutContentFocus.requestFocus()
            } else if (selected == null) {
                if (searching) searchInputFocus.requestFocus()
                else if (state.failure != null) retryFocus.requestFocus() else refreshFocus.requestFocus()
            } else {
                inputModeManager.requestInputMode(InputMode.Keyboard)
                val index = state.entries.indexOfFirst { it.key == selected.key }
                val cardKey = catalogKey + "|" + selected.key
                val targetLayout = latestCatalogLayout
                if (targetLayout == CatalogLayout.GRID) {
                    val grid = latestGridState
                    if (grid.layoutInfo.visibleItemsInfo.none { it.key == cardKey }) {
                        grid.scrollToItem(index.coerceAtLeast(0))
                    }
                } else if (listState.layoutInfo.visibleItemsInfo.none { it.key == cardKey }) {
                    listState.scrollToItem(index.coerceAtLeast(0))
                }
                repeat(2) { withFrameNanos { } }
                if (section == requestedSection && !detailsOpen && latestCatalogLayout == targetLayout && latestSelected?.key == selected.key) {
                    cards[cardKey]?.requestFocus()
                }
            }
        }
    }

    LaunchedEffect(pendingHistoryQuery, searchState.showHistory, searchState.query, searchState.results.loading, searchState.results.loaded, searchState.results.failure) {
        if (!searchState.showHistory && pendingHistoryQuery == searchState.query && !searchState.results.loading &&
            (searchState.results.loaded || searchState.results.failure != null)) {
            pendingHistoryQuery = null
            keyboard?.hide()
            if (searchState.results.entries.isNotEmpty()) enterContent() else refreshFocus.requestFocus()
        }
    }
    LaunchedEffect(searching, searchState.showHistory, searchState.focusHistoryOnShow) {
        if (!searching) pendingHistoryQuery = null
        else if (searchState.showHistory && searchState.focusHistoryOnShow) keyboard?.hide()
    }

    fun returnToMenu() {
        val requestedSection = section
        detailsOpen = false
        scope.launch {
            repeat(2) { withFrameNanos { } }
            if (section != requestedSection) return@launch
            when (section) {
                Section.SMART_CONTINUE -> smartFocus.requestFocus()
                Section.SEARCH -> searchFocus.requestFocus()
                Section.ACCOUNT -> accountFocus.requestFocus()
                Section.RESUME, Section.PERSONAL_UPDATES, Section.PERSONAL_NO_UPDATES, Section.WANT, Section.WATCHED, Section.HISTORY ->
                    personalFocus.getValue(requireNotNull(section.personalFeed)).requestFocus()
                Section.TODAY -> todayFocus.requestFocus()
                Section.UPDATES -> updatesFocus.requestFocus()
                Section.TOP -> topFocus.requestFocus()
                Section.NEWEST -> newestFocus.requestFocus()
                Section.BEST -> bestFocus.requestFocus()
                Section.ANNOUNCEMENTS -> announcementsFocus.requestFocus()
                Section.SETTINGS -> settingsFocus.requestFocus()
                Section.ABOUT -> aboutFocus.requestFocus()
            }
        }
    }

    fun closeDetails() {
        detailsOpen = false
        enterContent()
    }

    fun openSeriesActions(entry: SeriesEntry) {
        backNavigation.reset()
        removalOrigin = RemovalFocusOrigin(catalogKey, state.entries.map { it.key }, if (detailsOpen) selectedKey ?: entry.key else entry.key)
        onRemovalActions(entry)
    }

    fun openDetails(entry: SeriesEntry) {
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
        val exit = backNavigation.shouldExit(SystemClock.uptimeMillis())
        if (BuildConfig.DEBUG) android.util.Log.d("SeasonvarInput", "back section=" + section + " exit=" + exit)
        if (exit) onExit()
        else if (detailsOpen) closeDetails()
        else returnToMenu()
    }

    BackHandler { navigateBack() }
    val d = SeasonvarTheme.dimensions
    Row(
        Modifier.fillMaxSize()
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode !in setOf(
                        android.view.KeyEvent.KEYCODE_WAKEUP, android.view.KeyEvent.KEYCODE_SLEEP, android.view.KeyEvent.KEYCODE_POWER)) {
                    startupPending = false
                }
                false
            }
            .backNavigationKeys(::navigateBack, backNavigation::reset)
            .background(MaterialTheme.colorScheme.background)
            .padding(d.screenPaddingHorizontal, d.screenPaddingVertical),
        horizontalArrangement = Arrangement.spacedBy(d.contentGap),
    ) {
        Column(Modifier.width(d.menuWidth).fillMaxHeight()
            .onFocusChanged { if (it.hasFocus) menuOwnsInput = true }.focusGroup()) {
            Row(horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                ThemeButton(stringResource(R.string.search_title), onClick = {
                    startupPending = false; pendingHistoryQuery = null; backNavigation.reset(); detailsOpen = false; section = Section.SEARCH; searchEditRequest++
                }, modifier = Modifier.focusRequester(searchFocus).focusProperties { down = sectionFocus(visibleMenuPages.first()); right = accountFocus },
                    icon = R.drawable.ic_search)
                ThemeButton(stringResource(if (accountSession.user == null) R.string.account_sign_in else R.string.account_title),
                    onClick = { startupPending = false; backNavigation.reset(); selectSection(Section.ACCOUNT) },
                    modifier = Modifier.focusRequester(accountFocus).focusProperties { left = searchFocus; right = FocusRequester.Cancel; down = sectionFocus(visibleMenuPages.first()) },
                    icon = R.drawable.ic_person, avatarUrl = accountSession.user?.avatarUrl, avatarKey = accountSession.version)
            }
            Spacer(Modifier.height(d.menuHeaderSpacing))
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(d.menuItemSpacing),
            ) {
                visibleMenuGroups.forEachIndexed { index, (group, sections) ->
                    key(group) {
                        Text(
                            stringResource(menuGroupTitle(group)),
                            modifier = Modifier.fillMaxWidth()
                                .then(if (index > 0) Modifier.padding(top = d.spacingSmall) else Modifier)
                                .padding(horizontal = d.spacingSmall, vertical = d.menuItemSpacing)
                                .semantics { heading() },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        sections.forEach { item ->
                            key(item) {
                                val focus = sectionFocus(item)
                                val label = sectionTitle(item)
                                ThemeButton(
                                    stringResource(label),
                                    onClick = { startupPending = false; backNavigation.reset(); selectSection(item); enterContent() },
                                    modifier = Modifier.fillMaxWidth().height(d.menuItemHeight)
                                        .bringIntoViewRequester(menuBringIntoView.getValue(item))
                                        .focusRequester(focus).focusProperties { if (item == visibleMenuPages.first()) up = searchFocus }
                                        .onFocusChanged {
                                            if (it.isFocused) selectSection(item)
                                        }
                                        .onPreviewKeyEvent {
                                            if (it.key == Key.DirectionRight && it.type == KeyEventType.KeyDown) {
                                                selectSection(item)
                                                enterContent()
                                                true
                                            } else false
                                        },
                                    active = section == item,
                                    contentAlignment = Alignment.CenterStart,
                                    contentPadding = PaddingValues(horizontal = d.menuItemPaddingHorizontal, vertical = d.spacingSmall),
                                )
                            }
                        }
                    }
                }
            }
        }
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxHeight()
                .onFocusChanged { if (it.hasFocus) menuOwnsInput = false }.focusGroup()
        ) {
            val cardMetrics = rememberCatalogCardMetrics(maxWidth)
            if (section == Section.ACCOUNT) {
                AccountPane(accountSession, accountState, accountFocus, onLogin, onLogout, onCheckAccount)
            } else if (section == Section.SETTINGS) {
                SettingsPane(
                    imagePrefetchEnabled = imagePrefetchEnabled, onImagePrefetchChange = onImagePrefetchChange,
                    bypassEnabled = bypassEnabled, settingsContentFocus = settingsContentFocus,
                    translationSettingsModel = translationSettingsModel,
                    menuFocus = settingsFocus,
                    onBypassChange = onBypassChange,
                    dnsProvider = dnsProvider,
                    onDnsChange = onDnsChange,
                    startPage = startPage, onStartPageChange = onStartPageChange,
                    hiddenSections = hiddenSections, onSectionVisibilityChange = onSectionVisibilityChange,
                    autoNextEnabled = autoNextEnabled, onAutoNextChange = onAutoNextChange,
                    autoNextSeasonEnabled = autoNextSeasonEnabled, onAutoNextSeasonChange = onAutoNextSeasonChange,
                    playerClockPosition = playerClockPosition, onPlayerClockPositionChange = onPlayerClockPositionChange,
                    signedIn = accountSession.user != null,
                    onBack = ::navigateBack,
                    onCloseDialog = { backNavigation.reset(); returnToMenu() },
                    onInteraction = backNavigation::reset,
                )
            } else if (section == Section.ABOUT) {
                ru.seasonvar.tv.ui.about.AboutPane(aboutContentFocus, aboutFocus, backNavigation::reset, updateModel)
            } else if (detailsOpen && detailsSource != null && detailsModel != null) {
                SeriesDetailsPane(
                    detailsSource!!, detailsModel,
                    onActions = if (accountSession.user == null) null else ::openSeriesActions,
                    removalDialogOpen = removalState.visible,
                    onPlay = onPlay,
                    menuFocus = if (searching) searchFocus else if (smart) smartFocus else if (personalFeed != null) personalFocus.getValue(personalFeed) else when (activeFeed) {
                        PublicFeed.TODAY -> todayFocus
                        PublicFeed.UPDATES -> updatesFocus
                        PublicFeed.TOP -> topFocus
                        PublicFeed.NEWEST -> newestFocus
                        PublicFeed.BEST -> bestFocus
                        PublicFeed.ANNOUNCEMENTS -> announcementsFocus
                    },
                    onInteraction = backNavigation::reset,
                )
            } else {
                CatalogPane(
                    state, selected, listState, cards, if (searching || smart || personalFeed != null) null else activeFeed,
                    if (searching) searchFocus else if (smart) smartFocus else if (personalFeed != null) personalFocus.getValue(personalFeed) else when (activeFeed) {
                        PublicFeed.TODAY -> todayFocus
                        PublicFeed.UPDATES -> updatesFocus
                        PublicFeed.TOP -> topFocus
                        PublicFeed.NEWEST -> newestFocus
                        PublicFeed.BEST -> bestFocus
                        PublicFeed.ANNOUNCEMENTS -> announcementsFocus
                    }, refreshFocus, retryFocus,
                    imagePrefetchEnabled = imagePrefetchEnabled,
                    loadDescription = loadDescription,
                    preparationContext = Triple(accountSession.version, bypassEnabled, dnsProvider),
                    cardMetrics = cardMetrics, layout = catalogLayout, gridState = gridState, layoutFocus = layoutFocus,
                    onLayoutChange = { backNavigation.reset(); onCatalogLayoutChange(section, catalogLayout.other()) },
                    onEnterContent = ::enterContent,
                    query = if (searching) searchState.query else null,
                    searchHeader = if (searching) { {
                        SearchControls(
                            query = searchState.query,
                            onQuery = { pendingHistoryQuery = null; onSearchQuery(it) },
                            showHistory = searchState.showHistory,
                            historyLoading = searchState.historyLoading,
                            historyFailed = searchState.historyFailed,
                            onCommit = onSearchRemember,
                            inputFocus = searchInputFocus,
                            menuFocus = searchFocus,
                            editRequest = searchEditRequest,
                            onSubmit = { keyboard?.hide(); onSearchSubmit(); if (state.entries.isNotEmpty()) enterContent() },
                            onResults = { keyboard?.hide(); if (searchState.showHistory) {
                                if (searchState.recentQueries.isNotEmpty()) searchHistoryFocus.requestFocus()
                            } else if (state.entries.isNotEmpty()) enterContent() },
                        )
                    } } else null,
                    historyContent = if (searching && searchState.showHistory) ({
                        if (!searchState.historyLoading) ru.seasonvar.tv.ui.search.SearchHistoryPane(
                            searchState.recentQueries, searchState.historyClearing, searchHistoryFocus, searchInputFocus, searchFocus,
                            focusOnShow = searchState.focusHistoryOnShow,
                            onRecall = { query -> pendingHistoryQuery = query; keyboard?.hide(); onSearchRecall(query) },
                            onClear = onSearchClearHistory)
                    }) else null,
                    onRefresh = { if (searching) onSearchSubmit() else if (smart) onSmartRefresh() else if (personalFeed != null) onPersonalRefresh(personalFeed) else onRefresh(activeFeed) },
                    smart = smart, personalFeed = personalFeed, catalogKey = catalogKey, allowAccountActions = accountSession.user != null,
                    filters = if (personalFeed != null) personalFilters[personalFeed]
                        else if (!searching && !smart) publicFilters[activeFeed] else null,
                    historyClear = if (personalFeed == PersonalFeed.HISTORY) historyClear else null,
                    onLoadMore = { personalFeed?.let(onPersonalMore) },
                    onFocused = { if (latestCatalogKey == catalogKey) selectEntry(it.key) },
                    onOpen = ::openDetails,
                    onActions = { entry ->
                        selectEntry(entry.key)
                        openSeriesActions(entry)
                    },
                )
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
) {
    var focused by remember { mutableStateOf(false) }
    var blockedPress by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val c = MaterialTheme.colorScheme
    val d = SeasonvarTheme.dimensions
    val action = { if (enabled && actionEnabled && !loading) onClick() }
    val loadingDescription = if (loading) loadingLabel ?: stringResource(R.string.today_updating) else null
    val buttonModifier = modifier.focusRequester(focus).focusProperties { canFocus = enabled }
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
            else -> c.background
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
                Icon(painterResource(icon), contentDescription = text,
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
            modifier = buttonModifier.width(IntrinsicSize.Max).height(IntrinsicSize.Min),
            shape = shape, scale = scale, colors = colors, contentPadding = contentPadding) {
            if (checked != null || reserveCheckSpace) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                Box(Modifier.size(d.headerButtonIconSize), contentAlignment = Alignment.Center) {
                    if (checked == true) Icon(painterResource(R.drawable.ic_check), contentDescription = null,
                        modifier = Modifier.size(d.headerButtonIconSize))
                }
                Text(text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Box(Modifier.fillMaxSize(), contentAlignment = contentAlignment) {
                Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
private fun CatalogPane(
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
    layout: CatalogLayout = CatalogLayout.STRIP,
    gridState: LazyGridState = rememberLazyGridState(),
    layoutFocus: FocusRequester = remember { FocusRequester() },
    onLayoutChange: () -> Unit = {},
    onEnterContent: () -> Unit = {},
    filters: ru.seasonvar.tv.ui.filters.ListFiltersController? = null,
    historyClear: ru.seasonvar.tv.ui.account.HistoryClearController? = null,
    loadDescription: (suspend (SeriesEntry) -> ru.seasonvar.tv.data.SeriesDescription)? = null,
    preparationContext: Any? = null,
) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val engine = ru.seasonvar.tv.ui.catalog.rememberSeriesListEngine(loadDescription, preparationContext)
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
    val strings = remember(feed, personalFeed, smart) {
        if (smart) CatalogStrings(R.string.smart_continue_title, R.string.resume_loading, R.string.smart_continue_empty)
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
                } else CatalogHeading(stringResource(strings.title), metadata, Modifier.fillMaxWidth())
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
                    icon = R.drawable.ic_filter, badgeCount = filterState.activeCount,
                )
                if (historyClear != null) ThemeButton(stringResource(R.string.history_clear_action), historyClear::ask,
                    Modifier.focusRequester(clearFocus).focusProperties { left = menuFocus; right = layoutFocus }.then(enterCards),
                    icon = R.drawable.ic_delete,
                    actionEnabled = state.loaded && !state.loading && state.entries.isNotEmpty() && clearState?.busy != true)
                ThemeButton(
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
        if (historyContent != null) Box(Modifier.weight(1f).fillMaxWidth()) { historyContent() }
        else if (selected == null) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
            ) {
                val message = when {
                    query != null && query.isBlank() -> R.string.search_hint
                    state.loading && !state.loaded -> strings.loading
                    state.failure == LoadFailure.MARKUP -> if (personal) R.string.resume_error else if (feed == null) R.string.search_error else R.string.today_markup_error
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
                        else if (smart) R.string.smart_continue_empty_hint
                        else if (personalFeed == PersonalFeed.HISTORY) R.string.history_empty_hint
                        else if (personalFeed == PersonalFeed.RESUME) R.string.resume_empty_hint
                        else if (personal) R.string.personal_empty_hint else if (feed == null) {
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
                SelectedPanel(selected, { engine.description(selected) }, Modifier.weight(1f).padding(bottom = d.spacingMedium))
            ru.seasonvar.tv.ui.catalog.SeriesList(state.entries, catalogKey, selected.key, layout,
                listState, gridState, cards, layoutFocus, menuFocus, cardMetrics, imagePrefetchEnabled,
                if (layout == CatalogLayout.GRID) Modifier.fillMaxWidth().weight(1f)
                else Modifier.fillMaxWidth().height(cardMetrics.rowHeight),
                onFocused, onOpen, if (allowAccountActions) onActions else null, engine = engine)
        }
    }
    if (filters != null && filterState != null) ru.seasonvar.tv.ui.filters.ListFilterPanel(stringResource(strings.title), filterState, filters)
}

@Composable
private fun SelectedPanel(entry: SeriesEntry, description: () -> DescriptionState, modifier: Modifier) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    var posterRatio by remember(entry.posterUrl, d.posterWidth, d.posterHeight) {
        mutableFloatStateOf(d.posterWidth / d.posterHeight)
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val posterWidth = (maxHeight * posterRatio).coerceAtMost(maxWidth * d.previewPosterMaxWidthFraction)
        val posterHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxHeight.roundToPx().coerceAtLeast(1) }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
            Column(Modifier.weight(1f)) {
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
                val state = description()
                val text = when {
                    state.key != entry.key || state.loading -> entry.previewDescription ?: stringResource(R.string.description_loading)
                    state.failed -> entry.previewDescription ?: stringResource(R.string.description_error)
                    state.description?.paragraphs.isNullOrEmpty() -> entry.previewDescription ?: stringResource(R.string.description_empty)
                    else -> state.description.paragraphs.joinToString("\n\n")
                }
                Text(text, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
                    maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Poster(entry, Modifier.width(posterWidth).fillMaxHeight(), large = true,
                targetHeightPx = posterHeightPx,
                onAspectRatio = { posterRatio = it })
        }
    }
}

@Composable
internal fun SeriesThumbnail(
    entry: SeriesEntry,
    requester: FocusRequester,
    up: FocusRequester,
    metrics: CatalogCardMetrics,
    left: FocusRequester?,
    right: FocusRequester,
    content: () -> ru.seasonvar.tv.ui.catalog.SeriesCardContent?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
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
    Column(
        modifier.size(metrics.width, metrics.height)
            .focusRequester(requester)
            .focusProperties {
                this.up = up
                this.right = right
                if (left != null) this.left = left
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused() else { holdJob?.cancel(); activation.cancel() }
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
            .border(d.focusBorderWidth, if (focused) c.primary else Color.Transparent, shape)
            .background(c.surface, shape)
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) stringResource(R.string.mark_actions_title) else null)
            .padding(d.cardPadding),
    ) {
        val prepared = content()
        if (prepared != null) {
            Poster(entry, Modifier.fillMaxWidth().height(metrics.posterHeight))
            Spacer(Modifier.height(d.spacingSmall))
            Text(entry.title, modifier = Modifier.height(metrics.titleHeight),
                style = MaterialTheme.typography.labelMedium, color = c.onSurface,
                minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(prepared.subtitle, modifier = Modifier.height(metrics.subtitleHeight),
                style = MaterialTheme.typography.labelSmall, color = c.onSurface,
                minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Poster(entry: SeriesEntry, modifier: Modifier, large: Boolean = false,
    targetHeightPx: Int? = null, onAspectRatio: ((Float) -> Unit)? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val largeUrl = if (large) entry.largePosterUrl else null
    var fallback by remember(entry.posterUrl, largeUrl) { mutableStateOf(false) }
    val source = if (!fallback && largeUrl != null) largeUrl else entry.posterUrl
    val request = remember(context, source, targetHeightPx) {
        coil.request.ImageRequest.Builder(context).data(source)
            .apply { if (targetHeightPx != null) size(coil.size.Size(
                coil.size.Dimension.Undefined, coil.size.Dimension.Pixels(targetHeightPx))) }
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
            contentScale = if (large) ContentScale.Fit else ContentScale.Crop,
            placeholder = placeholder, error = placeholder,
            onSuccess = { state ->
                val width = state.result.drawable.intrinsicWidth
                val height = state.result.drawable.intrinsicHeight
                if (width > 0 && height > 0) onAspectRatio?.invoke(width.toFloat() / height)
                if (BuildConfig.DEBUG && large) android.util.Log.d("SeasonvarPoster",
                    "source=" + state.result.dataSource + " decoded=" + width + "x" + height)
            },
            onError = { if (largeUrl != null && source == largeUrl && largeUrl != entry.posterUrl) fallback = true },
        )
        if (entry.isNew) {
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
        if (entry.isWatching) {
            Box(
                modifier = Modifier.align(Alignment.TopStart)
                    .padding(start = d.watchBadgeInset,
                        top = if (entry.isNew) d.badgeHeight + d.watchBadgeInset else d.watchBadgeInset)
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
private fun SettingsPane(
    imagePrefetchEnabled: Boolean,
    onImagePrefetchChange: (Boolean) -> Unit,
    autoNextSeasonEnabled: Boolean,
    onAutoNextSeasonChange: (Boolean) -> Unit,
    autoNextEnabled: Boolean,
    onAutoNextChange: (Boolean) -> Unit,
    playerClockPosition: PlayerClockPosition,
    onPlayerClockPositionChange: (PlayerClockPosition) -> Unit,
    translationSettingsModel: ru.seasonvar.tv.ui.settings.TranslationSettingsViewModel?,
    bypassEnabled: Boolean,
    settingsContentFocus: FocusRequester,
    menuFocus: FocusRequester,
    onBypassChange: (Boolean) -> Unit,
    dnsProvider: DnsProvider,
    onDnsChange: (DnsProvider) -> Unit,
    startPage: Section,
    onStartPageChange: (Section) -> Unit,
    hiddenSections: Set<Section>,
    onSectionVisibilityChange: (Section, Boolean) -> Unit,
    signedIn: Boolean,
    onBack: () -> Unit,
    onCloseDialog: () -> Unit,
    onInteraction: () -> Unit,
) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    var startDialogOpen by rememberSaveable { mutableStateOf(false) }
    var startFocused by remember { mutableStateOf(false) }
    val bypassFocus = remember { FocusRequester() }
    val translationFocus = remember { FocusRequester() }
    val menuVisibilityFocus = remember { FocusRequester() }
    val homeChannelsFocus = remember { FocusRequester() }
    val autoNextFocus = remember { FocusRequester() }
    var autoNextFocused by remember { mutableStateOf(false) }
    val prefetchFocus = remember { FocusRequester() }
    var prefetchFocused by remember { mutableStateOf(false) }
    val autoSeasonFocus = remember { FocusRequester() }
    var autoSeasonFocused by remember { mutableStateOf(false) }
    val clockFocus = remember { FocusRequester() }
    var clockFocused by remember { mutableStateOf(false) }
    var clockDialogOpen by rememberSaveable { mutableStateOf(false) }
    var dnsDialogOpen by rememberSaveable { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var dnsFocused by remember { mutableStateOf(false) }
    val dnsFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium, color = c.onBackground)
        Spacer(Modifier.height(d.spacingLarge))
        Text(stringResource(R.string.application_settings_title),
            style = MaterialTheme.typography.titleLarge, color = c.onBackground)
        Spacer(Modifier.height(d.spacingMedium))
        Row(
            Modifier.fillMaxWidth().focusRequester(settingsContentFocus)
                .focusProperties { left = menuFocus; up = FocusRequester.Cancel; down = menuVisibilityFocus }
                .onFocusChanged { startFocused = it.isFocused }
                .border(d.focusBorderWidth, if (startFocused) c.primary else Color.Transparent,
                    RoundedCornerShape(d.cardCornerRadius))
                .clickable(role = Role.Button, onClick = { onInteraction(); startDialogOpen = true })
                .padding(d.spacingMedium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.start_page_title), color = c.onBackground,
                    style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(d.spacingSmall))
                Text(stringResource(R.string.start_page_description), color = c.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.width(d.spacingMedium))
            Text(stringResource(sectionTitle(startPage.availableAtStart(signedIn, hiddenSections))), color = c.primary,
                style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(d.spacingMedium))
        ru.seasonvar.tv.ui.settings.MenuVisibilitySetting(hiddenSections, onSectionVisibilityChange,
            menuVisibilityFocus, menuFocus, settingsContentFocus, homeChannelsFocus, onBack, onCloseDialog, onInteraction)
        Spacer(Modifier.height(d.spacingMedium))
        ru.seasonvar.tv.ui.settings.HomeChannelsSetting(homeChannelsFocus, menuFocus, menuVisibilityFocus,
            translationFocus, onBack, onCloseDialog, onInteraction)
        Spacer(Modifier.height(d.spacingMedium))
        ru.seasonvar.tv.ui.settings.PreferredTranslationsSetting(translationSettingsModel, translationFocus,
            menuFocus, homeChannelsFocus, autoNextFocus, onBack, onCloseDialog, onInteraction)
        Spacer(Modifier.height(d.spacingLarge))
        Text(stringResource(R.string.player_settings_title),
            style = MaterialTheme.typography.titleLarge, color = c.onBackground)
        Spacer(Modifier.height(d.spacingMedium))
        Row(Modifier.fillMaxWidth().focusRequester(autoNextFocus)
            .focusProperties { left = menuFocus; up = translationFocus; down = autoSeasonFocus }
            .onFocusChanged { autoNextFocused = it.isFocused }
            .border(d.focusBorderWidth, if (autoNextFocused) c.primary else Color.Transparent,
                RoundedCornerShape(d.cardCornerRadius))
            .toggleable(autoNextEnabled, role = Role.Checkbox, onValueChange = { onInteraction(); onAutoNextChange(it) })
            .padding(d.spacingMedium), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.auto_next_setting_title), style = MaterialTheme.typography.titleLarge, color = c.onBackground)
                Spacer(Modifier.height(d.spacingSmall))
                Text(stringResource(R.string.auto_next_setting_description),
                    style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
            }
            Spacer(Modifier.width(d.spacingMedium))
            Checkbox(checked = autoNextEnabled, onCheckedChange = null)
        }
        Spacer(Modifier.height(d.spacingMedium))
        Row(Modifier.fillMaxWidth().focusRequester(autoSeasonFocus)
            .focusProperties { left = menuFocus; up = autoNextFocus; down = clockFocus }
            .onFocusChanged { autoSeasonFocused = it.isFocused }
            .border(d.focusBorderWidth, if (autoSeasonFocused) c.primary else Color.Transparent,
                RoundedCornerShape(d.cardCornerRadius))
            .toggleable(autoNextSeasonEnabled, role = Role.Checkbox, onValueChange = { onInteraction(); onAutoNextSeasonChange(it) })
            .padding(d.spacingMedium), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.auto_next_season_setting_title), style = MaterialTheme.typography.titleLarge, color = c.onBackground)
                Spacer(Modifier.height(d.spacingSmall))
                Text(stringResource(R.string.auto_next_season_setting_description),
                    style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
            }
            Spacer(Modifier.width(d.spacingMedium))
            Checkbox(checked = autoNextSeasonEnabled, onCheckedChange = null)
        }
        Spacer(Modifier.height(d.spacingMedium))
        Row(Modifier.fillMaxWidth().focusRequester(clockFocus)
            .focusProperties { left = menuFocus; up = autoSeasonFocus; down = prefetchFocus }
            .onFocusChanged { clockFocused = it.isFocused }
            .border(d.focusBorderWidth, if (clockFocused) c.primary else Color.Transparent,
                RoundedCornerShape(d.cardCornerRadius))
            .clickable(role = Role.Button, onClick = { onInteraction(); clockDialogOpen = true })
            .padding(d.spacingMedium), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.player_clock_title), style = MaterialTheme.typography.titleLarge, color = c.onBackground)
                Spacer(Modifier.height(d.spacingSmall))
                Text(stringResource(R.string.player_clock_description), style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
            }
            Spacer(Modifier.width(d.spacingMedium))
            Text(stringResource(playerClockLabel(playerClockPosition)), style = MaterialTheme.typography.bodyLarge, color = c.primary)
        }
        Spacer(Modifier.height(d.spacingLarge))
        Text(stringResource(R.string.network_title),
            style = MaterialTheme.typography.titleLarge, color = c.onBackground)
        Spacer(Modifier.height(d.spacingMedium))
        Row(Modifier.fillMaxWidth().focusRequester(prefetchFocus)
            .focusProperties { left = menuFocus; up = clockFocus; down = bypassFocus }
            .onFocusChanged { prefetchFocused = it.isFocused }
            .border(d.focusBorderWidth, if (prefetchFocused) c.primary else Color.Transparent,
                RoundedCornerShape(d.cardCornerRadius))
            .toggleable(imagePrefetchEnabled, role = Role.Checkbox, onValueChange = { onInteraction(); onImagePrefetchChange(it) })
            .padding(d.spacingMedium), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.image_prefetch_title), style = MaterialTheme.typography.titleLarge, color = c.onBackground)
                Spacer(Modifier.height(d.spacingSmall))
                Text(stringResource(R.string.image_prefetch_description), style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
            }
            Spacer(Modifier.width(d.spacingMedium))
            Checkbox(checked = imagePrefetchEnabled, onCheckedChange = null)
        }
        Spacer(Modifier.height(d.spacingMedium))
        Row(
            Modifier.fillMaxWidth().focusRequester(bypassFocus)
                .focusProperties {
                    left = menuFocus
                    up = prefetchFocus
                    down = dnsFocus
                }
                .onFocusChanged { focused = it.isFocused }
                .border(d.focusBorderWidth, if (focused) c.primary else Color.Transparent,
                    RoundedCornerShape(d.cardCornerRadius))
                .toggleable(value = bypassEnabled, role = Role.Checkbox,
                    onValueChange = onBypassChange)
                .padding(d.spacingMedium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.bypass_title), color = c.onBackground,
                    style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(d.spacingSmall))
                Text(stringResource(R.string.bypass_description), color = c.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.width(d.spacingMedium))
            Checkbox(checked = bypassEnabled, onCheckedChange = null)
        }
        Spacer(Modifier.height(d.spacingLarge))
        Row(
            Modifier.fillMaxWidth().focusRequester(dnsFocus)
                .focusProperties {
                    left = menuFocus
                    up = bypassFocus
                    down = FocusRequester.Cancel
                }
                .onFocusChanged { dnsFocused = it.isFocused }
                .border(d.focusBorderWidth, if (dnsFocused) c.primary else Color.Transparent,
                    RoundedCornerShape(d.cardCornerRadius))
                .clickable(role = Role.Button, onClick = { onInteraction(); dnsDialogOpen = true })
                .padding(d.spacingMedium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.dns_setting_title), color = c.onBackground,
                    style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(d.spacingSmall))
                Text(stringResource(R.string.dns_description), color = c.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.width(d.spacingMedium))
            Text(stringResource(dnsLabel(dnsProvider)), color = c.primary,
                style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(d.spacingLarge))
        Text(stringResource(R.string.network_applies), color = c.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(d.spacingSmall))
        Text(stringResource(R.string.network_external_tools_hint), color = c.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge)
    }
    if (startDialogOpen) {
        SettingsChoiceDialog(
            title = R.string.start_page_dialog_title,
            options = Section.startPages(signedIn, hiddenSections), selected = startPage.availableAtStart(signedIn, hiddenSections), label = ::sectionTitle,
            onSelect = { selected ->
                onStartPageChange(selected)
                startDialogOpen = false
                scope.launch { repeat(2) { withFrameNanos { } }; settingsContentFocus.requestFocus() }
            },
            onClose = { startDialogOpen = false; onCloseDialog() },
            onBack = { startDialogOpen = false; onBack() },
            onInteraction = onInteraction,
        )
    }
    if (dnsDialogOpen) {
        SettingsChoiceDialog(
            title = R.string.dns_title, options = DnsProvider.entries, selected = dnsProvider, label = ::dnsLabel,
            onSelect = { provider ->
                onDnsChange(provider)
                dnsDialogOpen = false
                scope.launch { repeat(2) { withFrameNanos { } }; dnsFocus.requestFocus() }
            },
            onClose = { dnsDialogOpen = false; onCloseDialog() },
            onBack = { dnsDialogOpen = false; onBack() },
            onInteraction = onInteraction,
        )
    }
    if (clockDialogOpen) {
        SettingsChoiceDialog(
            title = R.string.player_clock_title, options = PlayerClockPosition.entries,
            selected = playerClockPosition, label = ::playerClockLabel,
            onSelect = { position ->
                onPlayerClockPositionChange(position)
                clockDialogOpen = false
                scope.launch { repeat(2) { withFrameNanos { } }; clockFocus.requestFocus() }
            },
            onClose = { clockDialogOpen = false; onCloseDialog() },
            onBack = { clockDialogOpen = false; onBack() },
            onInteraction = onInteraction,
        )
    }
}

@Composable
private fun <T> SettingsChoiceDialog(
    @StringRes title: Int,
    options: List<T>,
    selected: T,
    label: (T) -> Int,
    onSelect: (T) -> Unit,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onInteraction: () -> Unit,
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
                            Text(stringResource(label(provider)), color = c.onBackground,
                                style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
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

private fun dnsLabel(provider: DnsProvider): Int = when (provider) {
    DnsProvider.SYSTEM -> R.string.dns_system
    DnsProvider.QUAD9_DOH -> R.string.dns_quad9
}

private fun playerClockLabel(position: PlayerClockPosition): Int = when (position) {
    PlayerClockPosition.OFF -> R.string.player_clock_off
    PlayerClockPosition.LEFT -> R.string.player_clock_left
    PlayerClockPosition.CENTER -> R.string.player_clock_center
    PlayerClockPosition.RIGHT -> R.string.player_clock_right
}

@Preview(device = "spec:width=960dp,height=540dp,dpi=320", showBackground = true)
@Composable
private fun CatalogPreview() {
    SeasonvarApp(
        PublicFeed.entries.associateWith { CatalogUiState() },
        {},
    )
}
