package ru.seasonvar.tv.ui.details

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.filterNotNull
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.playback.PlaybackRequest
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.ShortMessageEffect
import ru.seasonvar.tv.ui.showShortMessage
import ru.seasonvar.tv.data.comments.CommentRejectedException
import ru.seasonvar.tv.data.comments.SeasonComment
import ru.seasonvar.tv.data.account.SessionExpiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ru.seasonvar.tv.ui.account.AccountEntryDetails
import ru.seasonvar.tv.ui.account.accountProgressValue
import ru.seasonvar.tv.ui.catalog.LoadFailure
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@OptIn(ExperimentalFoundationApi::class)
private object VisibleControlsScrollSpec : BringIntoViewSpec

private val headlineFacts = setOf("Вышел", "Страна", "Жанр", "Ограничение")
private fun isRating(label: String) = label.equals("IMDB", ignoreCase = true) || label.equals("КиноПоиск", ignoreCase = true)
private data class CommentEditorTarget(val parent: SeasonComment?, val returnFocus: FocusRequester)

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
    showActors: Boolean = true,
    showComments: Boolean = true,
    onBackdropSource: PosterBackdropPublisher? = null,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val d = SeasonvarTheme.dimensions
    val entry = state.entry ?: sourceEntry
    val preparing = state.loading || state.entry == null
    val navigation = rememberDetailsNavigation()
    val loadComments = model.loadComments.takeIf { showComments }
    val commentFeed = remember(entry.id, loadComments) { SeasonCommentFeed() }
    val commentsFocus = remember { FocusRequester() }
    val writeFocus = remember { FocusRequester() }
    val publishComment = model.publishComment.takeIf { loadComments != null && !preparing }
    val account by model.commentAccount.collectAsStateWithLifecycle()
    val drafts = remember(entry.id, account.version) { mutableMapOf<String?, CommentDraft>() }
    var editor by remember(entry.id, account.version) { mutableStateOf<CommentEditorTarget?>(null) }
    var sending by remember(entry.id, account.version) { mutableStateOf(false) }
    var sendFailure by remember(entry.id, account.version) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val rejectedMessage = stringResource(R.string.comment_rejected)
    val loginMessage = stringResource(R.string.comment_login_required)
    val failedMessage = stringResource(R.string.comment_send_failed)
    val commentsReady = commentFeed.entries.isNotEmpty() || commentFeed.failed || publishComment != null
    val afterDescription = when {
        commentFeed.entries.isNotEmpty() || commentFeed.failed -> commentsFocus
        publishComment != null -> writeFocus
        else -> FocusRequester.Cancel
    }
    fun writeComment(parent: SeasonComment?, returnFocus: FocusRequester) {
        onInteraction()
        if (account.user == null) { showShortMessage(context, R.string.comment_login_required); return }
        drafts.getOrPut(parent?.id) { CommentDraft() }
        sendFailure = null
        editor = CommentEditorTarget(parent, returnFocus)
    }
    fun closeEditor(target: CommentEditorTarget) {
        editor = null
        scope.launch {
            repeat(2) { withFrameNanos { } }
            runCatching { target.returnFocus.requestFocus() }
        }
    }
    val episodes = state.episodes.orEmpty()
    val beforeDescription = when {
        episodes.size > SeasonvarTheme.allEpisodesThreshold -> navigation.allEpisodes
        episodes.isNotEmpty() -> navigation.selectedEpisodeFocus
        else -> navigation.play
    }
    LaunchedEffect(commentFeed, preparing) {
        if (preparing || loadComments == null) return@LaunchedEffect
        snapshotFlow {
            val page = commentFeed.next
            val lastVisible = navigation.scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            page.takeIf { !commentFeed.loading && !commentFeed.failed &&
                (page == 1 || lastVisible >= commentFeed.entries.size - 2) }
        }.filterNotNull().collect { page -> commentFeed.append(page, entry, loadComments) }
    }
    val posterImage = remember(entry.posterUrl) { mutableStateOf<Bitmap?>(null) }
    val backdropEnabled = SeasonvarTheme.posterBackdropEnabled
    if (backdropEnabled && onBackdropSource != null) {
        DisposableEffect(entry.posterUrl, posterImage, onBackdropSource) {
            val handle = onBackdropSource(PosterBackdropSource(entry.posterUrl, posterImage))
            onDispose { handle.dispose() }
        }
    }
    ShortMessageEffect(if (state.progressFailure != null) stringResource(R.string.account_progress_error) else null)
    val actors = if (showActors) state.details?.actors.orEmpty() else emptyList()
    val actorFocus = remember(actors) { actors.map { FocusRequester() } }
    val actorListState = key(entry.id) { rememberLazyListState() }
    val actorIndex = remember(entry.id) { mutableIntStateOf(0) }
    LaunchedEffect(sourceEntry.key) { model.open(sourceEntry) }
    DisposableEffect(model) { onDispose { model.close() } }
    DetailsFocusEffects(navigation, entry.id, state.translationId, preparing, SeasonvarTheme.interfaceTheme,
        removalDialogOpen, !preparing && onActions != null)
    val density = LocalDensity.current
    CompositionLocalProvider(LocalBringIntoViewSpec provides VisibleControlsScrollSpec) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (backdropEnabled && onBackdropSource == null) {
                PosterBackdrop(entry.posterUrl, posterImage, with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) })
            }
            val step = with(density) { (maxHeight - d.spacingSection).coerceAtLeast(d.navigationButtonHeight).toPx() }
            fun actionScrolling(origin: FocusRequester) = navigation.actionScrolling(origin, actorListState,
                actorFocus, actorIndex, step, episodes.isEmpty(), commentsReady, onInteraction)
            LazyColumn(Modifier.fillMaxSize().detailsScrollbar(navigation.scroll).then(navigation.pageScrolling(step)),
                state = navigation.scroll, contentPadding = PaddingValues(d.focusBorderWidth)) {
                item(key = "season") {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
                        SeriesInformation(state, entry, Modifier.fillMaxWidth(), showActors, actorListState, actorFocus,
                            navigation.actorReturnFocus, menuFocus, onPosterLoaded = { posterImage.value = it }) { actorIndex.intValue = it; onInteraction() }
                        SeriesDetailsActions(state, entry, model, navigation, menuFocus, preparing,
                            if (episodes.isEmpty()) afterDescription else navigation.selectedEpisodeFocus,
                            onActions, onPlay, onInteraction, ::actionScrolling)
                        SeasonProgress(state, entry)
                        EpisodeList(state, model, navigation.selectedEpisodeFocus, navigation.allEpisodes, menuFocus,
                            up = when {
                                (state.details?.translations?.size ?: 0) > 1 -> navigation.translation
                                (state.details?.seasons?.size ?: 0) > 1 -> navigation.season
                                else -> navigation.play
                            }, preparing = preparing, onInteraction = onInteraction, onPlay = onPlay,
                            allModifier = Modifier.bringIntoViewRequester(navigation.allEpisodesView),
                            afterDescription = afterDescription,
                            onReadDescription = { navigation.readDescription(it, step, commentsReady) },
                            onAllEpisodes = { navigation.show(DetailsDialog.EPISODES, navigation.allEpisodes, onInteraction) })
                        SeriesSynopsis(state)
                    }
                }
                val firstComment = commentFeed.entries.firstOrNull()
                if (publishComment != null || firstComment != null) item(key = "comments-start") {
                    SeasonCommentsHeading(writeFocus, commentsFocus.takeIf { firstComment != null || commentFeed.failed },
                        { navigation.returnFromComments(beforeDescription) },
                        if (publishComment != null) ({ writeComment(null, writeFocus) }) else null)
                    if (firstComment != null) key(firstComment.id) {
                        SeasonCommentRow(firstComment, commentsFocus, {
                            if (publishComment != null) writeFocus.requestFocus()
                            else navigation.returnFromComments(beforeDescription)
                        }, if (publishComment != null) ({ focus -> writeComment(firstComment, focus) }) else null)
                    }
                }
                items(commentFeed.entries.drop(1), key = { comment -> "${entry.id}:${comment.id}" }) { comment ->
                    SeasonCommentRow(comment, null, null,
                        if (publishComment != null) ({ focus -> writeComment(comment, focus) }) else null)
                }
                if (commentFeed.loading || commentFeed.failed) item(key = "comments-status") {
                    if (commentFeed.failed) {
                        val first = commentFeed.entries.isEmpty()
                        ThemeButton(stringResource(R.string.comments_retry), commentFeed::retry,
                            if (first) Modifier.focusRequester(commentsFocus).returnFromFirstComment {
                                if (publishComment != null) writeFocus.requestFocus()
                                else navigation.returnFromComments(beforeDescription)
                            } else Modifier)
                    } else Text(stringResource(R.string.comments_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    SeriesDetailsDialogs(state, entry, model, navigation, onInteraction, onPlay)
    val target = editor
    if (target != null && publishComment != null) {
        val parentId = target.parent?.id
        val draft = checkNotNull(drafts[parentId])
        CommentEditorDialog(target.parent, draft, sending, sendFailure, { closeEditor(target) }, onSend = {
            val text = draft.text()
            if (!sending && text.isNotBlank()) {
                sending = true
                sendFailure = null
                val ownerVersion = account.version
                scope.launch {
                    try {
                        publishComment(entry, ownerVersion, parentId, text)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        sendFailure = when (error) {
                            is CommentRejectedException -> error.reason ?: rejectedMessage
                            is SessionExpiredException -> loginMessage
                            else -> failedMessage
                        }
                        return@launch
                    } finally {
                        sending = false
                    }
                    drafts.remove(parentId)
                    closeEditor(target)
                    showShortMessage(context, R.string.comment_sent)
                    model.refreshComments?.let { refresh ->
                        try { commentFeed.refresh(parentId, entry, refresh) }
                        catch (error: CancellationException) { throw error }
                        catch (_: Exception) { showShortMessage(context, R.string.comment_refresh_failed) }
                    }
                }
            }
        })
    }
}

@Composable
private fun SeasonCommentsHeading(writeFocus: FocusRequester, first: FocusRequester?, returnToSeason: () -> Unit,
    onWrite: (() -> Unit)?) {
    val d = SeasonvarTheme.dimensions
    Row(Modifier.fillMaxWidth().padding(start = d.spacingSmall * 2, top = d.spacingLarge, end = d.spacingSmall),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.comments_heading), Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        if (onWrite != null) {
            ThemeButton(stringResource(R.string.comment_write), onWrite,
                Modifier.height(d.headerButtonSize).focusRequester(writeFocus)
                    .focusProperties { down = first ?: FocusRequester.Cancel }.returnFromFirstComment(returnToSeason),
                showOutline = false)
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
private fun SeriesInformation(state: DetailsUiState, entry: SeriesEntry, modifier: Modifier, showActors: Boolean,
    actorListState: LazyListState, actorFocus: List<FocusRequester>, actorReturnFocus: FocusRequester,
    menuFocus: FocusRequester, onPosterLoaded: (Bitmap) -> Unit, onActorFocused: (Int) -> Unit) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val details = state.details
    Column(modifier, verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
        Row(horizontalArrangement = Arrangement.spacedBy(d.spacingMedium), verticalAlignment = Alignment.Top) {
            AsyncImage(entry.posterUrl, null, Modifier.width(d.posterWidth).height(d.posterHeight), contentScale = ContentScale.Fit,
                onSuccess = { (it.result.drawable as? BitmapDrawable)?.bitmap?.let(onPosterLoaded) })
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
        if (showActors) SeriesActorsRow(details?.actors.orEmpty(), actorListState, actorFocus, actorReturnFocus, menuFocus, onActorFocused)
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
    afterDescription: FocusRequester,
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
                                    down = if (showAllEpisodes) allFocus else afterDescription
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
                    this.up = selectedFocus; down = afterDescription
                })
    }
}

@Composable
private fun Modifier.detailsScrollbar(scroll: LazyListState): Modifier {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    return drawWithContent {
        drawContent()
        val indicator = scroll.scrollIndicatorState ?: return@drawWithContent
        val range = indicator.contentSize - indicator.viewportSize
        if (range > 0 && indicator.contentSize != Int.MAX_VALUE) {
            val width = d.focusBorderWidth.toPx()
            val x = size.width - width
            val thumb = maxOf(d.spacingMedium.toPx(), size.height * indicator.viewportSize / indicator.contentSize)
            val y = (size.height - thumb) * indicator.scrollOffset.coerceIn(0, range) / range
            drawLine(c.onSurfaceVariant.copy(alpha = 0.3f), Offset(x, 0f), Offset(x, size.height), width)
            drawLine(c.onSurfaceVariant, Offset(x, y), Offset(x, y + thumb), width)
        }
    }
}
