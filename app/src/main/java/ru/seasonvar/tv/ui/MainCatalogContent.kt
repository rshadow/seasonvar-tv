package ru.seasonvar.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import ru.seasonvar.tv.data.PublicFeed
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.SeriesDescription
import ru.seasonvar.tv.data.cache.CardDataCache
import ru.seasonvar.tv.ui.account.HistoryClearController
import ru.seasonvar.tv.ui.catalog.*
import ru.seasonvar.tv.ui.details.PosterBackdropPublisher
import ru.seasonvar.tv.ui.filters.ListFiltersController
import ru.seasonvar.tv.ui.navigation.MainCatalogPosition
import ru.seasonvar.tv.ui.navigation.MainFocus
import ru.seasonvar.tv.ui.navigation.Section
import ru.seasonvar.tv.ui.search.SearchControls
import ru.seasonvar.tv.ui.search.SearchUiState

@Composable
internal fun MainCatalogContent(state: CatalogUiState, selected: SeriesEntry?, section: Section, activeFeed: PublicFeed,
    catalogKey: String, position: MainCatalogPosition, focus: MainFocus, layout: CatalogLayout, cardMetrics: CatalogCardMetrics,
    signedIn: Boolean, cachedPreviewLocked: Boolean, imagePrefetchEnabled: Boolean, loadDescription: (suspend (SeriesEntry) -> SeriesDescription)?,
    cardData: CardDataCache?, preparationContext: Any?, resourceContext: Any?, browseState: BrowseCatalogState, browseCatalog: BrowseCatalogController?,
    searchState: SearchUiState, searchEditRequest: Int, filters: ListFiltersController?, historyClear: HistoryClearController?,
    onLayoutChange: () -> Unit, onEnterContent: () -> Unit, onSearchQuery: (String) -> Unit,
    onSearchSubmit: () -> Unit, onSearchResults: () -> Unit, onSearchRemember: () -> Unit,
    onSearchRecall: (String) -> Unit, onSearchClearHistory: () -> Unit, onRefresh: () -> Unit, onLoadMore: () -> Unit,
    onFocused: (SeriesEntry) -> Unit, onOpen: (SeriesEntry) -> Unit, onActions: (SeriesEntry) -> Unit,
    onBackdropSource: PosterBackdropPublisher?, onCardsFocusChanged: ((Boolean) -> Unit)?) {
    val browsing = section == Section.CATALOG
    val searching = section == Section.SEARCH
    val smart = section == Section.SMART_CONTINUE
    val personalFeed = section.personalFeed
    val cardsUpFocus = remember(browsing) { mutableStateOf(focus.layoutFocus) }
    CatalogPane(
        state, selected, position.list, focus.cards, if (browsing || searching || smart || personalFeed != null) null else activeFeed,
        focus.sectionFocus(section), focus.refreshFocus, focus.retryFocus,
        imagePrefetchEnabled = imagePrefetchEnabled,
        privateActionsEnabled = !cachedPreviewLocked,
        onBackdropSource = onBackdropSource,
        loadDescription = loadDescription,
        cardData = cardData,
        preparationContext = preparationContext,
        resourceContext = resourceContext,
        cardMetrics = cardMetrics, layout = layout, gridState = position.grid, layoutFocus = focus.layoutFocus,
        cardsUpFocus = cardsUpFocus.value,
        onLayoutChange = onLayoutChange,
        onEnterContent = onEnterContent,
        inlineFilters = if (browsing && browseCatalog != null) ({
            ru.seasonvar.tv.ui.catalog.BrowseCatalogFilters(browseState, browseCatalog, signedIn,
                focus.layoutFocus, focus.browseFiltersFocus, focus.refreshFocus, focus.browseFocus) { button ->
                cardsUpFocus.value = button
                onEnterContent()
            }
        }) else null,
        browsing = browsing,
        query = if (searching) searchState.query else null,
        searchHeader = if (searching) { {
            SearchControls(
                query = searchState.query,
                onQuery = onSearchQuery,
                showHistory = searchState.showHistory,
                historyLoading = searchState.historyLoading,
                historyFailed = searchState.historyFailed,
                onCommit = onSearchRemember,
                inputFocus = focus.searchInputFocus,
                menuFocus = focus.searchFocus,
                editRequest = searchEditRequest,
                onSubmit = onSearchSubmit,
                onResults = onSearchResults,
            )
        } } else null,
        historyContent = if (searching && searchState.showHistory) ({
            if (!searchState.historyLoading) ru.seasonvar.tv.ui.search.SearchHistoryPane(
                searchState.recentQueries, searchState.historyClearing, focus.searchHistoryFocus, focus.searchInputFocus, focus.searchFocus,
                focusOnShow = searchState.focusHistoryOnShow,
                onRecall = onSearchRecall,
                onClear = onSearchClearHistory)
        }) else null,
        onRefresh = onRefresh,
        smart = smart, personalFeed = personalFeed, catalogKey = catalogKey, allowAccountActions = signedIn,
        filters = filters, historyClear = historyClear,
        onLoadMore = onLoadMore,
        onFocused = onFocused,
        onOpen = onOpen,
        onActions = onActions,
        onCardsFocusChanged = onCardsFocusChanged,
    )
}
