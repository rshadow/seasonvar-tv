package ru.seasonvar.tv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.seasonvar.tv.data.CatalogRepository
import ru.seasonvar.tv.data.MarkupChangedException
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.SiteHttpException
import ru.seasonvar.tv.ui.catalog.CatalogUiState
import ru.seasonvar.tv.ui.catalog.LoadFailure

data class SearchUiState(
    val query: String = "",
    val results: CatalogUiState = CatalogUiState(loading = false),
    val recentQueries: List<String> = emptyList(),
    val showHistory: Boolean = false,
    val focusHistoryOnShow: Boolean = false,
    val historyLoading: Boolean = false,
    val historyFailed: Boolean = false,
    val historyClearing: Boolean = false,
)

class SearchViewModel(
    private val search: suspend (String) -> List<SeriesEntry>,
    private val history: ru.seasonvar.tv.data.SearchHistory? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SearchUiState(historyLoading = history != null))
    val state = mutableState.asStateFlow()
    private var searchJob: Job? = null
    private var active = false
    private var touchedDuringVisit = false
    private var historyDirty = false
    private var historyGeneration = 0L

    init {
        if (history != null) viewModelScope.launch {
            try {
                history.load(); ensureActive()
                mutableState.value = state.value.copy(historyLoading = false, recentQueries = history.queries.value,
                    showHistory = active && (!touchedDuringVisit || state.value.query.isBlank()) && history.queries.value.isNotEmpty(),
                    focusHistoryOnShow = active && !touchedDuringVisit && history.queries.value.isNotEmpty())
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableState.value = state.value.copy(historyLoading = false, historyFailed = true, showHistory = false) }
            history.queries.collect { queries -> mutableState.value = state.value.copy(recentQueries = queries) }
        }
    }

    fun rememberCurrentQuery() {
        val query = state.value.query
        val generation = historyGeneration
        historyDirty = false
        if (history == null || ru.seasonvar.tv.data.SearchHistoryQueries.normalize(query) == null) return
        viewModelScope.launch {
            if (generation != historyGeneration) return@launch
            try { history.remember(query); ensureActive(); mutableState.value = state.value.copy(historyFailed = false) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableState.value = state.value.copy(historyFailed = true) }
        }
    }

    fun recall(query: String) {
        val normalized = ru.seasonvar.tv.data.SearchHistoryQueries.normalize(query) ?: return
        queryChanged(normalized)
        touchedDuringVisit = true
        mutableState.value = state.value.copy(showHistory = false, focusHistoryOnShow = false)
        rememberCurrentQuery()
        request(debounce = false)
    }

    fun clearHistory() {
        if (history == null || state.value.historyClearing) return
        historyGeneration++
        historyDirty = false
        mutableState.value = state.value.copy(historyClearing = true, historyFailed = false)
        viewModelScope.launch {
            try { history.clear(); ensureActive(); mutableState.value = state.value.copy(historyClearing = false) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableState.value = state.value.copy(historyClearing = false, historyFailed = true) }
        }
    }

    fun activate() {
        if (active) return
        active = true
        touchedDuringVisit = false
        mutableState.value = state.value.copy(showHistory = state.value.historyLoading || state.value.recentQueries.isNotEmpty(),
            focusHistoryOnShow = state.value.historyLoading || state.value.recentQueries.isNotEmpty())
        if (state.value.query.isNotBlank() && !state.value.results.loaded && !state.value.showHistory) request(debounce = false)
    }

    fun rememberPendingQuery() {
        if (active && historyDirty && !state.value.showHistory && state.value.results.loaded && state.value.results.failure == null) rememberCurrentQuery()
    }

    fun deactivate() {
        rememberPendingQuery()
        active = false
        searchJob?.cancel()
        mutableState.value = mutableState.value.copy(results = mutableState.value.results.copy(loading = false))
    }

    fun queryChanged(query: String) {
        if (query == mutableState.value.query) return
        searchJob?.cancel()
        touchedDuringVisit = true
        historyDirty = query.isNotBlank()
        mutableState.value = state.value.copy(query = query, results = CatalogUiState(loading = query.isNotBlank()),
            showHistory = query.isBlank() && state.value.recentQueries.isNotEmpty(), focusHistoryOnShow = false)
        request(debounce = true)
    }

    fun submit() {
        touchedDuringVisit = true
        mutableState.value = state.value.copy(showHistory = false, focusHistoryOnShow = false)
        rememberCurrentQuery()
        request(debounce = false)
    }

    fun sessionChanged() {
        searchJob?.cancel()
        mutableState.value = state.value.copy(results = CatalogUiState(loading = false))
        if (active) request(debounce = false)
    }

    fun marksChanged() {
        if (active) request(debounce = false)
        else mutableState.value = mutableState.value.copy(
            results = mutableState.value.results.copy(loaded = false))
    }

    fun networkChanged() {
        if (active) {
            request(debounce = false)
        }
    }

    private fun request(debounce: Boolean) {
        searchJob?.cancel()
        val query = mutableState.value.query.trim()
        if (query.isEmpty() || !active || state.value.showHistory) return
        mutableState.value = mutableState.value.copy(results = mutableState.value.results.copy(loading = true, failure = null))
        searchJob = viewModelScope.launch {
            try {
                if (debounce) delay(300)
                val entries = search(query)
                ensureActive()
                mutableState.value = mutableState.value.copy(results = CatalogUiState(
                    entries = entries, loaded = true, loading = false))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive()
                mutableState.value = mutableState.value.copy(results = mutableState.value.results.copy(
                    loading = false, failure = when (e) {
                        is MarkupChangedException -> LoadFailure.MARKUP
                        is SiteHttpException -> LoadFailure.HTTP
                        else -> LoadFailure.NETWORK
                    }))
            }
        }
    }

    class Factory(private val repository: CatalogRepository, private val history: ru.seasonvar.tv.data.SearchHistory) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SearchViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return SearchViewModel(repository::search, history) as T
        }
    }
}
