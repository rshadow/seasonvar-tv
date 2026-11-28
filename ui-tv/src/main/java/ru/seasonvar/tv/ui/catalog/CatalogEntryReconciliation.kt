package ru.seasonvar.tv.ui.catalog

import ru.seasonvar.tv.data.SeriesEntry

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
