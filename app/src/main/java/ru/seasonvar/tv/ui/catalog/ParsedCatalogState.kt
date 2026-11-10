package ru.seasonvar.tv.ui.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.seasonvar.tv.data.ListParsingWindow
import ru.seasonvar.tv.data.ParsedSeriesEntries
import ru.seasonvar.tv.data.SeriesEntry

@Composable
internal fun parsedCatalogState(base: CatalogUiState, selectedKey: String?, identity: Any): CatalogUiState {
    val memory = remember(identity) { ParseDisplayMemory() }
    val window = (base.entries as? ParsedSeriesEntries)?.window ?: return base
    val parsed = window.state.collectAsStateWithLifecycle().value
    val previousIndex = memory.entries.indexOfFirst { it.key == selectedKey }
    val restoring = previousIndex >= 0 && parsed.hasMore && !parsed.failed && parsed.entries.none { it.key == selectedKey }
    LaunchedEffect(window, selectedKey, parsed.entries.size) {
        window.advance(if (restoring) maxOf(previousIndex, parsed.entries.size) else parsed.entries.indexOfFirst { it.key == selectedKey })
    }
    SideEffect { if (!restoring) memory.entries = parsed.entries }
    return base.copy(entries = if (restoring) memory.entries else parsed.entries,
        loading = base.loading || restoring, loadingMore = base.loadingMore || parsed.loading,
        moreFailure = if (parsed.failed) LoadFailure.MARKUP else base.moreFailure,
        hasMore = base.hasMore || parsed.hasMore)
}

private class ParseDisplayMemory(var entries: List<SeriesEntry> = emptyList())

internal fun <T> manageListParsing(scope: CoroutineScope, states: StateFlow<T>, entries: (T) -> List<SeriesEntry>) {
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        var previous: ListParsingWindow? = null
        try {
            states.collect { state ->
                val current = (entries(state) as? ParsedSeriesEntries)?.window
                if (current !== previous) { previous?.close(); previous = current }
            }
        } finally { previous?.close() }
    }
}
