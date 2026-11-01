package ru.seasonvar.tv.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.*
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import ru.seasonvar.tv.BuildConfig
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.TodayEntry
import ru.seasonvar.tv.data.network.DnsProvider
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import ru.seasonvar.tv.ui.today.DescriptionState
import ru.seasonvar.tv.ui.today.LoadFailure
import ru.seasonvar.tv.ui.today.TodayUiState
import java.time.format.DateTimeFormatter

private enum class Section { TODAY, SETTINGS, ABOUT }

@Composable
fun SeasonvarApp(
    state: TodayUiState,
    description: DescriptionState,
    onRefresh: () -> Unit,
    onSelect: (TodayEntry, Boolean) -> Unit,
    bypassEnabled: Boolean = true,
    onBypassChange: (Boolean) -> Unit = {},
    dnsProvider: DnsProvider = DnsProvider.QUAD9_DOH,
    onDnsChange: (DnsProvider) -> Unit = {},
) {
    var section by rememberSaveable { mutableStateOf(Section.TODAY) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var networkSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    var contentFocused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val todayFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val networkFocus = remember { FocusRequester() }
    val toggleFocus = remember { FocusRequester() }
    val aboutFocus = remember { FocusRequester() }
    val refreshFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val aboutBackFocus = remember { FocusRequester() }
    val cards = remember { mutableMapOf<String, FocusRequester>() }
    val scope = rememberCoroutineScope()
    val selected = remember(state.entries, selectedKey) {
        state.entries.firstOrNull { it.key == selectedKey } ?: state.entries.firstOrNull()
    }
    LaunchedEffect(state.entries) {
        if (selectedKey != null && state.entries.none { it.key == selectedKey }) detailsOpen = false
        if (state.entries.none { it.key == selectedKey }) selectedKey = selected?.key
    }
    LaunchedEffect(selected?.key) { selected?.let { onSelect(it, false) } }
    LaunchedEffect(Unit) { todayFocus.requestFocus() }

    fun enterContent() {
        scope.launch {
            repeat(2) { withFrameNanos { } }
            if (section == Section.SETTINGS) {
                if (networkSettingsOpen) toggleFocus.requestFocus() else networkFocus.requestFocus()
            } else if (section == Section.ABOUT) {
                aboutBackFocus.requestFocus()
            } else if (selected == null) {
                if (state.failure != null) retryFocus.requestFocus() else refreshFocus.requestFocus()
            } else {
                val index = state.entries.indexOfFirst { it.key == selected.key }
                if (listState.layoutInfo.visibleItemsInfo.none { it.key == selected.key }) {
                    listState.scrollToItem(index.coerceAtLeast(0))
                    repeat(2) { withFrameNanos { } }
                }
                cards[selected.key]?.requestFocus()
            }
        }
    }

    fun closeNetworkSettings() {
        networkSettingsOpen = false
        scope.launch { repeat(2) { withFrameNanos { } }; networkFocus.requestFocus() }
    }
    BackHandler(enabled = contentFocused || section != Section.TODAY) {
        if (section == Section.SETTINGS && networkSettingsOpen) {
            closeNetworkSettings()
        } else {
            section = Section.TODAY
            todayFocus.requestFocus()
        }
    }
    SeasonvarTheme {
        val d = SeasonvarTheme.dimensions
        Row(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .padding(d.screenPaddingHorizontal, d.screenPaddingVertical),
            horizontalArrangement = Arrangement.spacedBy(d.contentGap),
        ) {
            Column(Modifier.width(d.menuWidth)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(d.spacingSection))
                listOf(Section.TODAY, Section.SETTINGS, Section.ABOUT).forEach { item ->
                    val focus = when (item) {
                        Section.TODAY -> todayFocus
                        Section.SETTINGS -> settingsFocus
                        Section.ABOUT -> aboutFocus
                    }
                    val label = when (item) {
                        Section.TODAY -> R.string.today_title
                        Section.SETTINGS -> R.string.settings_title
                        Section.ABOUT -> R.string.about_title
                    }
                    ThemeButton(
                        stringResource(label),
                        onClick = { section = item; enterContent() },
                        modifier = Modifier.fillMaxWidth().height(d.navigationButtonHeight)
                            .focusRequester(focus)
                            .onPreviewKeyEvent {
                                if (it.key == Key.DirectionRight && it.type == KeyEventType.KeyDown) {
                                    section = item
                                    enterContent()
                                    true
                                } else false
                            },
                        active = section == item,
                    )
                    Spacer(Modifier.height(d.spacingSmall))
                }
            }
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .onFocusChanged { contentFocused = it.hasFocus }.focusGroup()
            ) {
                if (section == Section.SETTINGS) {
                    SettingsPane(
                        networkSettingsOpen, bypassEnabled, networkFocus, toggleFocus,
                        onOpenNetwork = {
                            networkSettingsOpen = true
                            scope.launch { repeat(2) { withFrameNanos { } }; toggleFocus.requestFocus() }
                        },
                        onBack = { closeNetworkSettings() },
                        onBypassChange = onBypassChange,
                        dnsProvider = dnsProvider,
                        onDnsChange = onDnsChange,
                    )
                } else if (section == Section.ABOUT) {
                    AboutPane(aboutBackFocus) { section = Section.TODAY; todayFocus.requestFocus() }
                } else {
                    TodayPane(
                        state, description, selected, listState, cards,
                        todayFocus, refreshFocus, retryFocus,
                        onRefresh = onRefresh,
                        onFocused = { selectedKey = it.key },
                        onOpen = { selectedKey = it.key; detailsOpen = true },
                    )
                }
            }
        }
        if (detailsOpen && selected != null) {
            SeriesDialog(
                selected, description,
                onRetry = { onSelect(selected, true) },
                onClose = { detailsOpen = false; enterContent() },
            )
        }
    }
}

