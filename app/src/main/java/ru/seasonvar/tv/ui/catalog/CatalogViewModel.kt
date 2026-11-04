package ru.seasonvar.tv.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.seasonvar.tv.data.PublicFeed
import ru.seasonvar.tv.data.MarkupChangedException
import ru.seasonvar.tv.data.SeriesDescription
import ru.seasonvar.tv.data.SiteHttpException
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.CatalogRepository
import java.time.LocalDate

enum class LoadFailure { NETWORK, HTTP, MARKUP }

data class CatalogUiState(
    val date: LocalDate = LocalDate.now(),
    val entries: List<SeriesEntry> = emptyList(),
    val loading: Boolean = true,
    val loaded: Boolean = false,
    val cached: Boolean = false,
    val failure: LoadFailure? = null,
    val loadingMore: Boolean = false,
    val moreFailure: LoadFailure? = null,
    val hasMore: Boolean = false,
)

data class DescriptionState(
    val key: String? = null,
    val description: SeriesDescription? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
)

class CatalogViewModel(private val repository: CatalogRepository, private val feed: PublicFeed) : ViewModel() {
    private val mutableState = MutableStateFlow(CatalogUiState(loading = feed == PublicFeed.TODAY))
    val state = mutableState.asStateFlow()
    private val mutableDescription = MutableStateFlow(DescriptionState())
    val description = mutableDescription.asStateFlow()
    private var refreshJob: Job? = null
    private var descriptionJob: Job? = null

    private var started = false

    init { if (feed == PublicFeed.TODAY) refresh() }

    fun activate() {
        if (!started) refresh() else refreshIfDateChanged()
    }

    fun refresh() {
        started = true
        refreshJob?.cancel()
        val day = LocalDate.now()
        val previous = mutableState.value.takeIf { it.date == day && it.loaded }
        mutableState.value = previous?.copy(loading = true, failure = null) ?: CatalogUiState(date = day)
        refreshJob = viewModelScope.launch {
            val cached = if (previous == null) repository.cached(day, feed) else null
            mutableState.value = CatalogUiState(
                date = day,
                entries = previous?.entries ?: cached?.entries.orEmpty(),
                loaded = previous != null || cached != null,
                cached = previous?.cached ?: (cached != null),
            )
            try {
                val page = repository.load(day, feed)
                mutableState.value = CatalogUiState(
                    date = day, entries = page.entries, loading = false, loaded = true,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = when (e) {
                    is MarkupChangedException -> LoadFailure.MARKUP
                    is SiteHttpException -> LoadFailure.HTTP
                    else -> LoadFailure.NETWORK
                }
                mutableState.value = mutableState.value.copy(loading = false, failure = failure)
            }
        }
    }

    fun sessionChanged() {
        val reload = started
        refreshJob?.cancel(); descriptionJob?.cancel()
        mutableState.value = CatalogUiState(loading = false)
        mutableDescription.value = DescriptionState()
        started = false
        if (reload) refresh()
    }

    fun networkChanged() {
        if (!started) return
        val selected = mutableState.value.entries.firstOrNull { it.key == mutableDescription.value.key }
        refresh()
        selected?.let { select(it, true) }
    }

    fun refreshIfStarted() {
        if (started) refresh()
    }

    fun refreshIfDateChanged() {
        if (started && mutableState.value.date != LocalDate.now()) refresh()
    }

    fun select(entry: SeriesEntry, retry: Boolean = false) {
        if (!retry && mutableDescription.value.key == entry.key) return
        descriptionJob?.cancel()
        mutableDescription.value = DescriptionState(key = entry.key, loading = true)
        descriptionJob = viewModelScope.launch {
            try {
                delay(180)
                val result = repository.description(entry)
                mutableDescription.value = DescriptionState(entry.key, result)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableDescription.value = DescriptionState(entry.key, failed = true)
            }
        }
    }

    class Factory(private val repository: CatalogRepository, private val feed: PublicFeed) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CatalogViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return CatalogViewModel(repository, feed) as T
        }
    }
}
