package ru.seasonvar.tv.ui.actor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.seasonvar.tv.data.CatalogPage
import ru.seasonvar.tv.data.CatalogRepository
import ru.seasonvar.tv.data.MarkupChangedException
import ru.seasonvar.tv.data.SiteHttpException
import ru.seasonvar.tv.ui.catalog.CatalogUiState
import ru.seasonvar.tv.ui.catalog.LoadFailure
import ru.seasonvar.tv.ui.catalog.manageListParsing

class ActorSeriesViewModel(
    private val cached: suspend () -> CatalogPage?,
    private val load: suspend () -> CatalogPage,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CatalogUiState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    init { manageListParsing(viewModelScope, state) { it.entries } }

    fun refresh(clear: Boolean = false) {
        job?.cancel()
        mutableState.value = if (clear) CatalogUiState() else state.value.copy(loading = true, failure = null)
        job = viewModelScope.launch {
            try {
                if (!state.value.loaded) cached()?.let {
                    ensureActive()
                    mutableState.value = CatalogUiState(date = it.date, entries = it.entries,
                        loaded = true, loading = true, cached = true)
                }
                val page = load()
                ensureActive()
                mutableState.value = CatalogUiState(date = page.date, entries = page.entries, loading = false, loaded = true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                ensureActive()
                mutableState.value = state.value.copy(loading = false, failure = when (error) {
                    is MarkupChangedException -> LoadFailure.MARKUP
                    is SiteHttpException -> LoadFailure.HTTP
                    else -> LoadFailure.NETWORK
                })
            }
        }
    }

    class Factory(private val repository: CatalogRepository, private val url: String) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ActorSeriesViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return ActorSeriesViewModel({ repository.cachedActorPage(url) }, { repository.actorPage(url) }) as T
        }
    }
}
