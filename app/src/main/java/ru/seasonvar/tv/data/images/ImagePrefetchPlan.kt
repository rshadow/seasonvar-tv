package ru.seasonvar.tv.data.images

import ru.seasonvar.tv.data.SeriesEntry

internal const val IMAGE_PREFETCH_CONCURRENCY = 3

internal enum class PrefetchKind { THUMBNAIL, LARGE }
internal data class PrefetchImage(val url: String, val kind: PrefetchKind, val width: Int = 0, val height: Int = 0) {
    val key: String get() = "$kind:$width:$height:$url"
}

internal fun imagePrefetchPlan(
    entries: List<SeriesEntry>, selectedIndex: Int, pageSize: Int, visible: IntRange,
    thumbnailWidth: Int, thumbnailHeight: Int, selectedPosterVisible: Boolean,
): List<PrefetchImage> {
    if (selectedIndex !in entries.indices || pageSize <= 0) return emptyList()
    val end = (selectedIndex.toLong() + pageSize.toLong() * 2).coerceAtMost(entries.size.toLong()).toInt()
    val displayedPoster = if (selectedPosterVisible) entries[selectedIndex].largePosterUrl else null
    val visiblePosters = entries.indices.filter { it in visible }.map { entries[it].posterUrl }.toSet()
    val result = linkedMapOf<String, PrefetchImage>()
    for (index in selectedIndex until end) {
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
