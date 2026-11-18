package ru.seasonvar.tv.ui.catalog

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.imageLoader
import ru.seasonvar.tv.R
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.SeriesDescription
import ru.seasonvar.tv.data.images.prefetchImage
import ru.seasonvar.tv.data.images.prefetchPageSize
import ru.seasonvar.tv.ui.SeriesThumbnail
import ru.seasonvar.tv.ui.details.PosterBackdropSource
import ru.seasonvar.tv.ui.details.PosterBackdropPublisher
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@Composable
internal fun SeriesList(
    entries: List<SeriesEntry>, catalogKey: String, selectedKey: String?, layout: CatalogLayout,
    listState: LazyListState, gridState: LazyGridState, cards: MutableMap<String, FocusRequester>,
    headerFocus: FocusRequester, menuFocus: FocusRequester, metrics: CatalogCardMetrics,
    prefetchEnabled: Boolean, modifier: Modifier,
    onFocused: (SeriesEntry) -> Unit, onOpen: (SeriesEntry) -> Unit, onActions: ((SeriesEntry) -> Unit)?,
    engine: SeriesListEngine = rememberSeriesListEngine(),
    onBackdropSource: PosterBackdropPublisher? = null,
) {
    val d = SeasonvarTheme.dimensions
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current
    val thumbnailWidth = with(density) { (metrics.width - d.cardPadding * 2).roundToPx().coerceAtLeast(1) }
    val thumbnailHeight = with(density) { metrics.posterHeight.roundToPx().coerceAtLeast(1) }
    val rowHeight = with(density) { (metrics.height + d.spacingSmall).roundToPx().coerceAtLeast(1) }
    val input = rememberUpdatedState(SeriesListInput(catalogKey, entries, selectedKey, metrics.columns,
        IntRange.EMPTY, prefetchEnabled, thumbnailWidth, thumbnailHeight, layout == CatalogLayout.STRIP))
    val latestLayout = rememberUpdatedState(layout)
    val latestRowHeight = rememberUpdatedState(rowHeight)
    DisposableEffect(engine) { onDispose { engine.clear() } }
    LaunchedEffect(engine, lifecycle, listState, gridState) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                snapshotFlow {
                    val current = input.value
                    if (latestLayout.value == CatalogLayout.GRID) {
                        val info = gridState.layoutInfo
                        val first = info.visibleItemsInfo.firstOrNull()?.index ?: -1
                        val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                        current.copy(visible = if (first < 0) IntRange.EMPTY else first..last,
                            pageSize = if (first < 0) 0 else prefetchPageSize(current.pageSize,
                                info.viewportEndOffset - info.viewportStartOffset, latestRowHeight.value))
                    } else {
                        val info = listState.layoutInfo
                        val first = info.visibleItemsInfo.firstOrNull()?.index ?: -1
                        val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                        current.copy(visible = if (first < 0) IntRange.EMPTY else first..last,
                            pageSize = if (first < 0) 0 else current.pageSize)
                    }
                }.collect { engine.update(it) }
            } finally { engine.clear() }
        }
    }
    val selection = key(catalogKey) { rememberUpdatedState(selectedKey) }
    val focusAction = key(catalogKey) { rememberUpdatedState(onFocused) }
    val openAction = key(catalogKey) { rememberUpdatedState(onOpen) }
    val longAction = key(catalogKey) { rememberUpdatedState(onActions) }.takeIf { onActions != null }
    if (layout == CatalogLayout.GRID) {
        LazyVerticalGrid(columns = GridCells.FixedSize(metrics.width), state = gridState, modifier = modifier,
            contentPadding = PaddingValues(d.cardPadding),
            horizontalArrangement = Arrangement.spacedBy(d.spacingSmall),
            verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
            itemsIndexed(entries, key = { _, entry -> catalogKey + "|" + entry.key }, contentType = { _, _ -> "series" }) { index, entry ->
                SeriesListCard(entry, index, entries.size, catalogKey, layout, cards, engine,
                    headerFocus, menuFocus, metrics, focusAction, openAction, longAction,
                    columns = { gridState.layoutInfo.maxSpan.coerceAtLeast(1) },
                    selection = selection, onBackdropSource = onBackdropSource)
            }
        }
    } else {
        LazyRow(state = listState, modifier = modifier, contentPadding = PaddingValues(d.cardPadding),
            horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
            itemsIndexed(entries, key = { _, entry -> catalogKey + "|" + entry.key }, contentType = { _, _ -> "series" }) { index, entry ->
                SeriesListCard(entry, index, entries.size, catalogKey, layout, cards, engine,
                    headerFocus, menuFocus, metrics, focusAction, openAction, longAction, columns = { metrics.columns },
                    selection = selection, onBackdropSource = onBackdropSource)
            }
        }
    }
}

