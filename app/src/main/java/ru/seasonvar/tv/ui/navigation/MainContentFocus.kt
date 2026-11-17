package ru.seasonvar.tv.ui.navigation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.ui.catalog.CatalogLayout
import ru.seasonvar.tv.ui.catalog.CatalogUiState
import ru.seasonvar.tv.ui.search.SearchUiState

/** Снимок собирается при входе после ожидания кадров, а не при каждом выборе карточки. */
internal class MainContentTarget(val selected: SeriesEntry?, val state: CatalogUiState, val list: LazyListState,
    val grid: LazyGridState, val layout: CatalogLayout, val search: SearchUiState, val catalogKey: String)

/** Требует CoroutineScope композиции с часами кадров; повторно проверяет экран и выбор перед фокусом. */
internal suspend fun requestMainContentFocus(requestedSection: Section, currentSection: () -> Section,
    latest: () -> MainContentTarget, focus: MainFocus, inputModeManager: InputModeManager,
    browsing: Boolean, searching: Boolean, isDetailsOpen: () -> Boolean) {
    with(focus) {
        repeat(2) { withFrameNanos { } }
        if (currentSection() != requestedSection) return@with
        val target = latest()
        val selected = target.selected
        val state = target.state
        val listState = target.list
        if (currentSection() == Section.SEARCH && target.search.showHistory) {
            if (!target.search.historyLoading) {
                if (target.search.recentQueries.isEmpty()) searchInputFocus.requestFocus() else searchHistoryFocus.requestFocus()
            }
        } else if (currentSection() == Section.ACCOUNT) {
            accountFocus.requestFocus()
        } else if (currentSection() == Section.SETTINGS) {
            settingsContentFocus.requestFocus()
        } else if (currentSection() == Section.ABOUT) {
            aboutContentFocus.requestFocus()
        } else if (selected == null) {
            if (browsing) layoutFocus.requestFocus()
            else if (searching) searchInputFocus.requestFocus()
            else if (state.failure != null) retryFocus.requestFocus() else refreshFocus.requestFocus()
        } else {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            val index = state.entries.indexOfFirst { it.key == selected.key }
            val cardKey = target.catalogKey + "|" + selected.key
            val targetLayout = target.layout
            if (targetLayout == CatalogLayout.GRID) {
                val grid = target.grid
                if (grid.layoutInfo.visibleItemsInfo.none { it.key == cardKey }) {
                    grid.scrollToItem(index.coerceAtLeast(0))
                }
            } else if (listState.layoutInfo.visibleItemsInfo.none { it.key == cardKey }) {
                listState.scrollToItem(index.coerceAtLeast(0))
            }
            repeat(2) { withFrameNanos { } }
            val current = latest()
            if (currentSection() == requestedSection && !isDetailsOpen() && current.layout == targetLayout && current.selected?.key == selected.key) {
                cards[cardKey]?.requestFocus()
            }
        }
    }
}
