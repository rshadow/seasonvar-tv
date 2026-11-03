package ru.seasonvar.tv.ui.navigation

internal fun removalNeighbor(previous: List<String>, remaining: List<String>, selected: String): String? {
    val available = remaining.toSet()
    if (selected in available) return selected
    val index = previous.indexOf(selected)
    if (index < 0) return remaining.firstOrNull()
    return previous.drop(index + 1).firstOrNull { it in available }
        ?: previous.take(index).lastOrNull { it in available }
        ?: remaining.firstOrNull()
}