@Composable
internal fun rememberSeriesListEngine(loadDescription: (suspend (SeriesEntry) -> SeriesDescription)? = null,
    contextKey: Any? = null, cardData: ru.seasonvar.tv.data.cache.CardDataCache? = null): SeriesListEngine {
    val context = LocalContext.current.applicationContext
    val loader = context.imageLoader
    val locale = LocalConfiguration.current.locales.toLanguageTags()
    val scope = rememberCoroutineScope()
    return remember(context, loader, scope, locale, loadDescription, contextKey, cardData) {
        val holder = Any()
        SeriesListEngine(scope, prepare = { entry ->
            val subtitle = if (entry.expectedReleaseDate != null) context.getString(R.string.announcements_expected, entry.expectedReleaseDate)
            else if (entry.historyVisitedAt != null && entry.initialSelection.episodeLabel != null) listOfNotNull(
                entry.season?.let { context.getString(R.string.season_number, it) },
                context.getString(R.string.episode_number, entry.initialSelection.episodeLabel),
            ).joinToString(" · ") else entry.subtitle.ifBlank {
                entry.season?.let { context.getString(R.string.season_number, it) }.orEmpty()
            }
            SeriesCardContent(cardData?.entry(entry) ?: entry, subtitle)
        }, loadImage = { prefetchImage(context, loader, it) },
            permits = (context as SeasonvarApplication).listResourcePermits,
            nextFrame = { withFrameNanos { } }, loadDescription = loadDescription,
            cachedDescription = { cardData?.description(it.id) },
            retainData = { cardData?.retain(holder, it) })
    }
}

@Composable
private fun SeriesListCard(entry: SeriesEntry, index: Int, count: Int, catalogKey: String, layout: CatalogLayout,
    cards: MutableMap<String, FocusRequester>, engine: SeriesListEngine, headerFocus: FocusRequester,
    menuFocus: FocusRequester, metrics: CatalogCardMetrics, onFocused: State<(SeriesEntry) -> Unit>,
    onOpen: State<(SeriesEntry) -> Unit>, onActions: State<((SeriesEntry) -> Unit)?>?, columns: () -> Int,
    selection: State<String?>, onBackdropSource: PosterBackdropPublisher?) {
    val key = catalogKey + "|" + entry.key
    val requester = remember(key, cards) { cards[key] ?: FocusRequester() }
    DisposableEffect(key, cards, requester) {
        cards[key] = requester
        onDispose { if (cards[key] === requester) cards.remove(key) }
    }
    val grid = layout == CatalogLayout.GRID
    val prepared = remember(engine, key, entry) {
        derivedStateOf(referentialEqualityPolicy()) { engine.content(key, entry) }
    }
    val posterImage = remember(entry.posterUrl) { mutableStateOf<Bitmap?>(null) }
    if (onBackdropSource != null && remember(selection, entry.key) {
            derivedStateOf(structuralEqualityPolicy()) { selection.value == entry.key }
        }.value) {
        DisposableEffect(entry.posterUrl, posterImage, onBackdropSource) {
            val handle = onBackdropSource(PosterBackdropSource(entry.posterUrl, posterImage))
            onDispose { handle.dispose() }
        }
    }
    SeriesThumbnail(entry, requester, if (grid) FocusRequester.Default else headerFocus, metrics,
        if (!grid && index == 0) menuFocus else null,
        right = if (!grid && index == count - 1) FocusRequester.Cancel else FocusRequester.Default,
        content = { prepared.value },
        onFocused = { onFocused.value(entry) }, onClick = { onOpen.value(entry) },
        onLongClick = onActions?.let { { it.value?.invoke(entry) } },
        onPosterLoaded = { posterImage.value = it },
        modifier = if (!grid) Modifier else Modifier.focusProperties {
            val edges = catalogGridEdges(index, count, columns())
            up = if (edges.top) headerFocus else FocusRequester.Default
            down = if (edges.bottom) FocusRequester.Cancel else FocusRequester.Default
            left = if (edges.left) menuFocus else FocusRequester.Default
            right = if (edges.right) FocusRequester.Cancel else FocusRequester.Default
        })
}
