package ru.seasonvar.tv.ui.catalog

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.ui.CARD_WARMUP_FRAMES
import ru.seasonvar.tv.ui.SeriesThumbnail
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@Composable
internal fun CatalogGrid(
    entries: List<SeriesEntry>, state: LazyGridState, catalogKey: String,
    cards: MutableMap<String, FocusRequester>, headerFocus: FocusRequester, menuFocus: FocusRequester,
    modifier: Modifier, onFocused: (SeriesEntry) -> Unit, onOpen: (SeriesEntry) -> Unit,
    onActions: ((SeriesEntry) -> Unit)?,
) {
    val d = SeasonvarTheme.dimensions
    val frames = remember(catalogKey, entries.firstOrNull()?.key, state) { mutableIntStateOf(0) }
    val first = remember(frames) { state.firstVisibleItemIndex }
    LaunchedEffect(frames) {
        repeat(CARD_WARMUP_FRAMES) { withFrameNanos { }; frames.intValue++ }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(d.gridMinCardWidth), state = state, modifier = modifier,
        contentPadding = PaddingValues(d.cardPadding),
        horizontalArrangement = Arrangement.spacedBy(d.spacingSmall),
        verticalArrangement = Arrangement.spacedBy(d.spacingSmall),
    ) {
        itemsIndexed(entries, key = { _, item -> catalogKey + "|" + item.key }, contentType = { _, _ -> "series" }) { index, entry ->
            val cardKey = catalogKey + "|" + entry.key
            val requester = remember(cardKey) { cards.getOrPut(cardKey) { FocusRequester() } }
            val ready = remember(frames, first, index) {
                derivedStateOf { frames.intValue >= CARD_WARMUP_FRAMES || index in first until first + frames.intValue }
            }
            SeriesThumbnail(entry, requester, FocusRequester.Default, null, FocusRequester.Default,
                contentReady = { ready.value }, onFocused = { onFocused(entry) }, onClick = { onOpen(entry) },
                onLongClick = onActions?.let { { it(entry) } }, grid = true,
                modifier = Modifier.focusProperties {
                    val edges = catalogGridEdges(index, entries.size, state.layoutInfo.maxSpan.coerceAtLeast(1))
                    up = if (edges.top) headerFocus else FocusRequester.Default
                    down = if (edges.bottom) FocusRequester.Cancel else FocusRequester.Default
                    left = if (edges.left) menuFocus else FocusRequester.Default
                    right = if (edges.right) FocusRequester.Cancel else FocusRequester.Default
                })
        }
    }
}
