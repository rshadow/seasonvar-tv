package ru.seasonvar.tv.ui.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.seasonvar.tv.data.ListParsingWindow
import ru.seasonvar.tv.data.ParsedSeriesEntries
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.work.BackgroundCpu

@Composable
internal fun parsedCatalogState(
    base: CatalogUiState,
    selectedKey: String?,
    identity: Any,
    onEntriesChanged: (List<SeriesEntry>) -> Unit,
): CatalogUiState {
    val window = (base.entries as? ParsedSeriesEntries)?.window
    val parsed = window?.state?.collectAsStateWithLifecycle()?.value
    val incoming = parsed?.entries ?: base.entries
    if (!base.loaded && incoming.isEmpty()) return base

    val unfinished = parsed?.hasMore == true
    val excludedSeasons = parsed?.excludedSeasons.orEmpty()
    val keepUnresolved = unfinished || base.hasMore || parsed?.failed == true
    var displayed by remember(identity) { mutableStateOf(incoming, referentialEqualityPolicy()) }
    val latestOnEntriesChanged by rememberUpdatedState(onEntriesChanged)
    LaunchedEffect(identity, IncomingEntries(incoming), keepUnresolved, excludedSeasons) {
        val previous = displayed
        if (previous !== incoming) {
            val reconciled = withContext(BackgroundCpu.dispatcher) {
                reconcileCatalogEntries(previous, incoming, keepUnresolved, excludedSeasons)
            }
            if (reconciled !== previous) {
                latestOnEntriesChanged(reconciled)
                displayed = reconciled
            }
        }
    }
    LaunchedEffect(window, selectedKey, incoming.size) {
        if (unfinished && window != null) {
            val index = incoming.indexOfFirst { it.key == selectedKey }
            val unresolvedSelection = index < 0 && displayed.any { it.key == selectedKey }
            window.advance(if (unresolvedSelection) incoming.size else index)
        }
    }
    return base.copy(entries = displayed, hasMore = unfinished || base.hasMore,
        loadingMore = base.loadingMore || parsed?.loading == true,
        moreFailure = if (parsed?.failed == true) LoadFailure.MARKUP else base.moreFailure)
}

private class IncomingEntries(private val entries: List<SeriesEntry>) {
    override fun equals(other: Any?) = other is IncomingEntries && entries === other.entries
    override fun hashCode() = System.identityHashCode(entries)
}

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
