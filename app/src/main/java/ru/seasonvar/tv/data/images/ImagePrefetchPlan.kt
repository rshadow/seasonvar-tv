package ru.seasonvar.tv.data.images

import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.SeriesListWindow
import ru.seasonvar.tv.data.seriesListWindow

internal const val LIST_RESOURCE_CONCURRENCY = 3

internal enum class PrefetchKind { THUMBNAIL, LARGE }
internal data class PrefetchImage(val url: String, val kind: PrefetchKind, val width: Int = 0, val height: Int = 0) {
    val key: String get() = "$kind:$width:$height:$url"
}

internal fun imagePrefetchPlan(
    entries: List<SeriesEntry>, selectedIndex: Int, pageSize: Int, visible: IntRange,
    thumbnailWidth: Int, thumbnailHeight: Int, selectedPosterVisible: Boolean,
): List<PrefetchImage> {
    if (selectedIndex !in entries.indices || pageSize <= 0) return emptyList()
    return imagePrefetchPlan(entries, selectedIndex,
        seriesListWindow(entries.size, selectedIndex, pageSize, visible, true),
        thumbnailWidth, thumbnailHeight, selectedPosterVisible)
}

internal fun imagePrefetchPlan(entries: List<SeriesEntry>, selectedIndex: Int, window: SeriesListWindow,
    thumbnailWidth: Int, thumbnailHeight: Int, selectedPosterVisible: Boolean): List<PrefetchImage> {
    if (selectedIndex !in entries.indices || window.ahead.isEmpty()) return emptyList()
    val displayedPoster = if (selectedPosterVisible) entries[selectedIndex].largePosterUrl else null
    val visiblePosters = window.visible.mapTo(hashSetOf()) { entries[it].posterUrl }
    val result = linkedMapOf<String, PrefetchImage>()
    for (index in window.ahead) {
        val entry = entries[index]
        if (entry.posterUrl !in visiblePosters) {
            val image = PrefetchImage(entry.posterUrl, PrefetchKind.THUMBNAIL, thumbnailWidth, thumbnailHeight)
            result.putIfAbsent(image.key, image)
        }
        val large = entry.largePosterUrl
        if (large != null && large != displayedPoster && large != entry.posterUrl) {
            val image = PrefetchImage(large, PrefetchKind.LARGE)
            result.putIfAbsent(image.key, image)
        }
    }
    return result.values.toList()
}

internal fun prefetchPageSize(columns: Int, viewportHeight: Int, rowHeight: Int): Int {
    if (columns <= 0 || viewportHeight <= 0 || rowHeight <= 0) return 0
    val rows = (viewportHeight.toLong() + rowHeight - 1) / rowHeight
    return (columns * rows).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
