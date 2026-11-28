package ru.seasonvar.tv.ui.catalog

internal enum class CatalogLayout(val storageId: String) {
    STRIP("strip"), GRID("grid");

    fun other() = if (this == STRIP) GRID else STRIP

    companion object {
        fun fromStorage(value: String?) = entries.firstOrNull { it.storageId == value } ?: STRIP
    }
}

internal data class GridCardEdges(val top: Boolean, val bottom: Boolean, val left: Boolean, val right: Boolean)

internal fun catalogGridEdges(index: Int, count: Int, columns: Int): GridCardEdges {
    require(index in 0 until count && columns > 0)
    return GridCardEdges(
        top = index < columns,
        bottom = index / columns == (count - 1) / columns,
        left = index % columns == 0,
        right = index % columns == columns - 1 || index == count - 1,
    )
}
