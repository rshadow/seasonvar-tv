package ru.seasonvar.tv.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.seasonvar.tv.data.update.*
import java.io.File
import java.io.IOException

enum class UpdateFailure { NETWORK, HTTP, FORMAT, INTEGRITY, SIGNATURE, PACKAGE, PERMISSION, INSTALLER }
data class UpdateUiState(
    val notes: List<ReleaseNotes> = emptyList(),
    val notesLoading: Boolean = false,
    val notesFailed: Boolean = false,
    val checked: Boolean = false,
    val checking: Boolean = false,
    val release: GitHubRelease? = null,
    val available: Boolean = false,
    val downloading: Boolean = false,
    val percent: Int = 0,
    val ready: File? = null,
    val installRequested: Boolean = false,
    val installing: Boolean = false,
    val failure: UpdateFailure? = null,
)

class UpdateViewModel internal constructor(
    private val installedVersion: String,
    private val latest: suspend () -> GitHubRelease?,
    private val download: suspend (GitHubRelease, (Int) -> Unit) -> File,
    private val cachedNotes: suspend () -> List<ReleaseNotes> = { emptyList() },
    private val loadNotes: suspend () -> List<ReleaseNotes> = { emptyList() },
) : ViewModel() {
    private val mutableState = MutableStateFlow(UpdateUiState())
    val state = mutableState.asStateFlow()
    private var notesJob: Job? = null

    fun enter() {
        if (state.value.checking || state.value.downloading || state.value.installRequested || state.value.installing) return
        refreshNotes()
        mutableState.value = state.value.copy(checking = true, failure = null, ready = null)
        viewModelScope.launch {
            try {
                val release = latest()
                ensureActive()
                mutableState.value = state.value.copy(checking = false, checked = true, release = release,
                    available = release?.newerThan(installedVersion) == true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutableState.value = state.value.copy(checking = false, checked = true, failure = failure(error))
            }
        }
    }

    private fun refreshNotes() {
        if (notesJob?.isActive == true) return
        mutableState.value = state.value.copy(notesLoading = true, notesFailed = false)
        notesJob = viewModelScope.launch {
            try {
                if (state.value.notes.isEmpty()) {
                    val cached = cachedNotes()
                    ensureActive()
                    mutableState.value = state.value.copy(notes = ReleaseNotesParser.relevant(cached, installedVersion))
                }
                val notes = loadNotes()
                ensureActive()
                mutableState.value = state.value.copy(notes = ReleaseNotesParser.relevant(notes, installedVersion),
                    notesLoading = false)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                mutableState.value = state.value.copy(notesLoading = false, notesFailed = true)
            }
        }
    }

    fun install() {
        val current = state.value
        val release = current.release ?: return
        if (!current.available || release.asset == null || current.checking || current.downloading || current.installRequested || current.installing) return
        if (current.ready?.isFile == true) {
            mutableState.value = current.copy(installRequested = true, failure = null)
            return
        }
        mutableState.value = current.copy(downloading = true, percent = 0, ready = null, failure = null)
        viewModelScope.launch {
            try {
                val ready = download(release) { percent ->
                    mutableState.value = mutableState.value.copy(percent = percent.coerceIn(0, 100))
                }
                ensureActive()
                mutableState.value = state.value.copy(downloading = false, ready = ready, installRequested = true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutableState.value = state.value.copy(downloading = false, failure = failure(error))
            }
        }
    }

    fun installRequestHandled() { mutableState.value = state.value.copy(installRequested = false, installing = true) }
    fun installationReturned() { mutableState.value = state.value.copy(installing = false) }
    fun installationFailed(reason: UpdateFailure) { mutableState.value = state.value.copy(installing = false, failure = reason) }

    private fun failure(error: Exception) = when (error) {
        is UpdateHttpException -> UpdateFailure.HTTP
        is UpdateIntegrityException -> UpdateFailure.INTEGRITY
        is UpdateSignatureException -> UpdateFailure.SIGNATURE
        is UpdatePackageException -> UpdateFailure.PACKAGE
        is IOException -> UpdateFailure.NETWORK
        else -> UpdateFailure.FORMAT
    }

    class Factory(private val repository: UpdateRepository, private val version: String) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(UpdateViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return UpdateViewModel(version, repository::latest, repository::download, repository::cachedNotes, repository::notes) as T
        }
    }
}
