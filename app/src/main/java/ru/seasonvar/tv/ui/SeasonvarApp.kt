package ru.seasonvar.tv.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import ru.seasonvar.tv.BuildConfig
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.PublicFeed
import ru.seasonvar.tv.data.network.DnsProvider
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import ru.seasonvar.tv.ui.navigation.BackNavigation
import ru.seasonvar.tv.ui.navigation.backNavigationKeys
import ru.seasonvar.tv.ui.catalog.DescriptionState
import ru.seasonvar.tv.ui.catalog.LoadFailure
import ru.seasonvar.tv.ui.catalog.CatalogUiState
import ru.seasonvar.tv.ui.search.SearchUiState
import ru.seasonvar.tv.ui.search.SearchControls
import java.time.format.DateTimeFormatter

private const val CARD_WARMUP_FRAMES = 8

private enum class Section { SEARCH, TODAY, UPDATES, TOP, NEWEST, BEST, SETTINGS, ABOUT }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeasonvarApp(
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
    onExit: () -> Unit = {},
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
            onExit = onExit,
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
    onExit: () -> Unit = {},
) {
    var section by rememberSaveable { mutableStateOf(Section.TODAY) }
    var activeFeed by rememberSaveable { mutableStateOf(PublicFeed.TODAY) }
    var selectedKeys by rememberSaveable { mutableStateOf(mapOf<String, String>()) }
    val searching = section == Section.SEARCH
    val catalogKey = if (searching) "SEARCH" else activeFeed.name
    val state = if (searching) searchState.results else states.getValue(activeFeed)
    val description = if (searching) searchDescription else descriptions.getValue(activeFeed)
    val selectedKey = selectedKeys[catalogKey]
    var searchEditRequest by remember { mutableIntStateOf(0) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    val todayListState = rememberLazyListState()
    val updatesListState = rememberLazyListState()
    val topListState = rememberLazyListState()
    val newestListState = rememberLazyListState()
    val bestListState = rememberLazyListState()
    val searchListState = rememberLazyListState()
    val listState = if (searching) searchListState else when (activeFeed) {
        PublicFeed.TODAY -> todayListState
        PublicFeed.UPDATES -> updatesListState
        PublicFeed.TOP -> topListState
        PublicFeed.NEWEST -> newestListState
        PublicFeed.BEST -> bestListState
    }
    val searchFocus = remember { FocusRequester() }
    val searchInputFocus = remember { FocusRequester() }
    val todayFocus = remember { FocusRequester() }
    val updatesFocus = remember { FocusRequester() }
    val topFocus = remember { FocusRequester() }
    val newestFocus = remember { FocusRequester() }
    val bestFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val toggleFocus = remember { FocusRequester() }
    val aboutFocus = remember { FocusRequester() }
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
        section = item
        val feed = when (item) {
            Section.TODAY -> PublicFeed.TODAY
            Section.UPDATES -> PublicFeed.UPDATES
            Section.TOP -> PublicFeed.TOP
            Section.NEWEST -> PublicFeed.NEWEST
            Section.BEST -> PublicFeed.BEST
            else -> null
        }
        if (feed != null) {
            activeFeed = feed
            onFeedVisible(feed)
        }
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
        if (selectedKey != null && state.entries.none { it.key == selectedKey }) detailsOpen = false
        if (state.entries.none { it.key == selectedKey }) selectEntry(selected?.key)
    }
    LaunchedEffect(catalogKey, selected?.key, description.key) {
        selected?.let { if (searching) onSearchSelect(it, false) else onSelect(activeFeed, it, false) }
    }
    LaunchedEffect(Unit) { todayFocus.requestFocus() }

    fun enterContent() {
        val requestedSection = section
        scope.launch {
            repeat(2) { withFrameNanos { } }
            if (section != requestedSection) return@launch
            val selected = latestSelected
            val state = latestState
            val listState = latestListState
            if (section == Section.SETTINGS) {
                toggleFocus.requestFocus()
            } else if (section == Section.ABOUT) {
                aboutFocus.requestFocus()
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
                Section.TODAY -> todayFocus.requestFocus()
                Section.UPDATES -> updatesFocus.requestFocus()
                Section.TOP -> topFocus.requestFocus()
                Section.NEWEST -> newestFocus.requestFocus()
                Section.BEST -> bestFocus.requestFocus()
                Section.SETTINGS -> settingsFocus.requestFocus()
                Section.ABOUT -> aboutFocus.requestFocus()
            }
        }
    }

    fun closeDetails() {
        detailsOpen = false
        enterContent()
    }

    fun navigateBack() {
        if (searching && !detailsOpen && imeVisible) {
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
            .backNavigationKeys(::navigateBack, backNavigation::reset)
            .background(MaterialTheme.colorScheme.background)
            .padding(d.screenPaddingHorizontal, d.screenPaddingVertical),
        horizontalArrangement = Arrangement.spacedBy(d.contentGap),
    ) {
        Column(Modifier.width(d.menuWidth).fillMaxHeight()) {
            ThemeButton(
                stringResource(R.string.search_title),
                onClick = {
                    backNavigation.reset()
                    section = Section.SEARCH
                    searchEditRequest++
                },
                modifier = Modifier.size(d.headerButtonSize).focusRequester(searchFocus)
                    .focusProperties { down = todayFocus },
                icon = R.drawable.ic_search,
            )
            Spacer(Modifier.height(d.menuHeaderSpacing))
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(d.menuItemSpacing),
            ) {
                listOf(Section.TODAY, Section.UPDATES, Section.TOP, Section.NEWEST, Section.BEST, Section.SETTINGS, Section.ABOUT).forEach { item ->
                    val focus = when (item) {
                        Section.SEARCH -> searchFocus
                        Section.TODAY -> todayFocus
                        Section.UPDATES -> updatesFocus
                        Section.TOP -> topFocus
                        Section.NEWEST -> newestFocus
                        Section.BEST -> bestFocus
                        Section.SETTINGS -> settingsFocus
                        Section.ABOUT -> aboutFocus
                    }
                    val label = when (item) {
                        Section.SEARCH -> R.string.search_title
                        Section.TODAY -> R.string.today_title
                        Section.UPDATES -> R.string.updates_title
                        Section.TOP -> R.string.top_title
                        Section.NEWEST -> R.string.newest_title
                        Section.BEST -> R.string.best_title
                        Section.SETTINGS -> R.string.settings_title
                        Section.ABOUT -> R.string.about_title
                    }
                    ThemeButton(
                        stringResource(label),
                        onClick = { backNavigation.reset(); selectSection(item); enterContent() },
                        modifier = Modifier.fillMaxWidth().height(d.menuItemHeight)
                            .focusRequester(focus)
                            .onFocusChanged { if (it.isFocused) selectSection(item) }
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
        Box(
            Modifier.weight(1f).fillMaxHeight().focusGroup()
        ) {
            if (section == Section.SETTINGS) {
                SettingsPane(
                    bypassEnabled, toggleFocus,
                    menuFocus = settingsFocus,
                    onBypassChange = onBypassChange,
                    dnsProvider = dnsProvider,
                    onDnsChange = onDnsChange,
                    onBack = ::navigateBack,
                    onCloseDialog = { backNavigation.reset(); returnToMenu() },
                    onInteraction = backNavigation::reset,
                )
            } else if (section == Section.ABOUT) {
                AboutPane()
            } else {
                CatalogPane(
                    state, description, selected, listState, cards, if (searching) null else activeFeed,
                    if (searching) searchFocus else when (activeFeed) {
                        PublicFeed.TODAY -> todayFocus
                        PublicFeed.UPDATES -> updatesFocus
                        PublicFeed.TOP -> topFocus
                        PublicFeed.NEWEST -> newestFocus
                        PublicFeed.BEST -> bestFocus
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
                    onRefresh = { if (searching) onSearchSubmit() else onRefresh(activeFeed) },
                    onFocused = { if (latestCatalogKey == catalogKey) selectEntry(it.key) },
                    onOpen = {
                        backNavigation.reset()
                        inputModeManager.requestInputMode(InputMode.Keyboard)
                        selectEntry(it.key)
                        detailsOpen = true
                    },
                )
            }
        }
    }
    if (detailsOpen && selected != null) {
        SeriesDialog(
            selected, description,
            onRetry = { if (searching) onSearchSelect(selected, true) else onSelect(activeFeed, selected, true) },
            onClose = { backNavigation.reset(); closeDetails() },
            onBack = ::navigateBack,
            onInteraction = backNavigation::reset,
        )
    }
}

@Composable
internal fun ThemeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    @DrawableRes icon: Int? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val c = MaterialTheme.colorScheme
    val d = SeasonvarTheme.dimensions
    Button(
        onClick = onClick,
        modifier = modifier.focusRequester(focus).onFocusChanged { focused = it.isFocused }
            .pointerInput(onClick) { detectTapGestures { focus.requestFocus(); onClick() } },
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(d.buttonCornerRadius)),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        contentPadding = if (icon != null) PaddingValues(d.spacingSmall) else ButtonDefaults.ContentPadding,
        colors = ButtonDefaults.colors(
            containerColor = when {
                focused -> c.primary
                icon != null -> Color.Transparent
                active -> c.surface
                else -> c.background
            },
            contentColor = if (focused) c.onPrimary else c.onSurface,
            focusedContainerColor = c.primary,
            focusedContentColor = c.onPrimary,
        ),
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = text, modifier = Modifier.size(d.headerButtonIconSize))
        } else {
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
) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val isToday = feed == PublicFeed.TODAY
    val strings = remember(feed) {
        if (feed == null) CatalogStrings(R.string.search_title, R.string.search_hint,
            R.string.search_count, R.string.search_loading, R.string.search_empty,
            R.string.search_hint, R.string.search_error)
        else catalogStrings(feed)
    }
    val date = remember(state.date) { state.date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) }
    val cardFrames = remember(feed, state.entries, listState) { mutableIntStateOf(0) }
    val firstCard = remember(feed, state.entries, listState) { listState.firstVisibleItemIndex }
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
                Text(
                    if (isToday) {
                        if (state.loaded) stringResource(R.string.today_date_count, date, state.entries.size) else date
                    } else {
                        if (state.loaded) stringResource(strings.count, state.entries.size)
                        else stringResource(strings.hint)
                    },
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                )
            }
            ThemeButton(
                stringResource(R.string.refresh), onRefresh,
                Modifier.size(d.headerButtonSize).focusRequester(refreshFocus),
                icon = R.drawable.ic_reload,
            )
        }
        Spacer(Modifier.height(d.spacingSmall))
        if (selected == null) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
            ) {
                val message = when {
                    query != null && query.isBlank() -> R.string.search_hint
                    state.loading -> strings.loading
                    state.failure == LoadFailure.MARKUP -> if (feed == null) R.string.search_error else R.string.today_markup_error
                    state.failure == LoadFailure.HTTP -> R.string.today_http_error
                    state.failure != null -> R.string.today_network_error
                    else -> strings.empty
                }
                Text(stringResource(message), style = MaterialTheme.typography.titleLarge,
                    color = c.onBackground)
                Spacer(Modifier.height(d.spacingMedium))
                Text(
                    stringResource(if (state.loading) R.string.today_loading_hint
                        else if (state.failure != null) R.string.today_error_hint
                        else if (feed == null) {
                            if (query.isNullOrBlank()) R.string.search_input_hint else R.string.search_empty_hint
                        } else R.string.today_empty_hint),
                    style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant,
                )
                if (!state.loading && (feed != null || state.failure != null)) {
                    Spacer(Modifier.height(d.spacingLarge))
                    ThemeButton(
                        stringResource(R.string.retry), onRefresh,
                        Modifier.width(d.buttonWidth).height(d.navigationButtonHeight)
                            .focusRequester(retryFocus),
                    )
                }
            }
        } else {
            if (state.failure != null || state.cached || state.loading) {
                Text(
                    stringResource(if (state.failure != null) {
                        strings.cachedError
                    } else if (state.loading) R.string.today_updating
                    else strings.cached),
                    color = c.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                )
            }
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
                    key = { _, item -> (feed?.name ?: "SEARCH") + "|" + item.key },
                    contentType = { _, _ -> "series" },
                ) { index, item ->
                    val requester = cards.getOrPut((feed?.name ?: "SEARCH") + "|" + item.key) { FocusRequester() }
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
) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(d.cardCornerRadius)
    Column(
        Modifier.width(d.cardWidth).height(d.cardHeight).focusRequester(requester)
            .focusProperties {
                this.up = up
                this.right = right
                if (left != null) this.left = left
            }
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .border(d.focusBorderWidth, if (focused) c.primary else Color.Transparent, shape)
            .background(c.surface, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(d.cardPadding),
    ) {
        if (contentReady()) {
            Poster(entry, Modifier.fillMaxWidth().height(d.thumbnailHeight))
            Spacer(Modifier.height(d.spacingSmall))
            Text(entry.title, style = MaterialTheme.typography.labelMedium, color = c.onSurface,
                minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(entry.subtitle.ifBlank { entry.season?.let { stringResource(R.string.season_number, it) }.orEmpty() }, style = MaterialTheme.typography.labelSmall, color = c.onSurface,
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
private fun SeriesDialog(
    entry: SeriesEntry,
    description: DescriptionState,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onInteraction: () -> Unit,
) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val descriptionFocus = remember { FocusRequester() }
    val paragraphs = when {
        description.key != entry.key || description.loading -> listOf(stringResource(R.string.description_loading))
        description.failed -> listOf(stringResource(R.string.description_error))
        description.description?.paragraphs.isNullOrEmpty() -> listOf(stringResource(R.string.description_empty))
        else -> description.description.paragraphs
    }
    val scroll = androidx.compose.foundation.rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(androidx.compose.ui.platform.LocalDensity.current) { d.navigationButtonHeight.toPx() }
    var textFocused by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onBack)
        Column(
            Modifier.width(d.dialogWidth).height(d.dialogHeight)
                .backNavigationKeys(onBack, onInteraction)
                .background(c.background, RoundedCornerShape(d.cardCornerRadius))
                .padding(d.spacingLarge),
            verticalArrangement = Arrangement.spacedBy(d.spacingMedium),
        ) {
            Text(entry.fullTitle, color = c.onBackground, style = MaterialTheme.typography.headlineSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(entry.subtitle.ifBlank { entry.season?.let { stringResource(R.string.season_number, it) }.orEmpty() }, color = c.primary, style = MaterialTheme.typography.bodyLarge)
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .focusRequester(descriptionFocus)
                    .onFocusChanged { textFocused = it.isFocused }
                    .border(d.focusBorderWidth, if (textFocused) c.primary else Color.Transparent,
                        RoundedCornerShape(d.cardCornerRadius))
                    .onPreviewKeyEvent {
                        if (it.type != KeyEventType.KeyDown) false
                        else when (it.key) {
                            Key.DirectionDown -> {
                                if (scroll.value >= scroll.maxValue) false
                                else { scope.launch { scroll.scrollBy(step) }; true }
                            }
                            Key.DirectionUp -> {
                                scope.launch { scroll.scrollBy(-step) }
                                true
                            }
                            else -> false
                        }
                    }
                    .focusable()
                    .verticalScroll(scroll)
                    .padding(d.spacingSmall),
            ) {
                Text(paragraphs.joinToString("\n\n"), color = c.onBackground,
                    style = MaterialTheme.typography.bodyLarge)
            }
            if (description.failed) {
                ThemeButton(stringResource(R.string.retry), onRetry,
                    Modifier.width(d.buttonWidth).height(d.navigationButtonHeight))
            }
        }
    }
    LaunchedEffect(Unit) { descriptionFocus.requestFocus() }
}

@Composable
private fun SettingsPane(
    bypassEnabled: Boolean,
    toggleFocus: FocusRequester,
    menuFocus: FocusRequester,
    onBypassChange: (Boolean) -> Unit,
    dnsProvider: DnsProvider,
    onDnsChange: (DnsProvider) -> Unit,
    onBack: () -> Unit,
    onCloseDialog: () -> Unit,
    onInteraction: () -> Unit,
) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    var dnsDialogOpen by rememberSaveable { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var dnsFocused by remember { mutableStateOf(false) }
    val dnsFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium, color = c.onBackground)
        Spacer(Modifier.height(d.spacingLarge))
        Text(stringResource(R.string.network_title),
            style = MaterialTheme.typography.titleLarge, color = c.onBackground)
        Spacer(Modifier.height(d.spacingMedium))
        Row(
            Modifier.fillMaxWidth().focusRequester(toggleFocus)
                .focusProperties {
                    left = menuFocus
                    up = FocusRequester.Cancel
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
                    up = toggleFocus
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
    if (dnsDialogOpen) {
        DnsDialog(
            dnsProvider,
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
private fun DnsDialog(
    selected: DnsProvider,
    onSelect: (DnsProvider) -> Unit,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onInteraction: () -> Unit,
) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val options = remember { DnsProvider.entries.associateWith { FocusRequester() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onBack)
        Column(
            Modifier.width(d.dialogWidth).heightIn(max = d.dialogHeight)
                .backNavigationKeys(onBack, onInteraction)
                .background(c.background, RoundedCornerShape(d.cardCornerRadius))
                .padding(d.spacingLarge)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(d.spacingMedium),
        ) {
            Text(stringResource(R.string.dns_title), color = c.onBackground,
                style = MaterialTheme.typography.headlineMedium)
            DnsProvider.entries.forEach { provider ->
                var focused by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = d.navigationButtonHeight)
                        .focusRequester(options.getValue(provider))
                        .onFocusChanged { focused = it.isFocused }
                        .border(d.focusBorderWidth, if (focused) c.primary else Color.Transparent,
                            RoundedCornerShape(d.cardCornerRadius))
                        .selectable(selected = selected == provider, role = Role.RadioButton,
                            onClick = { onInteraction(); onSelect(provider) })
                        .padding(d.spacingMedium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(dnsLabel(provider)), color = c.onBackground,
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(d.spacingMedium))
                    RadioButton(selected = selected == provider, onClick = null)
                }
            }
        }
    }
    LaunchedEffect(Unit) { options.getValue(selected).requestFocus() }
}

private fun dnsLabel(provider: DnsProvider): Int = when (provider) {
    DnsProvider.SYSTEM -> R.string.dns_system
    DnsProvider.QUAD9_DOH -> R.string.dns_quad9
}

@Composable
private fun AboutPane() {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
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
