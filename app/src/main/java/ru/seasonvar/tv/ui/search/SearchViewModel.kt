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
import ru.seasonvar.tv.data.SeriesDescription
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.SiteHttpException
import ru.seasonvar.tv.ui.catalog.CatalogUiState
import ru.seasonvar.tv.ui.catalog.DescriptionState
import ru.seasonvar.tv.ui.catalog.LoadFailure

data class SearchUiState(
    val query: String = "",
    val results: CatalogUiState = CatalogUiState(loading = false),
)

class SearchViewModel(
    private val search: suspend (String) -> List<SeriesEntry>,
    private val loadDescription: suspend (SeriesEntry) -> SeriesDescription,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SearchUiState())
    val state = mutableState.asStateFlow()
    private val mutableDescription = MutableStateFlow(DescriptionState())
    val description = mutableDescription.asStateFlow()
    private var searchJob: Job? = null
    private var descriptionJob: Job? = null
    private var active = false

    fun activate() {
        active = true
        if (mutableState.value.query.isNotBlank() && !mutableState.value.results.loaded) submit()
    }

    fun deactivate() {
        active = false
        searchJob?.cancel()
        descriptionJob?.cancel()
        mutableDescription.value = DescriptionState()
        mutableState.value = mutableState.value.copy(results = mutableState.value.results.copy(loading = false))
    }

    fun queryChanged(query: String) {
        if (query == mutableState.value.query) return
        searchJob?.cancel()
        descriptionJob?.cancel()
        mutableDescription.value = DescriptionState()
        mutableState.value = SearchUiState(query, CatalogUiState(loading = query.isNotBlank()))
        request(debounce = true)
    }

    fun submit() = request(debounce = false)

    fun networkChanged() {
        if (active) {
            mutableDescription.value = DescriptionState()
            submit()
        }
    }

    private fun request(debounce: Boolean) {
        searchJob?.cancel()
        val query = mutableState.value.query.trim()
        if (query.isEmpty() || !active) return
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

    fun select(entry: SeriesEntry, retry: Boolean = false) {
        if (!retry && mutableDescription.value.key == entry.key) return
        descriptionJob?.cancel()
        mutableDescription.value = DescriptionState(key = entry.key, loading = true)
        descriptionJob = viewModelScope.launch {
            try {
                delay(180)
                val result = loadDescription(entry)
                ensureActive()
                mutableDescription.value = DescriptionState(entry.key, result)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ensureActive()
                mutableDescription.value = DescriptionState(entry.key, failed = true)
            }
        }
    }

    class Factory(private val repository: CatalogRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SearchViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return SearchViewModel(repository::search, repository::description) as T
        }
    }
}
