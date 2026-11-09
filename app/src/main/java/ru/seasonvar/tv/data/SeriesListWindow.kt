package ru.seasonvar.tv.data

internal data class SeriesListWindow(val visible: IntRange, val ahead: IntRange) {
    fun preparationOrder(selectedIndex: Int): List<Int> = linkedSetOf<Int>().apply {
        if (selectedIndex in visible || selectedIndex in ahead) add(selectedIndex)
        addAll(visible)
        addAll(ahead)
    }.toList()
}

internal fun seriesListWindow(count: Int, selectedIndex: Int, pageSize: Int, visible: IntRange,
    prefetchEnabled: Boolean): SeriesListWindow {
    fun bounded(range: IntRange): IntRange {
        if (count <= 0 || range.isEmpty()) return IntRange.EMPTY
        val first = range.first.coerceAtLeast(0)
        val last = range.last.coerceAtMost(count - 1)
        return if (first <= last) first..last else IntRange.EMPTY
    }
    val ahead = if (prefetchEnabled && selectedIndex in 0 until count && pageSize > 0) {
        val end = (selectedIndex.toLong() + pageSize.toLong() * 2).coerceAtMost(count.toLong()).toInt()
        selectedIndex until end
    } else IntRange.EMPTY
    return SeriesListWindow(bounded(visible), ahead)
}
