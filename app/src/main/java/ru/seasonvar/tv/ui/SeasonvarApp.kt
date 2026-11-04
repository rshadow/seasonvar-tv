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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
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
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.PublicFeed
import ru.seasonvar.tv.data.network.DnsProvider
import ru.seasonvar.tv.ui.details.SeriesDetailsPane
import ru.seasonvar.tv.ui.details.SeriesDetailsViewModel
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import ru.seasonvar.tv.ui.navigation.Section
import ru.seasonvar.tv.ui.navigation.BackNavigation
import ru.seasonvar.tv.ui.navigation.backNavigationKeys
import ru.seasonvar.tv.ui.catalog.DescriptionState
import ru.seasonvar.tv.ui.catalog.LoadFailure
import ru.seasonvar.tv.ui.catalog.CatalogUiState
import ru.seasonvar.tv.data.account.PersonalFeed
import ru.seasonvar.tv.ui.search.SearchUiState
import ru.seasonvar.tv.ui.search.SearchControls
import java.time.format.DateTimeFormatter

private const val CARD_WARMUP_FRAMES = 8
private data class RemovalFocusOrigin(val catalogKey: String, val keys: List<String>, val selected: String)

private fun personalTitle(feed: PersonalFeed) = when (feed) {
    PersonalFeed.RESUME -> R.string.resume_title
    PersonalFeed.UPDATES -> R.string.personal_updates_title
    PersonalFeed.NO_UPDATES -> R.string.personal_no_updates_title
    PersonalFeed.WANT -> R.string.personal_want_title
    PersonalFeed.WATCHED -> R.string.personal_watched_title
    PersonalFeed.HISTORY -> R.string.history_title
}

