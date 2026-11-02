package ru.seasonvar.tv.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.ui.catalog.LoadFailure
import java.io.IOException

data class DetailsUiState(
    val entry: SeriesEntry? = null,
    val details: SeriesDetails? = null,
    val loading: Boolean = false,
    val failure: LoadFailure? = null,
    val translationId: String? = null,
    val episodeId: String? = null,
    val resume: ResumePoint? = null,
) {
    val translation get() = details?.translations?.firstOrNull { it.id == translationId }
    val episodes get() = translation?.episodes
    val episode get() = episodes?.firstOrNull { it.id == episodeId }
}

class SeriesDetailsViewModel(private val load: suspend (SeriesEntry) -> SeriesDetails) : ViewModel() {
    private val mutableState = MutableStateFlow(DetailsUiState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun open(entry: SeriesEntry) = fetch(entry)
    fun retry() { state.value.entry?.let(::fetch) }
    fun close() { job?.cancel(); mutableState.value = DetailsUiState() }

    private fun fetch(entry: SeriesEntry) {
        job?.cancel()
        mutableState.value = DetailsUiState(entry = entry, loading = true)
        job = viewModelScope.launch {
            try {
                val details = load(entry)
                ensureActive()
                val translation = details.translations.firstOrNull { it.id == details.defaultTranslationId }
                mutableState.value = DetailsUiState(details.entry, details,
                    translationId = translation?.id, episodeId = translation?.episodes?.firstOrNull()?.id)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                ensureActive()
                val failure = when (e) {
                    is SiteHttpException -> LoadFailure.HTTP
                    is IOException -> LoadFailure.NETWORK
                    else -> LoadFailure.MARKUP
                }
                mutableState.value = DetailsUiState(entry = entry, failure = failure)
            }
        }
    }

    fun selectSeason(id: String) {
        val current = state.value
        if (current.loading || current.entry?.id == id) return
        val season = current.details?.seasons?.firstOrNull { it.id == id } ?: return
        fetch(current.details.entry.copy(id = season.id, url = season.url, season = season.number, update = ""))
    }

    fun selectTranslation(id: String) {
        val current = state.value
        val translation = current.details?.translations?.firstOrNull { it.id == id } ?: return
        val selectedLabel = current.episode?.label
        val episode = translation.episodes?.firstOrNull { it.label == selectedLabel }
            ?: translation.episodes?.firstOrNull()
        mutableState.value = current.copy(translationId = id, episodeId = episode?.id)
    }

    fun selectEpisode(id: String) {
        val current = state.value
        if (current.episodes?.any { it.id == id } == true) mutableState.value = current.copy(episodeId = id)
    }

    class Factory(private val repository: CatalogRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SeriesDetailsViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return SeriesDetailsViewModel(repository::details) as T
        }
    }
}
