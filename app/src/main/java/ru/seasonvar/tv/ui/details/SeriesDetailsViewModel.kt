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
import ru.seasonvar.tv.diagnostics.failureSummary
import ru.seasonvar.tv.data.playback.PlaybackProgress
import ru.seasonvar.tv.data.playback.PlaybackRequest
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
    val episodes get() = translation?.episodes.takeIf { details?.playbackUnavailableReason == null }
    val episode get() = episodes?.firstOrNull { it.id == episodeId }
    val canPlay get() = details != null && details.playbackUnavailableReason == null && episode != null
}

class SeriesDetailsViewModel(
    private val loadResume: suspend (String) -> ResumePoint? = { null },
    private val reload: (suspend (SeriesEntry) -> SeriesDetails)? = null,
    private val load: suspend (SeriesEntry) -> SeriesDetails,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DetailsUiState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var resumeJob: Job? = null
    private var savedResume: ResumePoint? = null

    fun open(entry: SeriesEntry) = fetch(entry)
    fun retry() { state.value.entry?.let { fetch(it, force = true) } }
    fun close() { job?.cancel(); resumeJob?.cancel(); savedResume = null; mutableState.value = DetailsUiState() }

    private fun fetch(entry: SeriesEntry, force: Boolean = false) {
        job?.cancel()
        resumeJob?.cancel()
        savedResume = null
        mutableState.value = DetailsUiState(entry = entry, loading = true)
        job = viewModelScope.launch {
            try {
                val details = (if (force) reload ?: load else load)(entry)
                ensureActive()
                val point = loadResume(entry.id)
                ensureActive()
                savedResume = point?.takeIf { resume ->
                    resume.seasonId == entry.id && resume.positionMillis > 0 && details.translations.any { translation ->
                        translation.id == resume.translationId && translation.episodes?.any { it.id == resume.episodeId } == true
                    }
                }
                ensureActive()
                val translation = details.translations.firstOrNull { it.id == savedResume?.translationId }
                    ?: details.translations.firstOrNull { it.id == details.defaultTranslationId }
                val episode = translation?.episodes?.firstOrNull { it.id == savedResume?.episodeId }
                    ?: translation?.episodes?.firstOrNull()
                mutableState.value = DetailsUiState(details.entry, details,
                    translationId = translation?.id, episodeId = episode?.id, resume = savedResume)
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
        fetch(current.details.entry.copy(id = season.id, url = season.url, season = season.number, update = "", accountMark = null, historyVisitedAt = null, historyEpisode = null))
    }

    fun selectTranslation(id: String) {
        val current = state.value
        val translation = current.details?.translations?.firstOrNull { it.id == id } ?: return
        val selectedLabel = current.episode?.label
        val episode = translation.episodes?.firstOrNull { it.label == selectedLabel }
            ?: translation.episodes?.firstOrNull()
        mutableState.value = current.copy(translationId = id, episodeId = episode?.id,
            resume = resumeFor(id, episode?.id))
    }

    fun selectEpisode(id: String) {
        val current = state.value
        if (current.episodes?.any { it.id == id } == true) mutableState.value = current.copy(episodeId = id, resume = resumeFor(current.translationId, id))
    }

    private fun resumeFor(translationId: String?, episodeId: String?): ResumePoint? = savedResume?.takeIf {
        it.translationId == translationId && it.episodeId == episodeId
    }

    fun playbackRequest(episodeId: String? = state.value.episodeId): PlaybackRequest? {
        val current = state.value
        val details = current.details ?: return null
        if (details.playbackUnavailableReason != null) return null
        val translation = current.translation ?: return null
        val episode = translation.episodes?.firstOrNull { it.id == episodeId } ?: return null
        return PlaybackRequest(details.entry.id, details.entry.url, translation.id, episode.id, episode.label, details.entry.title)
    }

    fun refreshResume() {
        val current = state.value
        val id = current.entry?.id ?: return
        if (current.details == null) return
        resumeJob?.cancel()
        resumeJob = viewModelScope.launch {
            val point = loadResume(id)
            ensureActive()
            val latest = state.value
            if (latest.entry?.id != id || latest.details == null) return@launch
            savedResume = point
            mutableState.value = latest.copy(resume = resumeFor(latest.translationId, latest.episodeId))
        }
    }

    class Factory(private val repository: CatalogRepository, private val progress: PlaybackProgress) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SeriesDetailsViewModel::class.java))
            suspend fun loadDetails(entry: SeriesEntry, force: Boolean): SeriesDetails =
                try { repository.details(entry, force) } catch (e: Exception) {
                    if (e !is CancellationException && ru.seasonvar.tv.BuildConfig.DEBUG) {
                        android.util.Log.w("SeasonvarDetails", "season=" + entry.id + " " + failureSummary(e))
                    }
                    throw e
                }
            @Suppress("UNCHECKED_CAST")
            return SeriesDetailsViewModel(loadResume = progress::read,
                reload = { loadDetails(it, true) }, load = { loadDetails(it, false) }) as T
        }
    }
}
