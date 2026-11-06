package ru.seasonvar.tv.ui.catalog

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.images.*

private data class PrefetchInput(
    val entries: List<SeriesEntry>, val selectedKey: String?, val layout: CatalogLayout,
    val columns: Int, val thumbnailWidth: Int, val thumbnailHeight: Int, val gridRowHeight: Int,
)
private data class PrefetchViewport(val first: Int, val last: Int, val height: Int, val input: PrefetchInput)

@Composable
internal fun CatalogImagePrefetch(
    catalogKey: String, entries: List<SeriesEntry>, selectedKey: String?, layout: CatalogLayout,
    listState: LazyListState, gridState: LazyGridState, columns: Int,
    thumbnailWidth: Int, thumbnailHeight: Int, gridRowHeight: Int,
) {
    val context = LocalContext.current.applicationContext
    val loader = context.imageLoader
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val queue = remember(context, loader, scope, catalogKey) {
        WindowPrefetcher(scope, PrefetchImage::key, { prefetchImage(context, loader, it) },
            permits = (context as ru.seasonvar.tv.SeasonvarApplication).imagePrefetchPermits)
    }
    val input = rememberUpdatedState(PrefetchInput(entries, selectedKey, layout,
        columns, thumbnailWidth, thumbnailHeight, gridRowHeight))
    DisposableEffect(queue) { onDispose { queue.clear() } }
    LaunchedEffect(queue, lifecycle, catalogKey, listState, gridState) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                snapshotFlow {
                    val latest = input.value
                    if (latest.layout == CatalogLayout.GRID) {
                        val info = gridState.layoutInfo
                        PrefetchViewport(info.visibleItemsInfo.firstOrNull()?.index ?: -1,
                            info.visibleItemsInfo.lastOrNull()?.index ?: -1,
                            info.viewportEndOffset - info.viewportStartOffset, latest)
                    } else {
                        val info = listState.layoutInfo
                        PrefetchViewport(info.visibleItemsInfo.firstOrNull()?.index ?: -1,
                            info.visibleItemsInfo.lastOrNull()?.index ?: -1, 0, latest)
                    }
                }.collectLatest { view ->
                    val plan = withContext(Dispatchers.Default) {
                        val latest = view.input
                        val index = latest.entries.indexOfFirst { it.key == latest.selectedKey }
                        val page = if (latest.layout == CatalogLayout.STRIP) latest.columns
                            else prefetchPageSize(latest.columns, view.height, latest.gridRowHeight)
                        if (view.first < 0) emptyList() else imagePrefetchPlan(latest.entries, index, page,
                            view.first..view.last, latest.thumbnailWidth, latest.thumbnailHeight,
                            selectedPosterVisible = latest.layout == CatalogLayout.STRIP)
                    }
                    queue.update(plan)
                }
            } finally { queue.clear() }
        }
    }
}
