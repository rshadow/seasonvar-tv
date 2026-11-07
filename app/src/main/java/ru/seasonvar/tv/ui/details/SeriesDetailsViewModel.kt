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
    val seasonResume: ResumePoint? = null,
    val selectionFailure: SelectionFailure? = null,
    val progressFailure: LoadFailure? = null,
    val progressLoading: Boolean = false,
) {
    val resume get() = seasonResume?.takeIf {
        it.seasonId == entry?.id && it.translationId == translationId && it.episodeId == episodeId
    }
    val translation get() = details?.translations?.firstOrNull { it.id == translationId }
    val episodes get() = translation?.episodes.takeIf { details?.playbackUnavailableReason == null }
    val episode get() = episodes?.firstOrNull { it.id == episodeId }
    val episodeFocusId get() = episode?.id ?: episodes?.firstOrNull()?.id
    val canPlay get() = details != null && details.playbackUnavailableReason == null && episode != null && selectionFailure == null
}

class SeriesDetailsViewModel(
    private val loadResume: suspend (String) -> ResumePoint? = { null },
    private val reload: (suspend (SeriesEntry) -> SeriesDetails)? = null,
    private val loadAccountProgress: suspend (SeriesDetails) -> AccountProgress? = { null },
    private val loadAccountMark: suspend (SeriesEntry) -> ru.seasonvar.tv.data.account.AccountListMark? = { null },
    private val preferredTranslations: () -> List<String> = { emptyList() },
    private val load: suspend (SeriesEntry) -> SeriesDetails,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DetailsUiState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var resumeJob: Job? = null
    private var progressJob: Job? = null
    private var markJob: Job? = null
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
    fun close() { job?.cancel(); resumeJob?.cancel(); progressJob?.cancel(); markJob?.cancel(); savedResume = null; selectionIntent = EntrySelection(); mutableState.value = DetailsUiState() }

    private fun fetch(entry: SeriesEntry, force: Boolean = false, selection: EntrySelection = entry.initialSelection) {
        job?.cancel()
        resumeJob?.cancel()
        progressJob?.cancel()
        markJob?.cancel()
        savedResume = null
        selectionIntent = selection
        mutableState.value = DetailsUiState(entry = entry, loading = true, progressLoading = true)
        job = viewModelScope.launch {
            try {
                val details = (if (force) reload ?: load else load)(entry)
                ensureActive()
                val point = loadResume(entry.id)
                ensureActive()
                savedResume = if (selection.positionMillis == null) EntrySelectionResolver.validResume(point, details) else null
                val selected = EntrySelectionResolver.resolve(details, selection, savedResume, preferredTranslations())
                if (selection.positionMillis != null && selection.positionMillis >= 0 && selected.translationId != null && selected.episodeId != null) {
                    savedResume = ResumePoint(details.entry.id, selected.translationId, selected.episodeId, selection.positionMillis)
                }
                mutableState.value = DetailsUiState(details.entry, details,
                    translationId = selected.translationId, episodeId = selected.episodeId,
                    seasonResume = savedResume, selectionFailure = selected.failure, progressLoading = true)
                refreshAccountProgress(details)
                refreshAccountMark(details.entry)
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
        progressJob?.cancel()
        mutableState.value = state.value.copy(progressLoading = true, progressFailure = null)
        progressJob = viewModelScope.launch {
            try {
                val progress = loadAccountProgress(details)
                ensureActive()
                val current = state.value
                if (current.entry?.id != details.entry.id || current.details == null) return@launch
                val entry = if (progress != null) current.entry.copy(accountProgress = progress) else current.entry
                mutableState.value = current.copy(entry = entry, details = current.details.copy(entry = entry), progressLoading = false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                ensureActive()
                val current = state.value
                if (current.entry?.id == details.entry.id) mutableState.value = current.copy(progressLoading = false, progressFailure = when (e) {
                    is IOException -> LoadFailure.NETWORK; is SiteHttpException -> LoadFailure.HTTP; else -> LoadFailure.MARKUP
                })
            }
        }
    }

    private fun refreshAccountMark(entry: SeriesEntry) {
        markJob?.cancel()
        markJob = viewModelScope.launch {
            try {
                val mark = loadAccountMark(entry)
                ensureActive()
                val current = state.value
                if (current.entry?.id != entry.id || current.details == null) return@launch
                val updated = current.entry.copy(accountListMark = mark)
                mutableState.value = current.copy(entry = updated, details = current.details.copy(entry = updated))
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Keep a known list entry available when its refresh fails. */ }
        }
    }

    fun markChanged(target: ru.seasonvar.tv.data.account.AccountListMark) {
        val current = state.value
        if (current.entry?.id != target.seasonId || current.details == null) return
        val entry = current.entry.copy(accountListMark = target, accountMark = null, accountProgress = null)
        mutableState.value = current.copy(entry = entry, details = current.details.copy(entry = entry))
        refreshAccountMark(entry)
        refreshAccountProgress(current.details)
    }

    fun markRemoved(target: ru.seasonvar.tv.data.account.AccountListMark) {
        val current = state.value
        if (current.entry?.id != target.seasonId || current.details == null) return
        markJob?.cancel(); progressJob?.cancel()
        val updated = current.entry.copy(accountListMark = null, accountMark = null, accountProgress = null)
        mutableState.value = current.copy(entry = updated, details = current.details.copy(entry = updated), progressLoading = false)
    }

    fun selectSeason(id: String) {
        val current = state.value
        if (current.loading || current.entry?.id == id) return
        val season = current.details?.seasons?.firstOrNull { it.id == id } ?: return
        fetch(current.details.entry.copy(id = season.id, url = season.url, season = season.number, update = "", accountMark = null, accountProgress = null, accountListMark = null, historyVisitedAt = null, initialSelection = EntrySelection()))
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
            selectionFailure = selected.failure)
        selectionIntent = if (selected.failure == null && next.episode != null) selectedHint(next)
            else selectionIntent.copy(translationId = id, translationLabel = null)
        mutableState.value = next
    }

    fun selectEpisode(id: String) {
        val current = state.value
        if (current.episodes?.any { it.id == id } != true) return
        val next = current.copy(episodeId = id, selectionFailure = null)
        selectionIntent = selectedHint(next)
        mutableState.value = next
    }

    fun playbackRequest(episodeId: String? = state.value.episodeId): PlaybackRequest? {
        val current = state.value
        val details = current.details ?: return null
        if (details.playbackUnavailableReason != null || current.selectionFailure != null) return null
        val translation = current.translation ?: return null
        val episode = translation.episodes?.firstOrNull { it.id == episodeId } ?: return null
        return PlaybackRequest(details.entry.id, details.entry.url, translation.id, episode.id, episode.label, details.entry.title, translation.episodes.toList(),
            current.resume?.takeIf { it.episodeId == episode.id && it.translationId == translation.id }?.positionMillis,
            seasonNumber = details.seasons.firstOrNull { it.id == details.entry.id }?.number ?: details.entry.season)
    }

    fun onPlaybackFinished(seasonId: String?, translationId: String?, episodeId: String?) {
        val current = state.value
        if (seasonId == null || translationId == null || episodeId == null) return
        if (current.entry?.id != seasonId) {
            val season = current.details?.seasons?.singleOrNull { it.id == seasonId } ?: return
            val entry = current.entry ?: return
            fetch(entry.copy(id = season.id, url = season.url, season = season.number, update = "",
                accountMark = null, accountProgress = null, accountListMark = null, historyVisitedAt = null,
                initialSelection = EntrySelection(episodeId = episodeId, translationId = translationId)))
            return
        }
        if (current.translationId == translationId) selectEpisode(episodeId)
    }

    fun refreshResume() {
        val current = state.value
        val id = current.entry?.id ?: return
        if (current.details == null) return
        refreshAccountProgress(current.details)
        refreshAccountMark(current.entry)
        resumeJob?.cancel()
        resumeJob = viewModelScope.launch {
            val point = loadResume(id)
            ensureActive()
            val latest = state.value
            if (latest.entry?.id != id || latest.details == null) return@launch
            savedResume = EntrySelectionResolver.validResume(point, latest.details)
            mutableState.value = latest.copy(seasonResume = savedResume)
        }
    }

    class Factory(private val repository: CatalogRepository, private val progress: PlaybackProgress,
        private val account: ru.seasonvar.tv.data.account.AccountRepository? = null,
        private val preferredTranslations: () -> List<String> = { emptyList() }) : ViewModelProvider.Factory {
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
            return SeriesDetailsViewModel(loadResume = progress::read, preferredTranslations = preferredTranslations,
                reload = { loadDetails(it, true) },
                loadAccountMark = { entry -> account?.listMark(ru.seasonvar.tv.data.account.AccountListMark(entry.id, entry.url)) },
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