@Composable
private fun ThemeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val c = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = modifier.focusRequester(focus).onFocusChanged { focused = it.isFocused }
            .pointerInput(onClick) { detectTapGestures { focus.requestFocus(); onClick() } },
        colors = ButtonDefaults.colors(
            containerColor = if (focused) c.primary else if (active) c.surface else c.background,
            contentColor = if (focused) c.onPrimary else c.onSurface,
            focusedContainerColor = c.primary,
            focusedContentColor = c.onPrimary,
        ),
    ) { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun TodayPane(
    state: TodayUiState,
    description: DescriptionState,
    selected: TodayEntry?,
    listState: LazyListState,
    cards: MutableMap<String, FocusRequester>,
    todayFocus: FocusRequester,
    refreshFocus: FocusRequester,
    retryFocus: FocusRequester,
    onRefresh: () -> Unit,
    onFocused: (TodayEntry) -> Unit,
    onOpen: (TodayEntry) -> Unit,
) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val date = remember(state.date) { state.date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.today_title), style = MaterialTheme.typography.headlineSmall,
                    color = c.onBackground)
                Text(
                    if (state.loaded) stringResource(R.string.today_date_count, date, state.entries.size) else date,
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                )
            }
            ThemeButton(
                stringResource(R.string.refresh), onRefresh,
                Modifier.width(d.headerButtonWidth).height(d.headerButtonHeight)
                    .focusRequester(refreshFocus),
            )
        }
        Spacer(Modifier.height(d.spacingSmall))
        if (selected == null) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
            ) {
                val message = when {
                    state.loading -> R.string.today_loading
                    state.failure == LoadFailure.MARKUP -> R.string.today_markup_error
                    state.failure == LoadFailure.HTTP -> R.string.today_http_error
                    state.failure != null -> R.string.today_network_error
                    else -> R.string.today_empty
                }
                Text(stringResource(message), style = MaterialTheme.typography.titleLarge,
                    color = c.onBackground)
                Spacer(Modifier.height(d.spacingMedium))
                Text(
                    stringResource(if (state.loading) R.string.today_loading_hint
                        else if (state.failure != null) R.string.today_error_hint else R.string.today_empty_hint),
                    style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant,
                )
                if (!state.loading) {
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
                    stringResource(if (state.failure != null) R.string.today_cached_error
                        else if (state.loading) R.string.today_updating else R.string.today_cached),
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
                    key = { _, item -> item.key },
                    contentType = { _, _ -> "today-series" },
                ) { index, item ->
                    val requester = cards.getOrPut(item.key) { FocusRequester() }
                    SeriesThumbnail(
                        item, requester, refreshFocus,
                        if (index == 0) todayFocus else null,
                        onFocused = { onFocused(item) }, onClick = { onOpen(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedPanel(entry: TodayEntry, description: DescriptionState, modifier: Modifier) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.headlineSmall, color = c.onBackground,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(d.spacingSmall))
            Text(entry.update, style = MaterialTheme.typography.bodyMedium, color = c.primary,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            entry.season?.let {
                Text(stringResource(R.string.season_number, it), color = c.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(d.spacingSmall))
            val text = when {
                description.key != entry.key || description.loading -> stringResource(R.string.description_loading)
                description.failed -> stringResource(R.string.description_error)
                description.description?.paragraphs.isNullOrEmpty() -> stringResource(R.string.description_empty)
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
    entry: TodayEntry,
    requester: FocusRequester,
    up: FocusRequester,
    left: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(d.cardCornerRadius)
    Column(
        Modifier.width(d.cardWidth).focusRequester(requester)
            .focusProperties { this.up = up; if (left != null) this.left = left }
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .border(d.focusBorderWidth, if (focused) c.primary else Color.Transparent, shape)
            .background(c.surface, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(d.cardPadding),
    ) {
        Poster(entry, Modifier.fillMaxWidth().height(d.thumbnailHeight))
        Spacer(Modifier.height(d.spacingSmall))
        Text(entry.title, style = MaterialTheme.typography.labelMedium, color = c.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(entry.update, style = MaterialTheme.typography.labelSmall, color = c.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Poster(entry: TodayEntry, modifier: Modifier) {
    val placeholder = painterResource(R.drawable.poster_placeholder)
    AsyncImage(
        model = entry.posterUrl,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        placeholder = placeholder, error = placeholder,
    )
}

@Composable
private fun SeriesDialog(
    entry: TodayEntry,
    description: DescriptionState,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val closeFocus = remember { FocusRequester() }
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
        Column(
            Modifier.width(d.dialogWidth).height(d.dialogHeight)
                .background(c.background, RoundedCornerShape(d.cardCornerRadius))
                .padding(d.spacingLarge),
            verticalArrangement = Arrangement.spacedBy(d.spacingMedium),
        ) {
            ThemeButton(stringResource(R.string.back), onClose,
                Modifier.width(d.headerButtonWidth).height(d.headerButtonHeight)
                    .focusRequester(closeFocus))
            Text(entry.title, color = c.onBackground, style = MaterialTheme.typography.headlineSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(entry.update, color = c.primary, style = MaterialTheme.typography.bodyLarge)
            Box(
                Modifier.weight(1f).fillMaxWidth()
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
                                if (scroll.value == 0) closeFocus.requestFocus()
                                else scope.launch { scroll.scrollBy(-step) }
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
    LaunchedEffect(Unit) { closeFocus.requestFocus() }
}

@Composable
private fun SettingsPane(
    networkOpen: Boolean,
    bypassEnabled: Boolean,
    networkFocus: FocusRequester,
    toggleFocus: FocusRequester,
    onOpenNetwork: () -> Unit,
    onBack: () -> Unit,
    onBypassChange: (Boolean) -> Unit,
    dnsProvider: DnsProvider,
    onDnsChange: (DnsProvider) -> Unit,
) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    var dnsDialogOpen by rememberSaveable { mutableStateOf(false) }
    val dnsFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(if (networkOpen) R.string.network_title else R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium, color = c.onBackground,
        )
        Spacer(Modifier.height(d.spacingLarge))
        if (!networkOpen) {
            ThemeButton(
                stringResource(R.string.network_title), onOpenNetwork,
                Modifier.width(d.buttonWidth).height(d.navigationButtonHeight)
                    .focusRequester(networkFocus),
            )
        } else {
            var focused by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().focusRequester(toggleFocus)
                    .onFocusChanged { focused = it.isFocused }
                    .border(d.focusBorderWidth, if (focused) c.primary else Color.Transparent,
                        RoundedCornerShape(d.cardCornerRadius))
                    .toggleable(value = bypassEnabled, role = Role.Checkbox,
                        onValueChange = onBypassChange)
                    .padding(d.spacingMedium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = bypassEnabled, onCheckedChange = null)
                Spacer(Modifier.width(d.spacingMedium))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.bypass_title), color = c.onBackground,
                        style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(d.spacingSmall))
                    Text(stringResource(R.string.bypass_description), color = c.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(d.spacingLarge))
            ThemeButton(
                stringResource(R.string.dns_current, stringResource(dnsLabel(dnsProvider))),
                onClick = { dnsDialogOpen = true },
                Modifier.width(d.buttonWidth).height(d.navigationButtonHeight)
                    .focusRequester(dnsFocus),
            )
            Spacer(Modifier.height(d.spacingLarge))
            Text(stringResource(R.string.network_applies), color = c.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(d.spacingExtraLarge))
            ThemeButton(stringResource(R.string.back), onBack,
                Modifier.width(d.buttonWidth).height(d.navigationButtonHeight))
        }
    }
    if (dnsDialogOpen && networkOpen) {
        DnsDialog(dnsProvider, onDnsChange, onClose = {
            dnsDialogOpen = false
            scope.launch { repeat(2) { withFrameNanos { } }; dnsFocus.requestFocus() }
        })
    }

}

@Composable
private fun DnsDialog(
    selected: DnsProvider,
    onSelect: (DnsProvider) -> Unit,
    onClose: () -> Unit,
) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val options = remember { DnsProvider.entries.associateWith { FocusRequester() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(d.dialogWidth).heightIn(max = d.dialogHeight)
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
                            onClick = { onSelect(provider); onClose() })
                        .padding(d.spacingMedium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected == provider, onClick = null)
                    Spacer(Modifier.width(d.spacingMedium))
                    Text(stringResource(dnsLabel(provider)), color = c.onBackground,
                        style = MaterialTheme.typography.titleLarge)
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
private fun AboutPane(backFocus: FocusRequester, onBack: () -> Unit) {
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
        Spacer(Modifier.height(d.spacingExtraLarge))
        ThemeButton(stringResource(R.string.back), onBack,
            Modifier.width(d.buttonWidth).height(d.buttonHeight).focusRequester(backFocus))
    }
}

@Preview(device = "spec:width=960dp,height=540dp,dpi=320", showBackground = true)
@Composable
private fun TodayPreview() {
    SeasonvarApp(TodayUiState(), DescriptionState(), {}, { _, _ -> })
}
