package ru.seasonvar.tv.ui.details

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.RadioButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.playback.PlaybackRequest
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.ShortMessageEffect
import ru.seasonvar.tv.ui.account.AccountEntryDetails
import ru.seasonvar.tv.ui.account.accountProgressValue
import ru.seasonvar.tv.ui.catalog.LoadFailure
import ru.seasonvar.tv.ui.navigation.backNavigationKeys
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@OptIn(ExperimentalFoundationApi::class)
private object VisibleControlsScrollSpec : BringIntoViewSpec

private enum class DetailsDialog { SEASONS, TRANSLATIONS, EPISODES }
private val headlineFacts = setOf("Вышел", "Страна", "Жанр", "Ограничение")
private fun isRating(label: String) = label.equals("IMDB", ignoreCase = true) || label.equals("КиноПоиск", ignoreCase = true)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SeriesDetailsPane(
    sourceEntry: SeriesEntry,
    model: SeriesDetailsViewModel,
    menuFocus: FocusRequester,
    onInteraction: () -> Unit,
    onPlay: (PlaybackRequest) -> Unit,
    onActions: ((SeriesEntry) -> Unit)? = null,
    removalDialogOpen: Boolean = false,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val entry = state.entry ?: sourceEntry
    val details = state.details
    val preparing = state.loading || state.entry == null
    ShortMessageEffect(if (state.progressFailure != null) stringResource(R.string.account_progress_error) else null)
    val selectedEpisodeFocus = remember { FocusRequester() }
    val play = remember { FocusRequester() }
    val actors = details?.actors.orEmpty()
    val actorFocus = remember(actors) { actors.map { FocusRequester() } }
    val actorListState = key(entry.id) { rememberLazyListState() }
    var actorIndex by remember(entry.id) { mutableIntStateOf(0) }
    var actorReturnFocus by remember { mutableStateOf(play) }
    val actionsMenu = remember { FocusRequester() }
    var restoreActions by remember { mutableStateOf(false) }
    val canOpenActions = !preparing && onActions != null
    val season = remember { FocusRequester() }
    val translation = remember { FocusRequester() }
    val allEpisodes = remember { FocusRequester() }
    val actions = remember { BringIntoViewRequester() }
    val allEpisodesView = remember { BringIntoViewRequester() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var descriptionAnchor by remember { mutableStateOf<Int?>(null) }
    var revealActionsAfterLoad by remember { mutableStateOf(true) }
    var dialog by remember { mutableStateOf<DetailsDialog?>(null) }
    var restoreFocus by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(sourceEntry.key) { model.open(sourceEntry) }
    DisposableEffect(model) { onDispose { model.close() } }
    LaunchedEffect(Unit) { play.requestFocus() }
    LaunchedEffect(entry.id, preparing) {
        if (!preparing) {
            repeat(2) { withFrameNanos { } }
            if (revealActionsAfterLoad) {
                actions.bringIntoView()
                play.requestFocus()
                revealActionsAfterLoad = false
            }
            descriptionAnchor = null
        }
    }
    LaunchedEffect(entry.id, state.translationId) { descriptionAnchor = null }
    LaunchedEffect(dialog) {
        if (dialog == null) restoreFocus?.let { focus ->
            repeat(2) { withFrameNanos { } }
            if (focus == allEpisodes) {
                descriptionAnchor = null
                allEpisodesView.bringIntoView()
            }
            focus.requestFocus()
            restoreFocus = null
        }
    }
    LaunchedEffect(removalDialogOpen) {
        if (!removalDialogOpen && restoreActions) {
            repeat(2) { withFrameNanos { } }
            if (canOpenActions) actionsMenu.requestFocus() else play.requestFocus()
            restoreActions = false
        }
    }
    fun dismiss() { onInteraction(); dialog = null }
    fun show(which: DetailsDialog, focus: FocusRequester) {
        onInteraction(); restoreFocus = focus; dialog = which
    }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalBringIntoViewSpec provides VisibleControlsScrollSpec) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val step = with(density) { (maxHeight - d.spacingSection).coerceAtLeast(d.navigationButtonHeight).toPx() }
            fun actionScrolling(origin: FocusRequester) = Modifier.onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) false else when {
                    it.key == Key.DirectionUp -> {
                        onInteraction()
                        if (actors.isNotEmpty()) {
                            actorReturnFocus = origin
                            scope.launch {
                                scroll.scrollTo(0)
                                val index = actorIndex.coerceIn(actors.indices)
                                if (actorListState.layoutInfo.visibleItemsInfo.none { item -> item.index == index }) {
                                    actorListState.scrollToItem(index)
                                }
                                withFrameNanos { }
                                actorFocus[index].requestFocus()
                            }
                        } else scope.launch { scroll.scrollBy(-step) }
                        true
                    }
                    it.key == Key.DirectionDown && state.episodes.isNullOrEmpty() -> {
                        scope.launch { scroll.scrollBy(step) }; true
                    }
                    else -> false
                }
            }
            Column(Modifier.fillMaxSize().detailsScrollbar(scroll)
                .onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                        Key.PageDown -> { scope.launch { scroll.scrollBy(step) }; true }
                        Key.PageUp -> { scope.launch { scroll.scrollBy(-step) }; true }
                        else -> false
                    }
                }.verticalScroll(scroll).padding(d.focusBorderWidth),
                verticalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
                SeriesInformation(state, entry, Modifier.fillMaxWidth(), actorListState, actorFocus,
                    actorReturnFocus, menuFocus) { actorIndex = it; onInteraction() }
                FlowRow(Modifier.fillMaxWidth().bringIntoViewRequester(actions),
                    horizontalArrangement = Arrangement.spacedBy(d.spacingSmall),
                    verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                    ThemeButton(stringResource(when {
                        state.failure != null -> R.string.retry
                        state.selectionFailure != null -> R.string.details_choose_episode
                        !preparing && !state.canPlay -> R.string.details_reload
                        state.resume != null -> R.string.details_continue
                        else -> R.string.details_watch
                    }), onClick = {
                        onInteraction()
                        when {
                            state.failure != null -> model.retry()
                            state.selectionFailure != null -> show(DetailsDialog.EPISODES, play)
                            !state.canPlay -> model.retry()
                            else -> model.playbackRequest()?.let(onPlay)
                        }
                    }, loading = preparing,
                        modifier = Modifier.height(d.headerButtonSize).then(actionScrolling(play)).focusRequester(play).focusProperties {
                            left = menuFocus; up = FocusRequester.Cancel
                            right = when {
                                (details?.seasons?.size ?: 0) > 1 -> season
                                (details?.translations?.size ?: 0) > 1 -> translation
                                canOpenActions -> actionsMenu
                                else -> FocusRequester.Cancel
                            }
                            down = if (!state.episodes.isNullOrEmpty()) selectedEpisodeFocus else FocusRequester.Cancel
                        })
                    if (details != null) {
                        val currentSeason = details.seasons.firstOrNull { it.id == entry.id }
                        val seasonLabel = if (currentSeason?.number != null) currentSeason.label
                            else entry.season?.let { stringResource(R.string.season_number, it) }
                                ?: stringResource(R.string.details_season)
                        if (details.seasons.size > 1) ThemeButton(seasonLabel + " ▾",
                            { show(DetailsDialog.SEASONS, season) }, Modifier.height(d.headerButtonSize).then(actionScrolling(season))
                                .focusRequester(season).focusProperties {
                                    left = play; up = FocusRequester.Cancel
                                    right = if (details.translations.size > 1) translation else if (canOpenActions) actionsMenu else FocusRequester.Cancel
                                    down = if (!state.episodes.isNullOrEmpty()) selectedEpisodeFocus else FocusRequester.Cancel
                                })
                        else if (currentSeason?.number != null || entry.season != null) Text(seasonLabel, Modifier.heightIn(min = d.headerButtonSize).wrapContentHeight(),
                            color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        if (details.translations.isNotEmpty()) {
                            val label = state.translation?.let { stringResource(R.string.details_translation, it.label) }
                                ?: stringResource(R.string.details_choose_translation)
                            if (details.translations.size > 1 || state.translation == null) ThemeButton(label + " ▾",
                                { show(DetailsDialog.TRANSLATIONS, translation) }, Modifier.height(d.headerButtonSize).then(actionScrolling(translation))
                                    .focusRequester(translation).focusProperties {
                                        up = FocusRequester.Cancel
                                        left = if (details.seasons.size > 1) season else play
                                        right = if (canOpenActions) actionsMenu else FocusRequester.Cancel
                                        down = if (!state.episodes.isNullOrEmpty()) selectedEpisodeFocus else FocusRequester.Cancel
                                    })
                            else Text(label, Modifier.heightIn(min = d.headerButtonSize).wrapContentHeight(),
                                color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (canOpenActions) ThemeButton(stringResource(R.string.series_actions_menu), {
                        onInteraction(); restoreActions = true
                        onActions?.invoke(entry.copy(initialSelection = ru.seasonvar.tv.data.EntrySelection(
                            episodeId = state.episode?.id, episodeLabel = state.episode?.label,
                            translationId = state.translationId, translationLabel = state.translation?.label)))
                    }, Modifier.then(actionScrolling(actionsMenu)).focusRequester(actionsMenu).focusProperties {
                        up = FocusRequester.Cancel; right = FocusRequester.Cancel
                        left = when { (details?.translations?.size ?: 0) > 1 -> translation; (details?.seasons?.size ?: 0) > 1 -> season; else -> play }
                        down = if (!state.episodes.isNullOrEmpty()) selectedEpisodeFocus else FocusRequester.Cancel
                    }, icon = R.drawable.ic_more_vert)
                }
                SeasonProgress(state, entry)
                EpisodeList(state, model, selectedEpisodeFocus, allEpisodes, menuFocus,
                    up = when {
                        (details?.translations?.size ?: 0) > 1 -> translation
                        (details?.seasons?.size ?: 0) > 1 -> season
                        else -> play
                    }, preparing = preparing, onInteraction = onInteraction, onPlay = onPlay,
                    allModifier = Modifier.bringIntoViewRequester(allEpisodesView),
                    onReadDescription = { forward ->
                        if (forward) {
                            if (descriptionAnchor == null) descriptionAnchor = scroll.value
                            scope.launch { scroll.scrollBy(step) }
                            true
                        } else {
                            val anchor = descriptionAnchor
                            if (anchor != null && scroll.value > anchor) {
                                val distance = minOf(step, (scroll.value - anchor).toFloat())
                                scope.launch { scroll.scrollBy(-distance) }
                                true
                            } else { descriptionAnchor = null; false }
                        }
                    },
                    onAllEpisodes = { show(DetailsDialog.EPISODES, allEpisodes) })
                SeriesSynopsis(state)

            }
        }
    }
    val currentDialog = dialog
    if (currentDialog != null && details != null) {
        Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            BackHandler { dismiss() }
            Column(Modifier.width(d.dialogWidth).height(d.dialogHeight)
                .backNavigationKeys(::dismiss, onInteraction)
                .background(c.background, RoundedCornerShape(d.cardCornerRadius)).padding(d.spacingLarge),
                verticalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
                Text(stringResource(when (currentDialog) {
                    DetailsDialog.SEASONS -> R.string.details_season
                    DetailsDialog.TRANSLATIONS -> R.string.details_translation_title
                    DetailsDialog.EPISODES -> R.string.details_all_episodes
                }), style = MaterialTheme.typography.headlineSmall, color = c.onBackground)
                when (currentDialog) {
                    DetailsDialog.SEASONS -> ChoiceList(details.seasons.map { it.id to it.label }, entry.id) {
                        restoreFocus = play
                        revealActionsAfterLoad = it != entry.id
                        dismiss()
                        model.selectSeason(it)
                    }
                    DetailsDialog.TRANSLATIONS -> ChoiceList(details.translations.map { it.id to it.label }, state.translationId) {
                        dismiss(); model.selectTranslation(it)
                    }
                    DetailsDialog.EPISODES -> {
                        val focus = remember { FocusRequester() }
                        val episodes = state.episodes.orEmpty()
                        val focusId = state.episodeFocusId
                        val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState(
                            initialFirstVisibleItemIndex = episodes.indexOfFirst { it.id == focusId }.coerceAtLeast(0))
                        if (episodes.isEmpty()) Text(stringResource(if (state.episodes == null)
                            R.string.details_episodes_unavailable else R.string.details_episodes_empty),
                            color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        LazyVerticalGrid(GridCells.Adaptive(d.episodeWidth), state = grid,
                            horizontalArrangement = Arrangement.spacedBy(d.spacingSmall),
                            verticalArrangement = Arrangement.spacedBy(d.spacingSmall), contentPadding = PaddingValues(d.focusBorderWidth)) {
                            items(episodes, key = { it.id }) { episode ->
                                ThemeButton(episode.label, onClick = { onInteraction(); model.selectEpisode(episode.id); model.playbackRequest(episode.id)?.let(onPlay) },
                                    Modifier.height(d.headerButtonSize).then(if (episode.id == focusId) Modifier.focusRequester(focus) else Modifier),
                                    active = episode.id == state.episodeId)
                            }
                        }
                        LaunchedEffect(focusId) {
                            if (focusId != null) { repeat(2) { withFrameNanos { } }; focus.requestFocus() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SeasonProgress(state: DetailsUiState, entry: SeriesEntry) {
    val progress = accountProgressValue(entry, compact = true) ?: stringResource(when {
        state.entry == null || state.loading || state.progressLoading -> R.string.details_progress_loading
        else -> R.string.details_progress_unknown
    })
    val resume = state.seasonResume?.takeIf { it.seasonId == entry.id && it.positionMillis > 0 }
    val translation = state.details?.translations?.firstOrNull { it.id == resume?.translationId }
    val episode = translation?.episodes?.firstOrNull { it.id == resume?.episodeId }
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.spacingMedium),
        verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
        Text(stringResource(R.string.account_mark_progress, progress),
            style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        if (resume != null && episode != null) {
            val stopped = stringResource(R.string.details_stopped_at, episode.label,
                "%02d:%02d".format(resume.positionMillis / 60000, resume.positionMillis / 1000 % 60))
            val voice = translation.takeIf { it.id != state.translationId }?.label
            Text(listOfNotNull(stopped, voice).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        }
    }
}

@Composable
private fun SeriesInformation(state: DetailsUiState, entry: SeriesEntry, modifier: Modifier,
    actorListState: LazyListState, actorFocus: List<FocusRequester>, actorReturnFocus: FocusRequester,
    menuFocus: FocusRequester, onActorFocused: (Int) -> Unit) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val details = state.details
    Column(modifier, verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
        Row(horizontalArrangement = Arrangement.spacedBy(d.spacingMedium), verticalAlignment = Alignment.Top) {
            AsyncImage(entry.posterUrl, null, Modifier.width(d.posterWidth).height(d.posterHeight), contentScale = ContentScale.Fit)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                Text(entry.title, color = c.onBackground, style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                entry.originalTitle?.takeIf { it != entry.title }?.let {
                    Text(it, color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                AccountEntryDetails(entry, includeProgress = false)
                details?.facts?.filter { it.first in headlineFacts }?.joinToString(" · ") { it.second }
                    ?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                details?.facts?.filter { isRating(it.first) }?.joinToString(" · ") {
                    (if (it.first.equals("IMDB", ignoreCase = true)) "IMDb" else "КиноПоиск") + ": " + it.second
                }?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = c.primary, style = MaterialTheme.typography.bodyMedium)
                }
                details?.facts?.filterNot { it.first in headlineFacts || it.first == "Оригинал" || isRating(it.first) }?.forEach {
                    Text(it.first + ": " + it.second, color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        SeriesActorsRow(details?.actors.orEmpty(), actorListState, actorFocus, actorReturnFocus, menuFocus, onActorFocused)
    }
}

@Composable
private fun SeriesSynopsis(state: DetailsUiState) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val paragraphs = when {
        state.loading || state.entry == null -> listOf(stringResource(R.string.description_loading))
        state.failure != null -> listOf(stringResource(when (state.failure) {
            LoadFailure.NETWORK -> R.string.today_network_error
            LoadFailure.HTTP -> R.string.today_http_error
            else -> R.string.details_markup_error
        }))
        state.details?.paragraphs.isNullOrEmpty() -> listOf(stringResource(R.string.description_empty))
        else -> state.details.paragraphs
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
        Text(paragraphs.joinToString("\n\n"), color = c.onBackground, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun EpisodeList(
    state: DetailsUiState, model: SeriesDetailsViewModel, selectedFocus: FocusRequester,
    allFocus: FocusRequester, menuFocus: FocusRequester, up: FocusRequester, preparing: Boolean,
    onInteraction: () -> Unit, onAllEpisodes: () -> Unit,
    onPlay: (PlaybackRequest) -> Unit,
    allModifier: Modifier, onReadDescription: (Boolean) -> Boolean,
) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val showAllEpisodes = (state.episodes?.size ?: 0) > SeasonvarTheme.allEpisodesThreshold
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
        key(state.entry?.id, state.translationId) {
            val episodes = state.episodes
            val focusId = state.episodeFocusId
            val list = androidx.compose.foundation.lazy.rememberLazyListState(
                initialFirstVisibleItemIndex = episodes?.indexOfFirst { it.id == focusId }?.coerceAtLeast(0) ?: 0)
            LaunchedEffect(focusId) {
                val index = episodes?.indexOfFirst { it.id == focusId } ?: -1
                if (index >= 0 && list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.scrollToItem(index)
            }
            if (!episodes.isNullOrEmpty()) {
                LazyRow(Modifier.fillMaxWidth().height(d.navigationButtonHeight), state = list,
                    horizontalArrangement = Arrangement.spacedBy(d.spacingSmall), contentPadding = PaddingValues(d.focusBorderWidth)) {
                    itemsIndexed(episodes, key = { _, episode -> episode.id }) { index, episode ->
                        ThemeButton(episode.label, onClick = { onInteraction(); model.selectEpisode(episode.id); model.playbackRequest(episode.id)?.let(onPlay) },
                            modifier = Modifier.widthIn(min = d.episodeWidth).width(IntrinsicSize.Max).height(d.headerButtonSize)
                                .then(if (episode.id == focusId) Modifier.focusRequester(selectedFocus) else Modifier)
                                .onFocusChanged { if (it.isFocused) model.selectEpisode(episode.id) }
                                .onPreviewKeyEvent {
                                    if (showAllEpisodes || it.type != KeyEventType.KeyDown) false
                                    else when (it.key) {
                                        Key.DirectionDown -> onReadDescription(true)
                                        Key.DirectionUp -> onReadDescription(false)
                                        else -> false
                                    }
                                }
                                .focusProperties {
                                    if (index == 0) left = menuFocus
                                    if (index == episodes.lastIndex) right = FocusRequester.Cancel
                                    this.up = up
                                    down = if (showAllEpisodes) allFocus else FocusRequester.Cancel
                                }, active = state.episodeId == episode.id)
                    }
                }
            } else Text(if (preparing) stringResource(R.string.description_loading)
                else state.details?.playbackUnavailableReason
                    ?: stringResource(if (episodes == null) R.string.details_episodes_unavailable else R.string.details_episodes_empty),
                color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        if (showAllEpisodes) ThemeButton(stringResource(R.string.details_all_episodes), onAllEpisodes,
            Modifier.width(IntrinsicSize.Max).height(d.headerButtonSize).then(allModifier).focusRequester(allFocus)
                .onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                        Key.DirectionDown -> onReadDescription(true)
                        Key.DirectionUp -> onReadDescription(false)
                        else -> false
                    }
                }
                .focusProperties {
                    left = menuFocus; right = FocusRequester.Cancel
                    this.up = selectedFocus; down = FocusRequester.Cancel
                })
    }
}

@Composable
private fun Modifier.detailsScrollbar(scroll: ScrollState): Modifier {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    return drawWithContent {
        drawContent()
        if (scroll.maxValue > 0) {
            val width = d.focusBorderWidth.toPx()
            val x = size.width - width
            val thumb = maxOf(d.spacingMedium.toPx(), size.height * size.height / (size.height + scroll.maxValue))
            val y = (size.height - thumb) * scroll.value / scroll.maxValue
            drawLine(c.onSurfaceVariant.copy(alpha = 0.3f), Offset(x, 0f), Offset(x, size.height), width)
            drawLine(c.onSurfaceVariant, Offset(x, y), Offset(x, y + thumb), width)
        }
    }
}

@Composable
private fun ChoiceList(options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(
        initialFirstVisibleItemIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0))
    LazyColumn(Modifier.fillMaxWidth().selectableGroup(), state = listState,
        verticalArrangement = Arrangement.spacedBy(d.spacingSmall), contentPadding = PaddingValues(d.focusBorderWidth)) {
        itemsIndexed(options, key = { _, item -> item.first }) { index, item ->
            var focused by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().heightIn(min = d.navigationButtonHeight)
                .then(if (item.first == selected) Modifier.focusRequester(focus) else Modifier)
                .focusProperties {
                    if (index == 0) up = FocusRequester.Cancel
                    if (index == options.lastIndex) down = FocusRequester.Cancel
                }
                .onFocusChanged { focused = it.isFocused }
                .border(d.focusBorderWidth, if (focused) c.primary else Color.Transparent, RoundedCornerShape(d.buttonCornerRadius))
                .selectable(item.first == selected, role = Role.RadioButton, onClick = { onSelect(item.first) })
                .padding(d.spacingSmall), verticalAlignment = Alignment.CenterVertically) {
                Text(item.second, Modifier.weight(1f), color = c.onBackground, style = MaterialTheme.typography.bodyLarge)
                RadioButton(item.first == selected, onClick = null)
            }
        }
    }
    LaunchedEffect(Unit) { repeat(2) { withFrameNanos { } }; focus.requestFocus() }
}
