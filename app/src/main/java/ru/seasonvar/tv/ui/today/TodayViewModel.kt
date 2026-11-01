package ru.seasonvar.tv.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.seasonvar.tv.data.MarkupChangedException
import ru.seasonvar.tv.data.SeriesDescription
import ru.seasonvar.tv.data.SiteHttpException
import ru.seasonvar.tv.data.TodayEntry
import ru.seasonvar.tv.data.TodayRepository
import java.time.LocalDate

enum class LoadFailure { NETWORK, HTTP, MARKUP }

data class TodayUiState(
    val date: LocalDate = LocalDate.now(),
    val entries: List<TodayEntry> = emptyList(),
    val loading: Boolean = true,
    val loaded: Boolean = false,
    val cached: Boolean = false,
    val failure: LoadFailure? = null,
)

data class DescriptionState(
    val key: String? = null,
    val description: SeriesDescription? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
)

class TodayViewModel(private val repository: TodayRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(TodayUiState())
    val state = mutableState.asStateFlow()
    private val mutableDescription = MutableStateFlow(DescriptionState())
    val description = mutableDescription.asStateFlow()
    private var refreshJob: Job? = null
    private var descriptionJob: Job? = null

    init { refresh() }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val day = LocalDate.now()
            val previous = mutableState.value.takeIf { it.date == day && it.loaded }
            val cached = if (previous == null) repository.cached(day) else null
            mutableState.value = TodayUiState(
                date = day,
                entries = previous?.entries ?: cached?.entries.orEmpty(),
                loaded = previous != null || cached != null,
                cached = previous?.cached ?: (cached != null),
            )
            try {
                val page = repository.load(day)
                mutableState.value = TodayUiState(
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

    fun networkChanged() {
        val selected = mutableState.value.entries.firstOrNull { it.key == mutableDescription.value.key }
        refresh()
        selected?.let { select(it, true) }
    }

    fun refreshIfDateChanged() {
        if (mutableState.value.date != LocalDate.now()) refresh()
    }

    fun select(entry: TodayEntry, retry: Boolean = false) {
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

    class Factory(private val repository: TodayRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TodayViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return TodayViewModel(repository) as T
        }
    }
}