private fun sectionTitle(item: Section): Int = when (item) {
    Section.SEARCH -> R.string.search_title
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SeasonvarApp(
    states: Map<PublicFeed, CatalogUiState>,
    descriptions: Map<PublicFeed, DescriptionState>,
    onRefresh: (PublicFeed) -> Unit,
    onSelect: (PublicFeed, SeriesEntry, Boolean) -> Unit,
    onFeedVisible: (PublicFeed) -> Unit = {},
    searchState: SearchUiState = SearchUiState(),
    searchDescription: DescriptionState = DescriptionState(),
    onSearchQuery: (String) -> Unit = {},
    onSearchSubmit: () -> Unit = {},
    onSearchSelect: (SeriesEntry, Boolean) -> Unit = { _, _ -> },
    onSearchVisible: (Boolean) -> Unit = {},
    bypassEnabled: Boolean = true,
    onBypassChange: (Boolean) -> Unit = {},
    dnsProvider: DnsProvider = DnsProvider.QUAD9_DOH,
    onDnsChange: (DnsProvider) -> Unit = {},
    startPage: Section = Section.TODAY,
    onStartPageChange: (Section) -> Unit = {},
    onExit: () -> Unit = {},
    detailsModel: SeriesDetailsViewModel? = null,
    onPlay: (PlaybackRequest) -> Unit = {},
    accountSession: AccountSession = AccountSession(),
    accountState: AccountUiState = AccountUiState(busy = false),
    personalStates: Map<PersonalFeed, CatalogUiState> = emptyMap(),
    personalDescriptions: Map<PersonalFeed, DescriptionState> = emptyMap(),
    onLogin: (String, String) -> Unit = { _, _ -> },
    onLogout: () -> Unit = {},
    onCheckAccount: () -> Unit = {},
    onPersonalVisible: (PersonalFeed) -> Unit = {},
    onPersonalRefresh: (PersonalFeed) -> Unit = {},
    onPersonalMore: (PersonalFeed) -> Unit = {},
    onPersonalSelect: (PersonalFeed, SeriesEntry, Boolean) -> Unit = { _, _, _ -> },
    removalState: MarkRemovalUiState = MarkRemovalUiState(),
    onRemovalActions: (SeriesEntry) -> Unit = {},
    onRemovalRequest: (SeriesEntry) -> Unit = {},
    onRemovalAsk: () -> Unit = {},
    onRemovalDismiss: () -> Unit = {},
    onRemovalConfirm: () -> Unit = {},
) {
    SeasonvarTheme {
        SeasonvarContent(
            states = states,
            descriptions = descriptions,
            onRefresh = onRefresh,
            onSelect = onSelect,
            onFeedVisible = onFeedVisible,
            searchState = searchState,
            searchDescription = searchDescription,
            onSearchQuery = onSearchQuery,
            onSearchSubmit = onSearchSubmit,
            onSearchSelect = onSearchSelect,
            onSearchVisible = onSearchVisible,
            bypassEnabled = bypassEnabled,
            onBypassChange = onBypassChange,
            dnsProvider = dnsProvider,
            onDnsChange = onDnsChange,
            startPage = startPage, onStartPageChange = onStartPageChange,
            onExit = onExit,
            detailsModel = detailsModel,
            onPlay = onPlay,
            removalState = removalState, onRemovalActions = onRemovalActions, onRemovalRequest = onRemovalRequest,
            onRemovalAsk = onRemovalAsk, onRemovalDismiss = onRemovalDismiss, onRemovalConfirm = onRemovalConfirm,
            accountSession = accountSession, accountState = accountState,
            personalStates = personalStates, personalDescriptions = personalDescriptions,
            onLogin = onLogin, onLogout = onLogout, onCheckAccount = onCheckAccount,
            onPersonalVisible = onPersonalVisible, onPersonalRefresh = onPersonalRefresh, onPersonalMore = onPersonalMore, onPersonalSelect = onPersonalSelect,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeasonvarContent(
    states: Map<PublicFeed, CatalogUiState>,
    descriptions: Map<PublicFeed, DescriptionState>,
    onRefresh: (PublicFeed) -> Unit,
    onSelect: (PublicFeed, SeriesEntry, Boolean) -> Unit,
    onFeedVisible: (PublicFeed) -> Unit = {},
    searchState: SearchUiState = SearchUiState(),
    searchDescription: DescriptionState = DescriptionState(),
    onSearchQuery: (String) -> Unit = {},
    onSearchSubmit: () -> Unit = {},
    onSearchSelect: (SeriesEntry, Boolean) -> Unit = { _, _ -> },
    onSearchVisible: (Boolean) -> Unit = {},
    bypassEnabled: Boolean = true,
    onBypassChange: (Boolean) -> Unit = {},
    dnsProvider: DnsProvider = DnsProvider.QUAD9_DOH,
    onDnsChange: (DnsProvider) -> Unit = {},
    startPage: Section = Section.TODAY,
    onStartPageChange: (Section) -> Unit = {},
    onExit: () -> Unit = {},
    detailsModel: SeriesDetailsViewModel? = null,
    onPlay: (PlaybackRequest) -> Unit = {},
    accountSession: AccountSession = AccountSession(),
    accountState: AccountUiState = AccountUiState(busy = false),
    personalStates: Map<PersonalFeed, CatalogUiState> = emptyMap(),
    personalDescriptions: Map<PersonalFeed, DescriptionState> = emptyMap(),
    onLogin: (String, String) -> Unit = { _, _ -> },
    onLogout: () -> Unit = {},
    onCheckAccount: () -> Unit = {},
    onPersonalVisible: (PersonalFeed) -> Unit = {},
    onPersonalRefresh: (PersonalFeed) -> Unit = {},
    onPersonalMore: (PersonalFeed) -> Unit = {},
    onPersonalSelect: (PersonalFeed, SeriesEntry, Boolean) -> Unit = { _, _, _ -> },
    removalState: MarkRemovalUiState = MarkRemovalUiState(),
    onRemovalActions: (SeriesEntry) -> Unit = {},
    onRemovalRequest: (SeriesEntry) -> Unit = {},
    onRemovalAsk: () -> Unit = {},
    onRemovalDismiss: () -> Unit = {},
    onRemovalConfirm: () -> Unit = {},
) {
    val initialStartPage = remember { startPage }
    var startupPending by rememberSaveable { mutableStateOf(true) }
    var menuOwnsInput by remember { mutableStateOf(true) }
    var section by rememberSaveable { mutableStateOf(initialStartPage.availableAtStart(accountSession.user != null)) }
    var activeFeed by rememberSaveable { mutableStateOf(section.publicFeed ?: PublicFeed.TODAY) }
    var selectedKeys by rememberSaveable { mutableStateOf(mapOf<String, String>()) }
    val searching = section == Section.SEARCH
    val personalFeed = section.personalFeed
    val catalogKey = if (searching) "SEARCH" else if (personalFeed != null) personalFeed.catalogKey else activeFeed.name
    val state = if (searching) searchState.results else if (personalFeed != null) personalStates[personalFeed] ?: CatalogUiState(loading = false) else states.getValue(activeFeed)
    val description = if (searching) searchDescription else if (personalFeed != null) personalDescriptions[personalFeed] ?: DescriptionState() else descriptions.getValue(activeFeed)
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
    val personalListStates = PersonalFeed.entries.associateWith { rememberLazyListState() }
    val listState = if (searching) searchListState else if (personalFeed != null) personalListStates.getValue(personalFeed) else when (activeFeed) {
        PublicFeed.TODAY -> todayListState
        PublicFeed.UPDATES -> updatesListState
        PublicFeed.TOP -> topListState
        PublicFeed.NEWEST -> newestListState
        PublicFeed.BEST -> bestListState
        PublicFeed.ANNOUNCEMENTS -> announcementsListState
    }
    val searchFocus = remember { FocusRequester() }
    val accountFocus = remember { FocusRequester() }
    val personalFocus = remember { PersonalFeed.entries.associateWith { FocusRequester() } }
    val menuBringIntoView = remember { Section.entries.associateWith { BringIntoViewRequester() } }
    val searchInputFocus = remember { FocusRequester() }
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
    val latestListState by rememberUpdatedState(listState)

    fun selectEntry(key: String?) {
        selectedKeys = if (key == null) selectedKeys - catalogKey
            else selectedKeys + (catalogKey to key)
    }

    fun selectSection(item: Section) {
        detailsOpen = false
        section = item
        item.personalFeed?.let(onPersonalVisible)
        val feed = item.publicFeed
        if (feed != null) {
            activeFeed = feed
            onFeedVisible(feed)
        }
    }

    fun sectionFocus(item: Section): FocusRequester = when (item) {
        Section.SEARCH -> searchFocus
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

    DisposableEffect(searching) {
        onSearchVisible(searching)
        onDispose { if (searching) onSearchVisible(false) }
    }
    LaunchedEffect(searchState.query) {
        selectedKeys = selectedKeys - "SEARCH"
        searchListState.scrollToItem(0)
    }
    LaunchedEffect(catalogKey, state.entries) {
        if (state.entries.none { it.key == selectedKey }) {
            val origin = removalOrigin?.takeIf { it.catalogKey == catalogKey && it.selected == selectedKey }
            selectEntry(if (origin != null) removalNeighbor(origin.keys, state.entries.map { it.key }, origin.selected) else selected?.key)
        }
    }
    LaunchedEffect(catalogKey, selected?.key, description.key) {
        selected?.let { if (searching) onSearchSelect(it, false) else if (personalFeed != null) onPersonalSelect(personalFeed, it, false) else onSelect(activeFeed, it, false) }
    }
    val startupTarget = initialStartPage.atStartup(accountSession.user != null, accountState.busy)
    LaunchedEffect(startupTarget) {
        if (!startupPending) return@LaunchedEffect
        val target = startupTarget ?: Section.TODAY
        if (startupTarget != null) selectSection(target)
        repeat(2) { withFrameNanos { } }
        if (startupPending && section == target) {
            if (startupTarget != null) startupPending = false
            sectionFocus(target).requestFocus()
        }
    }
    LaunchedEffect(Unit) {
        if (!startupPending) {
            repeat(2) { withFrameNanos { } }
            sectionFocus(section).requestFocus()
        }
    }
    LaunchedEffect(accountSession.version) {
        val menuTarget = section.takeIf { menuOwnsInput }
        val personalKeys = PersonalFeed.entries.map { it.catalogKey }.toSet()
        selectedKeys = selectedKeys - personalKeys
        removalOrigin = null
        cards.keys.removeAll { it.substringBefore('|') in personalKeys }
        personalListStates.values.forEach { it.requestScrollToItem(0) }
        detailsOpen = false
        if (section.personalFeed != null && accountSession.user == null) section = Section.ACCOUNT
        if (section == Section.ACCOUNT && accountSession.user != null) {
            selectSection(Section.RESUME)
            repeat(2) { withFrameNanos { } }; personalFocus.getValue(PersonalFeed.RESUME).requestFocus()
        }
        if (menuTarget != null) {
            repeat(2) { withFrameNanos { } }
            if (menuOwnsInput && section == menuTarget) {
                sectionFocus(menuTarget).requestFocus()
                if (menuTarget in Section.startPages(true)) menuBringIntoView.getValue(menuTarget).bringIntoView()
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
            if (section == Section.ACCOUNT) {
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
                if (listState.layoutInfo.visibleItemsInfo.none { it.key == catalogKey + "|" + selected.key }) {
                    listState.scrollToItem(index.coerceAtLeast(0))
                    repeat(2) { withFrameNanos { } }
                    if (section != requestedSection) return@launch
                }
                cards[catalogKey + "|" + selected.key]?.requestFocus()
            }
        }
    }

    fun returnToMenu() {
        val requestedSection = section
        detailsOpen = false
        scope.launch {
            repeat(2) { withFrameNanos { } }
            if (section != requestedSection) return@launch
            when (section) {
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

    fun openDetails(entry: SeriesEntry) {
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
                    startupPending = false; backNavigation.reset(); detailsOpen = false; section = Section.SEARCH; searchEditRequest++
                }, modifier = Modifier.focusRequester(searchFocus).focusProperties { down = todayFocus; right = accountFocus },
                    icon = R.drawable.ic_search)
                ThemeButton(stringResource(if (accountSession.user == null) R.string.account_sign_in else R.string.account_title),
                    onClick = { startupPending = false; backNavigation.reset(); selectSection(Section.ACCOUNT) },
                    modifier = Modifier.focusRequester(accountFocus).focusProperties { left = searchFocus; right = FocusRequester.Cancel; down = todayFocus },
                    icon = R.drawable.ic_person, avatarUrl = accountSession.user?.avatarUrl, avatarKey = accountSession.version)
            }
            Spacer(Modifier.height(d.menuHeaderSpacing))
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(d.menuItemSpacing),
            ) {
                val groups = listOf(R.string.menu_catalog to Section.catalogPages) +
                    (if (accountSession.user != null) listOf(R.string.menu_personal to Section.personalPages) else emptyList()) +
                    listOf(R.string.menu_system to Section.systemPages)
                groups.forEachIndexed { index, (title, sections) ->
                    key(title) {
                        Text(
                            stringResource(title),
                            modifier = Modifier.fillMaxWidth()
                                .then(if (index > 0) Modifier.padding(top = d.spacingSmall) else Modifier)
                                .padding(horizontal = d.spacingSmall, vertical = d.menuItemSpacing)
                                .semantics { heading() },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        sections.forEach { item ->
                            val focus = sectionFocus(item)
                            val label = sectionTitle(item)
                            ThemeButton(
                                stringResource(label),
                                onClick = { startupPending = false; backNavigation.reset(); selectSection(item); enterContent() },
                                modifier = Modifier.fillMaxWidth().height(d.menuItemHeight)
                                    .bringIntoViewRequester(menuBringIntoView.getValue(item))
                                    .focusRequester(focus).focusProperties { if (item == Section.TODAY) up = searchFocus }
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
                            )
                        }
                    }
                }
            }
        }
        Box(
            Modifier.weight(1f).fillMaxHeight()
                .onFocusChanged { if (it.hasFocus) menuOwnsInput = false }.focusGroup()
        ) {
            if (section == Section.ACCOUNT) {
                AccountPane(accountSession, accountState, accountFocus, onLogin, onLogout, onCheckAccount)
            } else if (section == Section.SETTINGS) {
                SettingsPane(
                    bypassEnabled, settingsContentFocus,
                    menuFocus = settingsFocus,
                    onBypassChange = onBypassChange,
                    dnsProvider = dnsProvider,
                    onDnsChange = onDnsChange,
                    startPage = startPage, onStartPageChange = onStartPageChange,
                    signedIn = accountSession.user != null,
                    onBack = ::navigateBack,
                    onCloseDialog = { backNavigation.reset(); returnToMenu() },
                    onInteraction = backNavigation::reset,
                )
            } else if (section == Section.ABOUT) {
                AboutPane(aboutContentFocus, aboutFocus, backNavigation::reset)
            } else if (detailsOpen && detailsSource != null && detailsModel != null) {
                SeriesDetailsPane(
                    detailsSource!!, detailsModel,
                    onRemove = { entry ->
                        removalOrigin = RemovalFocusOrigin(catalogKey, state.entries.map { it.key }, selectedKey ?: entry.key)
                        onRemovalRequest(entry)
                    }, removalDialogOpen = removalState.visible,
                    onPlay = onPlay,
                    menuFocus = if (searching) searchFocus else if (personalFeed != null) personalFocus.getValue(personalFeed) else when (activeFeed) {
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
                    state, description, selected, listState, cards, if (searching || personalFeed != null) null else activeFeed,
                    if (searching) searchFocus else if (personalFeed != null) personalFocus.getValue(personalFeed) else when (activeFeed) {
                        PublicFeed.TODAY -> todayFocus
                        PublicFeed.UPDATES -> updatesFocus
                        PublicFeed.TOP -> topFocus
                        PublicFeed.NEWEST -> newestFocus
                        PublicFeed.BEST -> bestFocus
                        PublicFeed.ANNOUNCEMENTS -> announcementsFocus
                    }, refreshFocus, retryFocus,
                    query = if (searching) searchState.query else null,
                    searchHeader = if (searching) { {
                        SearchControls(
                            query = searchState.query,
                            onQuery = onSearchQuery,
                            inputFocus = searchInputFocus,
                            menuFocus = searchFocus,
                            editRequest = searchEditRequest,
                            onSubmit = { keyboard?.hide(); onSearchSubmit(); if (state.entries.isNotEmpty()) enterContent() },
                            onResults = { keyboard?.hide(); if (state.entries.isNotEmpty()) enterContent() },
                        )
                    } } else null,
                    onRefresh = { if (searching) onSearchSubmit() else if (personalFeed != null) onPersonalRefresh(personalFeed) else onRefresh(activeFeed) },
                    personalFeed = personalFeed, catalogKey = catalogKey,
                    onLoadMore = { personalFeed?.let(onPersonalMore) },
                    onFocused = { if (latestCatalogKey == catalogKey) selectEntry(it.key) },
                    onOpen = ::openDetails,
                    onActions = { entry ->
                        backNavigation.reset()
                        selectEntry(entry.key)
                        removalOrigin = RemovalFocusOrigin(catalogKey, state.entries.map { it.key }, entry.key)
                        onRemovalActions(entry)
                    },
                )
            }
        }
    }
    MarkRemovalDialog(removalState,
        onDismiss = { backNavigation.reset(); onRemovalDismiss() },
        onAsk = { backNavigation.reset(); onRemovalAsk() },
        onConfirm = { backNavigation.reset(); onRemovalConfirm() },
        onOpen = { removalState.entry?.let { entry -> onRemovalDismiss(); openDetails(entry) } },
        onInteraction = backNavigation::reset)
}

@Composable
internal fun ThemeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    @DrawableRes icon: Int? = null,
    loading: Boolean = false,
    loadingLabel: String? = null,
    avatarUrl: String? = null,
    avatarKey: Long = 0,
) {
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val c = MaterialTheme.colorScheme
    val d = SeasonvarTheme.dimensions
    val action = { if (!loading) onClick() }
    val loadingDescription = if (loading) loadingLabel ?: stringResource(R.string.today_updating) else null
    val buttonModifier = modifier.focusRequester(focus).onFocusChanged { focused = it.isFocused }
        .pointerInput(action) { detectTapGestures { focus.requestFocus(); action() } }
        .then(if (loadingDescription != null) Modifier.semantics {
            stateDescription = loadingDescription
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        } else Modifier)
    val shape = ButtonDefaults.shape(shape = RoundedCornerShape(d.buttonCornerRadius))
    val scale = ButtonDefaults.scale(focusedScale = 1f)
    val colors = ButtonDefaults.colors(
        containerColor = when {
            focused -> c.primary
            icon != null -> Color.Transparent
            active -> c.surface
            else -> c.background
        },
        contentColor = if (focused) c.onPrimary else c.onSurface,
        focusedContainerColor = c.primary,
        focusedContentColor = c.onPrimary,
    )
    if (icon != null) {
        IconButton(
            onClick = action,
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
            else Icon(painterResource(icon), contentDescription = text,
                modifier = Modifier.size(d.headerButtonIconSize).then(rotation))
        }
    } else {
        Button(onClick = action, modifier = buttonModifier,
            shape = shape, scale = scale, colors = colors) {
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private data class CatalogStrings(
    @get:StringRes val title: Int,
    @get:StringRes val hint: Int,
    @get:StringRes val count: Int,
    @get:StringRes val loading: Int,
    @get:StringRes val empty: Int,
    @get:StringRes val cached: Int,
    @get:StringRes val cachedError: Int,
)

private fun catalogStrings(feed: PublicFeed): CatalogStrings = when (feed) {
    PublicFeed.TODAY -> CatalogStrings(
        R.string.today_title, R.string.today_loading_hint, R.string.today_date_count,
        R.string.today_loading, R.string.today_empty, R.string.today_cached, R.string.today_cached_error,
    )
    PublicFeed.UPDATES -> CatalogStrings(
        R.string.updates_title, R.string.updates_hint, R.string.updates_count,
        R.string.updates_loading, R.string.updates_empty, R.string.updates_cached, R.string.updates_cached_error,
    )
    PublicFeed.TOP -> CatalogStrings(
        R.string.top_title, R.string.top_hint, R.string.top_count,
        R.string.top_loading, R.string.top_empty, R.string.top_cached, R.string.top_cached_error,
    )
    PublicFeed.NEWEST -> CatalogStrings(
        R.string.newest_title, R.string.newest_hint, R.string.newest_count,
        R.string.newest_loading, R.string.newest_empty, R.string.newest_cached, R.string.newest_cached_error,
    )
    PublicFeed.ANNOUNCEMENTS -> CatalogStrings(
        R.string.announcements_title, R.string.announcements_hint, R.string.announcements_count,
        R.string.announcements_loading, R.string.announcements_empty, R.string.announcements_cached, R.string.announcements_cached_error,
    )
    PublicFeed.BEST -> CatalogStrings(
        R.string.best_title, R.string.best_hint, R.string.best_count,
        R.string.best_loading, R.string.best_empty, R.string.best_cached, R.string.best_cached_error,
    )
}

@Composable
private fun CatalogPane(
    state: CatalogUiState,
    description: DescriptionState,
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
    personalFeed: PersonalFeed? = null,
    catalogKey: String = feed?.name ?: "SEARCH",
    onLoadMore: () -> Unit = {},
    onActions: (SeriesEntry) -> Unit = {},
) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val isToday = feed == PublicFeed.TODAY
    val personal = personalFeed != null
    val strings = remember(feed, personalFeed) {
        if (personalFeed != null) CatalogStrings(personalTitle(personalFeed), R.string.resume_hint,
            R.string.resume_count, R.string.resume_loading, R.string.resume_empty,
            R.string.resume_hint, R.string.resume_error)
        else if (feed == null) CatalogStrings(R.string.search_title, R.string.search_hint,
            R.string.search_count, R.string.search_loading, R.string.search_empty,
            R.string.search_hint, R.string.search_error)
        else catalogStrings(feed)
    }
    val latestLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(catalogKey, listState, state.entries.size, state.hasMore, state.moreFailure) {
        if (state.hasMore && state.moreFailure == null) snapshotFlow {
            (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= state.entries.size - 5
        }.collect { nearEnd -> if (nearEnd) latestLoadMore() }
    }
    val date = remember(state.date) { state.date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) }
    val cardFrames = remember(catalogKey, state.entries.firstOrNull()?.key, listState) { mutableIntStateOf(0) }
    val firstCard = remember(catalogKey, state.entries.firstOrNull()?.key, listState) { listState.firstVisibleItemIndex }
    LaunchedEffect(cardFrames) {
        if (state.entries.isNotEmpty()) repeat(CARD_WARMUP_FRAMES) {
            withFrameNanos { }
            cardFrames.intValue++
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f).padding(end = d.spacingSmall)) {
                if (searchHeader != null) searchHeader()
                else Text(stringResource(strings.title), style = MaterialTheme.typography.headlineSmall,
                    color = c.onBackground)
                if (feed != null || state.loaded) {
                    Text(
                        when {
                            selected != null && state.moreFailure != null -> stringResource(R.string.history_more_error)
                            selected != null && state.failure != null -> stringResource(strings.cachedError)
                            selected != null && state.cached && !state.loading -> stringResource(strings.cached)
                            isToday -> if (state.loaded) stringResource(R.string.today_date_count, date, state.entries.size) else date
                            state.loaded -> stringResource(if (personalFeed == PersonalFeed.HISTORY && state.hasMore) R.string.history_loaded_count else strings.count, state.entries.size)
                            else -> stringResource(strings.hint)
                        },
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                        minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ThemeButton(
                stringResource(if (state.moreFailure != null) R.string.retry else R.string.refresh), onRefresh,
                Modifier.focusRequester(refreshFocus),
                icon = R.drawable.ic_reload,
                loading = state.loading || state.loadingMore,
            )
        }
        if (personalFeed != null && personalFeed != PersonalFeed.HISTORY) Text(stringResource(R.string.mark_long_press_hint),
            color = c.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(d.spacingSmall))
        if (selected == null) {
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
                    else -> strings.empty
                }
                Text(stringResource(message), style = MaterialTheme.typography.titleLarge,
                    color = c.onBackground)
                Spacer(Modifier.height(d.spacingMedium))
                Text(
                    stringResource(if (state.loading && !state.loaded) R.string.today_loading_hint
                        else if (state.failure != null) R.string.today_error_hint
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
            SelectedPanel(selected, description, Modifier.weight(1f))
            Spacer(Modifier.height(d.spacingMedium))
            LazyRow(
                modifier = Modifier.fillMaxWidth().height(d.cardRowHeight),
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(d.spacingSmall),
                contentPadding = PaddingValues(d.cardPadding),
            ) {
                itemsIndexed(
                    state.entries,
                    key = { _, item -> catalogKey + "|" + item.key },
                    contentType = { _, _ -> "series" },
                ) { index, item ->
                    val cardKey = catalogKey + "|" + item.key
                    val cachedRequester = cards[cardKey]
                    val requester = remember(cardKey, cachedRequester) {
                        cachedRequester ?: FocusRequester().also { cards[cardKey] = it }
                    }
                    val contentReady = remember(cardFrames, firstCard, index) {
                        derivedStateOf {
                            val count = cardFrames.intValue
                            count >= CARD_WARMUP_FRAMES || index in firstCard until (firstCard + count)
                        }
                    }
                    SeriesThumbnail(
                        item, requester, refreshFocus,
                        if (index == 0) menuFocus else null,
                        right = if (index == state.entries.lastIndex) FocusRequester.Cancel else FocusRequester.Default,
                        contentReady = { contentReady.value },
                        onFocused = { onFocused(item) }, onClick = { onOpen(item) },
                        onLongClick = if (personal && item.accountListMark != null) ({ onActions(item) }) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedPanel(entry: SeriesEntry, description: DescriptionState, modifier: Modifier) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
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
            val text = when {
                description.key != entry.key || description.loading -> entry.previewDescription ?: stringResource(R.string.description_loading)
                description.failed -> entry.previewDescription ?: stringResource(R.string.description_error)
                description.description?.paragraphs.isNullOrEmpty() -> entry.previewDescription ?: stringResource(R.string.description_empty)
                else -> description.description.paragraphs.joinToString("\n\n")
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
                maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        Poster(entry, Modifier.width(d.posterWidth).height(d.posterHeight))
    }
}

@Composable
private fun SeriesThumbnail(
    entry: SeriesEntry,
    requester: FocusRequester,
    up: FocusRequester,
    left: FocusRequester?,
    right: FocusRequester,
    contentReady: () -> Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
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
        Modifier.width(d.cardWidth).height(d.cardHeight).focusRequester(requester)
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
        if (contentReady()) {
            Poster(entry, Modifier.fillMaxWidth().height(d.thumbnailHeight))
            Spacer(Modifier.height(d.spacingSmall))
            Text(entry.title, style = MaterialTheme.typography.labelMedium, color = c.onSurface,
                minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val subtitle = if (entry.expectedReleaseDate != null)
                stringResource(R.string.announcements_expected, entry.expectedReleaseDate)
            else if (entry.historyVisitedAt != null && entry.initialSelection.episodeLabel != null) listOfNotNull(
                entry.season?.let { stringResource(R.string.season_number, it) },
                stringResource(R.string.episode_number, entry.initialSelection.episodeLabel),
            ).joinToString(" · ") else entry.subtitle.ifBlank {
                entry.season?.let { stringResource(R.string.season_number, it) }.orEmpty()
            }
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = c.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Poster(entry: SeriesEntry, modifier: Modifier) {
    val placeholder = painterResource(R.drawable.poster_placeholder)
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    Box(modifier.clip(RoundedCornerShape(
        topStart = d.cardCornerRadius, topEnd = d.cardCornerRadius))) {
        AsyncImage(
            model = entry.posterUrl,
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
            placeholder = placeholder, error = placeholder,
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
    }
}

@Composable
private fun SettingsPane(
    bypassEnabled: Boolean,
    settingsContentFocus: FocusRequester,
    menuFocus: FocusRequester,
    onBypassChange: (Boolean) -> Unit,
    dnsProvider: DnsProvider,
    onDnsChange: (DnsProvider) -> Unit,
    startPage: Section,
    onStartPageChange: (Section) -> Unit,
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
                .focusProperties { left = menuFocus; up = FocusRequester.Cancel; down = bypassFocus }
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
            Text(stringResource(sectionTitle(startPage)), color = c.primary,
                style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(d.spacingLarge))
        Text(stringResource(R.string.network_title),
            style = MaterialTheme.typography.titleLarge, color = c.onBackground)
        Spacer(Modifier.height(d.spacingMedium))
        Row(
            Modifier.fillMaxWidth().focusRequester(bypassFocus)
                .focusProperties {
                    left = menuFocus
                    up = settingsContentFocus
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
    }
    if (startDialogOpen) {
        SettingsChoiceDialog(
            title = R.string.start_page_dialog_title,
            options = Section.startPages(signedIn), selected = startPage.availableAtStart(signedIn), label = ::sectionTitle,
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

@Composable
private fun AboutPane(
    contentFocus: FocusRequester,
    menuFocus: FocusRequester,
    onInteraction: () -> Unit,
) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val githubUrl = stringResource(R.string.about_github_url)
    var linkUnavailable by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.about_title), style = MaterialTheme.typography.headlineLarge,
            color = c.onBackground)
        Spacer(Modifier.height(d.spacingLarge))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = c.onBackground)
        Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
        Spacer(Modifier.height(d.spacingMedium))
        Text(stringResource(R.string.about_description), color = c.onBackground)
        Text(stringResource(R.string.about_source), color = c.onSurfaceVariant)
        Spacer(Modifier.height(d.spacingLarge))
        ThemeButton(
            stringResource(R.string.about_github),
            onClick = {
                onInteraction()
                linkUnavailable = false
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl))
                        .addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: ActivityNotFoundException) {
                    linkUnavailable = true
                } catch (_: SecurityException) {
                    linkUnavailable = true
                }
            },
            modifier = Modifier.focusRequester(contentFocus).focusProperties {
                left = menuFocus
                right = FocusRequester.Cancel
                up = FocusRequester.Cancel
                down = FocusRequester.Cancel
            },
        )
        Spacer(Modifier.height(d.spacingSmall))
        Text(githubUrl, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
        if (linkUnavailable) {
            Spacer(Modifier.height(d.spacingSmall))
            Text(stringResource(R.string.about_github_unavailable), color = c.onSurfaceVariant)
        }
    }
}

@Preview(device = "spec:width=960dp,height=540dp,dpi=320", showBackground = true)
@Composable
private fun CatalogPreview() {
    SeasonvarApp(
        PublicFeed.entries.associateWith { CatalogUiState() },
        PublicFeed.entries.associateWith { DescriptionState() },
        {}, { _, _, _ -> },
    )
}
