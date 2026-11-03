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
    val selectionFailure: SelectionFailure? = null,
    val progressFailure: LoadFailure? = null,
) {
    val translation get() = details?.translations?.firstOrNull { it.id == translationId }
    val episodes get() = translation?.episodes.takeIf { details?.playbackUnavailableReason == null }
    val episode get() = episodes?.firstOrNull { it.id == episodeId }
    val canPlay get() = details != null && details.playbackUnavailableReason == null && episode != null && selectionFailure == null
}

class SeriesDetailsViewModel(
    private val loadResume: suspend (String) -> ResumePoint? = { null },
    private val reload: (suspend (SeriesEntry) -> SeriesDetails)? = null,
    private val loadAccountProgress: suspend (SeriesDetails) -> AccountProgress? = { null },
    private val load: suspend (SeriesEntry) -> SeriesDetails,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DetailsUiState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var resumeJob: Job? = null
    private var progressJob: Job? = null
    private var savedResume: ResumePoint? = null
    private var selectionIntent = EntrySelection()

    fun open(entry: SeriesEntry) = fetch(entry)
    fun retry() {
        val current = state.value
        current.entry?.let { entry ->
            val hint = if (current.selectionFailure == null && current.episode != null) selectedHint(current)
                else selectionIntent
            fetch(entry, force = true, selection = hint)
        }
    }
    private fun selectedHint(current: DetailsUiState) = EntrySelection(
        episodeId = current.episode?.id, episodeLabel = current.episode?.label,
        translationId = current.translationId, translationLabel = current.translation?.label,
    )
    fun close() { job?.cancel(); resumeJob?.cancel(); progressJob?.cancel(); savedResume = null; selectionIntent = EntrySelection(); mutableState.value = DetailsUiState() }

    private fun fetch(entry: SeriesEntry, force: Boolean = false, selection: EntrySelection = entry.initialSelection) {
        job?.cancel()
        resumeJob?.cancel()
        progressJob?.cancel()
        savedResume = null
        selectionIntent = selection
        mutableState.value = DetailsUiState(entry = entry, loading = true)
        job = viewModelScope.launch {
            try {
                val details = (if (force) reload ?: load else load)(entry)
                ensureActive()
                val point = loadResume(entry.id)
                ensureActive()
                savedResume = EntrySelectionResolver.validResume(point, details)
                val selected = EntrySelectionResolver.resolve(details, selection, savedResume)
                mutableState.value = DetailsUiState(details.entry, details,
                    translationId = selected.translationId, episodeId = selected.episodeId,
                    resume = resumeFor(selected.translationId, selected.episodeId), selectionFailure = selected.failure)
                refreshAccountProgress(details)
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

    private fun refreshAccountProgress(details: SeriesDetails) {
        progressJob = viewModelScope.launch {
            try {
                val progress = loadAccountProgress(details) ?: return@launch
                ensureActive()
                val current = state.value
                if (current.entry?.id != details.entry.id || current.details == null) return@launch
                val entry = current.entry.copy(accountProgress = progress)
                mutableState.value = current.copy(entry = entry, details = current.details.copy(entry = entry))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                ensureActive()
                val current = state.value
                if (current.entry?.id == details.entry.id) mutableState.value = current.copy(progressFailure = when (e) {
                    is IOException -> LoadFailure.NETWORK; is SiteHttpException -> LoadFailure.HTTP; else -> LoadFailure.MARKUP
                })
            }
        }
    }

    fun selectSeason(id: String) {
        val current = state.value
        if (current.loading || current.entry?.id == id) return
        val season = current.details?.seasons?.firstOrNull { it.id == id } ?: return
        fetch(current.details.entry.copy(id = season.id, url = season.url, season = season.number, update = "", accountMark = null, accountProgress = null, historyVisitedAt = null, initialSelection = EntrySelection()))
    }

    fun selectTranslation(id: String) {
        val current = state.value
        val translation = current.details?.translations?.firstOrNull { it.id == id } ?: return
        val selected = if (current.selectionFailure != null || (current.episode == null && selectionIntent.hasEpisode)) {
            val hint = selectionIntent.copy(translationId = id, translationLabel = null)
            EntrySelectionResolver.resolve(current.details, hint, savedResume)
        } else {
            val episode = translation.episodes?.firstOrNull { it.label == current.episode?.label }
                ?: translation.episodes?.firstOrNull()
            ResolvedSelection(id, episode?.id)
        }
        val next = current.copy(translationId = selected.translationId, episodeId = selected.episodeId,
            selectionFailure = selected.failure, resume = resumeFor(selected.translationId, selected.episodeId))
        selectionIntent = if (selected.failure == null && next.episode != null) selectedHint(next)
            else selectionIntent.copy(translationId = id, translationLabel = null)
        mutableState.value = next
    }

    fun selectEpisode(id: String) {
        val current = state.value
        if (current.episodes?.any { it.id == id } != true) return
        val next = current.copy(episodeId = id, resume = resumeFor(current.translationId, id), selectionFailure = null)
        selectionIntent = selectedHint(next)
        mutableState.value = next
    }

    private fun resumeFor(translationId: String?, episodeId: String?): ResumePoint? = savedResume?.takeIf {
        it.translationId == translationId && it.episodeId == episodeId
    }

    fun playbackRequest(episodeId: String? = state.value.episodeId): PlaybackRequest? {
        val current = state.value
        val details = current.details ?: return null
        if (details.playbackUnavailableReason != null || current.selectionFailure != null) return null
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
            savedResume = EntrySelectionResolver.validResume(point, latest.details)
            mutableState.value = latest.copy(resume = resumeFor(latest.translationId, latest.episodeId))
        }
    }

    class Factory(private val repository: CatalogRepository, private val progress: PlaybackProgress,
        private val account: ru.seasonvar.tv.data.account.AccountRepository? = null) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SeriesDetailsViewModel::class.java))
            suspend fun loadDetails(entry: SeriesEntry, force: Boolean): SeriesDetails =
                try {
                    val details = repository.details(entry, force)
                    if (ru.seasonvar.tv.BuildConfig.DEBUG && (entry.initialSelection.hasEpisode || entry.initialSelection.hasTranslation)) {
                        val selected = EntrySelectionResolver.resolve(details, entry.initialSelection, null)
                        android.util.Log.d("SeasonvarDetails", "selectionContext episodeKnown=" + entry.initialSelection.hasEpisode +
                            " voiceKnown=" + entry.initialSelection.hasTranslation + " voiceSelected=" + (selected.translationId != null) +
                            " standardSelected=" + (selected.translationId == EntrySelectionResolver.standard(details)?.id) +
                            " episodeMatched=" + (selected.episodeId != null) + " failure=" + (selected.failure != null))
                    }
                    details
                } catch (e: Exception) {
                    if (e !is CancellationException && ru.seasonvar.tv.BuildConfig.DEBUG) {
                        android.util.Log.w("SeasonvarDetails", "season=" + entry.id + " " + failureSummary(e))
                    }
                    throw e
                }
            @Suppress("UNCHECKED_CAST")
            return SeriesDetailsViewModel(loadResume = progress::read,
                reload = { loadDetails(it, true) },
                loadAccountProgress = { details -> account?.progressSnapshot()?.let { snapshot ->
                    val parsed = ru.seasonvar.tv.data.account.AccountProgressParser.forDetails(details, snapshot)
                    if (ru.seasonvar.tv.BuildConfig.DEBUG) {
                        val direct = snapshot.episodeCounters[details.entry.id]
                        val standard = EntrySelectionResolver.standard(details)
                        val sourceEpisode = details.entry.initialSelection.episodeId?.substringBefore('_')?.toIntOrNull()
                            ?: details.entry.initialSelection.episodeLabel?.toIntOrNull()
                        android.util.Log.d("SeasonvarAccount", "progressContract seasonsKnown=" + (parsed.seasons != null) +
                            " episodesKnown=" + (parsed.episodes != null) + " episodesFromSite=" + (direct != null) +
                            " totalMatchesPlaylist=" + (direct != null && direct.total == standard?.episodes?.size) +
                            " markMatchesSource=" + (direct != null && sourceEpisode != null && direct.marked == sourceEpisode))
                    }
                    parsed
                } }, load = { loadDetails(it, false) }) as T
        }
    }
}
