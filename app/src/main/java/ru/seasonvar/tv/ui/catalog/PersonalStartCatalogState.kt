package ru.seasonvar.tv.ui.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.withContext
import ru.seasonvar.tv.data.ParsedSeriesEntries
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.work.BackgroundCpu

internal fun reconcileCatalogEntries(
    previous: List<SeriesEntry>,
    incoming: List<SeriesEntry>,
    keepUnresolved: Boolean,
    excludedSeasons: Set<String> = emptySet(),
): List<SeriesEntry> {
    if (previous === incoming || previous.isEmpty() || incoming.isEmpty() && !keepUnresolved) return incoming
    if ((previous.size == incoming.size || keepUnresolved && excludedSeasons.isEmpty() && incoming.size < previous.size) &&
        incoming.indices.all { previous[it] == incoming[it] }) return previous

    val remaining = previous.associateByTo(HashMap(previous.size), SeriesEntry::key)
    val result = ArrayList<SeriesEntry>(if (keepUnresolved) maxOf(previous.size, incoming.size) else incoming.size)
    for (entry in incoming) {
        val old = remaining.remove(entry.key)
        result.add(old?.takeIf { it == entry } ?: entry)
    }
    if (keepUnresolved) previous.filterTo(result) { it.key in remaining && it.id !in excludedSeasons }
    return result
}

@Composable
internal fun personalStartCatalogState(
    base: CatalogUiState,
    selectedKey: String?,
    identity: Any,
    showingPreview: Boolean,
    onEntriesChanged: (List<SeriesEntry>) -> Unit,
): CatalogUiState {
    val usedPreview = remember(identity) { PreviewUsage() }
    if (showingPreview) SideEffect { usedPreview.shown = true }
    if (!showingPreview && !usedPreview.shown) return parsedCatalogState(base, selectedKey, identity)

    val window = (base.entries as? ParsedSeriesEntries)?.window
    val parsed = window?.state?.collectAsStateWithLifecycle()?.value
    val incoming = parsed?.entries ?: base.entries
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

private class PreviewUsage(var shown: Boolean = false)

private class IncomingEntries(private val entries: List<SeriesEntry>) {
    override fun equals(other: Any?) = other is IncomingEntries && entries === other.entries
    override fun hashCode() = System.identityHashCode(entries)
}
