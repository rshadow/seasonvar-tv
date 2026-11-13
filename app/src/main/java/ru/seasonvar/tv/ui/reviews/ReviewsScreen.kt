package ru.seasonvar.tv.ui.reviews

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.reviews.SeriesReview
import ru.seasonvar.tv.ui.ShortMessageEffect
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.catalog.LoadFailure
import ru.seasonvar.tv.ui.navigation.backNavigationKeys
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@Composable
internal fun ReviewsScreen(title: String, state: ReviewsUiState, onRefresh: (String?) -> Unit,
    onMore: (Boolean) -> Unit, onExit: () -> Unit) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val rows = remember { mutableMapOf<String, FocusRequester>() }
    val refresh = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    val reader = remember { FocusRequester() }
    val holder = remember { FocusRequester() }
    var holdingFocus by remember { mutableStateOf(true) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = state.entries.firstOrNull { it.key == selectedKey } ?: state.entries.firstOrNull()
    val latestSelected by rememberUpdatedState(selected)
    suspend fun focusSelected() {
        val chosen = latestSelected ?: return
        val index = state.entries.indexOfFirst { it.key == chosen.key }.coerceAtLeast(0)
        if (list.layoutInfo.visibleItemsInfo.none { it.key == chosen.key }) list.scrollToItem(index)
        repeat(2) { withFrameNanos { } }
        rows[chosen.key]?.requestFocus()
    }
    LaunchedEffect(state.entries) {
        if (state.entries.isNotEmpty()) selectedKey = selected?.key
    }
    LaunchedEffect(state.loading, state.loaded, state.failure) {
        if (holdingFocus && !state.loading) {
            if (selected != null) focusSelected() else refresh.requestFocus()
            holdingFocus = false
        }
    }
    LaunchedEffect(Unit) { holder.requestFocus() }
    val lastVisible by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 } }
    LaunchedEffect(lastVisible, selected?.key, state.nextCursor, state.loading) {
        if (lastVisible >= state.entries.size - 2 && state.entries.isNotEmpty() && !state.loading) onMore(false)
    }
    val failure = state.moreFailure ?: state.failure
    ShortMessageEffect(failure?.let { stringResource(when (it) {
        LoadFailure.NETWORK -> R.string.reviews_network_error
        LoadFailure.HTTP -> R.string.reviews_http_error
        LoadFailure.MARKUP -> R.string.reviews_markup_error
    }) })
    BackHandler(onBack = onExit)
    Column(Modifier.fillMaxSize().backNavigationKeys(onExit, {}).background(c.background)
        .padding(d.screenPaddingHorizontal, d.screenPaddingVertical)
        .focusRequester(holder).focusProperties { canFocus = holdingFocus }.focusTarget().focusGroup(),
        verticalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
            Text(stringResource(R.string.reviews_title, title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.onBackground)
            ThemeButton(stringResource(R.string.reviews_refresh), { onRefresh(selected?.key) },
                Modifier.focusRequester(refresh).onPreviewKeyEvent { event ->
                    if (event.key == Key.DirectionDown && selected != null) {
                        if (event.type == KeyEventType.KeyDown) scope.launch { focusSelected() }
                        true
                    } else false
                }.focusProperties {
                    up = FocusRequester.Cancel; right = FocusRequester.Cancel
                    down = if (selected != null) rows.getOrPut(selected.key) { FocusRequester() } else FocusRequester.Cancel
                }, icon = R.drawable.ic_reload, loading = state.loading)
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
            Column(Modifier.weight(d.reviewsListWidthFraction).fillMaxHeight()) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list,
                    contentPadding = PaddingValues(d.focusBorderWidth), verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                    itemsIndexed(state.entries, key = { _, review -> review.key }) { index, review ->
                        val focus = remember(review.key) { rows.getOrPut(review.key) { FocusRequester() } }
                        DisposableEffect(review.key) { onDispose { rows.remove(review.key) } }
                        ReviewRow(review, selected?.key == review.key, Modifier.focusRequester(focus).focusProperties {
                            right = reader; left = FocusRequester.Cancel
                            if (index == 0) up = refresh
                            if (index == state.entries.lastIndex) down = if (state.moreFailure != null) retry else FocusRequester.Cancel
                        }.onFocusChanged { if (it.isFocused) selectedKey = review.key }) { reader.requestFocus() }
                    }
                }
                Row(Modifier.fillMaxWidth().height(d.reviewsStatusHeight), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(when {
                        state.loading -> R.string.reviews_loading
                        state.loadingMore -> R.string.reviews_loading_more
                        failure != null -> R.string.reviews_failed
                        state.entries.isEmpty() -> R.string.reviews_empty
                        state.total != null -> R.string.reviews_loaded_total
                        else -> R.string.reviews_loaded
                    }, state.entries.size, state.total ?: state.entries.size), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.size(d.headerButtonSize)) {
                        if (state.moreFailure != null) ThemeButton(stringResource(R.string.retry), { onMore(true) },
                            Modifier.focusRequester(retry).focusProperties {
                                up = selected?.let { rows[it.key] } ?: refresh; right = if (selected != null) reader else refresh
                            }, icon = R.drawable.ic_reload)
                    }
                }
            }
            Column(Modifier.weight(1f - d.reviewsListWidthFraction).fillMaxHeight()) {
                Column(Modifier.fillMaxWidth().height(d.reviewsMetadataHeight), verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                    Text(selected?.author ?: if (selected != null) stringResource(R.string.reviews_author_unknown) else "",
                        style = MaterialTheme.typography.titleMedium, color = c.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(selected?.date.orEmpty(), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                key(selected?.key) {
                    ReviewText(selected, if (state.loading) stringResource(R.string.reviews_loading)
                        else if (failure != null) stringResource(R.string.reviews_failed) else stringResource(R.string.reviews_empty),
                        reader, refresh, Modifier.weight(1f)) { scope.launch { focusSelected() } }
                }
                Text(stringResource(R.string.reviews_hint), Modifier.height(d.reviewsStatusHeight).wrapContentHeight(),
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ReviewRow(review: SeriesReview, selected: Boolean, modifier: Modifier, onRead: () -> Unit) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(d.cardCornerRadius)
    Column(modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
        .background(if (focused) c.primary else c.surface, shape)
        .border(d.focusBorderWidth, if (selected) c.primary else c.surface, shape)
        .clickable(role = Role.Button, onClick = onRead).padding(d.spacingSmall), verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
        Text(review.author ?: stringResource(R.string.reviews_author_unknown), style = MaterialTheme.typography.titleSmall,
            color = if (focused) c.onPrimary else c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(review.text.replace('\n', ' '), style = MaterialTheme.typography.bodySmall,
            color = if (focused) c.onPrimary else c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ReviewText(review: SeriesReview?, placeholder: String, focus: FocusRequester, refresh: FocusRequester,
    modifier: Modifier, onList: () -> Unit) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { d.headerButtonSize.toPx() }
    var focused by remember { mutableStateOf(false) }
    val description = stringResource(R.string.reviews_read)
    Box(modifier.fillMaxWidth().border(d.focusBorderWidth, if (focused) c.primary else c.surface,
        RoundedCornerShape(d.cardCornerRadius)).focusRequester(focus)
        .focusProperties { canFocus = review != null; up = refresh; right = FocusRequester.Cancel; down = FocusRequester.Cancel }
        .onFocusChanged { focused = it.isFocused }
        .onPreviewKeyEvent { event ->
            when (event.key) {
                Key.DirectionLeft -> { if (event.type == KeyEventType.KeyDown) onList(); true }
                Key.DirectionUp, Key.DirectionDown, Key.PageUp, Key.PageDown -> {
                    if (event.type == KeyEventType.KeyDown) {
                        val forward = event.key == Key.DirectionDown || event.key == Key.PageDown
                        val amount = if (event.key == Key.PageUp || event.key == Key.PageDown) scroll.viewportSize.toFloat() else step
                        if (!forward && scroll.value == 0) refresh.requestFocus()
                        else scope.launch { scroll.scrollBy(if (forward) amount else -amount) }
                    }; true
                }
                else -> false
            }
        }.semantics { contentDescription = description }.focusable()) {
        Text(review?.text ?: placeholder, Modifier.fillMaxSize().verticalScroll(scroll).padding(d.spacingMedium),
            style = MaterialTheme.typography.bodyLarge, color = c.onBackground)
    }
}
