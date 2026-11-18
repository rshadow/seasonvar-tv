package ru.seasonvar.tv.ui.navigation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import ru.seasonvar.tv.data.PublicFeed
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.account.PersonalFeed
import ru.seasonvar.tv.ui.catalog.CatalogLayout

@Stable
internal class MainCatalogPosition(val list: LazyListState, val grid: LazyGridState) {
    fun keepSelectionVisible(layout: CatalogLayout, catalogKey: String, selectedKey: String?, entries: List<SeriesEntry>) {
        if (selectedKey == null) return
        val visibleKeys = if (layout == CatalogLayout.GRID) grid.layoutInfo.visibleItemsInfo.map { it.key }
            else list.layoutInfo.visibleItemsInfo.map { it.key }
        val offset = visibleKeys.indexOf("$catalogKey|$selectedKey")
        if (offset < 0) return
        val selectedIndex = entries.indexOfFirst { it.key == selectedKey }
        if (selectedIndex < 0) return
        val firstKey = (visibleKeys.first() as? String)?.removePrefix("$catalogKey|")
        val firstIndex = entries.indexOfFirst { it.key == firstKey }
        if (firstIndex >= 0 && selectedIndex - firstIndex in visibleKeys.indices) return
        val anchor = (selectedIndex - offset).coerceAtLeast(0)
        if (layout == CatalogLayout.GRID) grid.requestScrollToItem(anchor) else list.requestScrollToItem(anchor)
    }
}

/** Все списки сохраняют позиции независимо; смена фильтров пересоздаёт только позицию каталога. */
@Stable
internal class MainCatalogPositions(
    private val public: Map<PublicFeed, MainCatalogPosition>,
    private val personal: Map<PersonalFeed, MainCatalogPosition>,
    private val browse: MainCatalogPosition,
    val search: MainCatalogPosition,
    private val smart: MainCatalogPosition,
) {
    fun forSection(section: Section, activeFeed: PublicFeed): MainCatalogPosition = when {
        section == Section.CATALOG -> browse
        section == Section.SEARCH -> search
        section == Section.SMART_CONTINUE -> smart
        section.personalFeed != null -> personal.getValue(section.personalFeed)
        else -> public.getValue(activeFeed)
    }

    fun resetPrivate() {
        personal.values.forEach { it.list.requestScrollToItem(0) }
        personal.values.forEach { it.grid.requestScrollToItem(0) }
        smart.list.requestScrollToItem(0)
        smart.grid.requestScrollToItem(0)
    }
}

@Composable
private fun rememberCatalogPosition(): MainCatalogPosition {
    val list = rememberLazyListState()
    val grid = rememberLazyGridState()
    return remember(list, grid) { MainCatalogPosition(list, grid) }
}

@Composable
internal fun rememberMainCatalogPositions(browseConditions: Map<String, String?>): MainCatalogPositions {
    val public = PublicFeed.entries.associateWith { key(it) { rememberCatalogPosition() } }
    val personal = PersonalFeed.entries.associateWith { key(it) { rememberCatalogPosition() } }
    val browse = key(browseConditions) { rememberCatalogPosition() }
    val search = rememberCatalogPosition()
    val smart = rememberCatalogPosition()
    return remember(public, personal, browse, search, smart) { MainCatalogPositions(public, personal, browse, search, smart) }
}
