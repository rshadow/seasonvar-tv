package ru.seasonvar.tv.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.SiteHttpException
import ru.seasonvar.tv.data.account.*
import ru.seasonvar.tv.data.playback.AccountMarkWriter
import ru.seasonvar.tv.ui.catalog.LoadFailure
import java.io.IOException

enum class RemovalStage { ACTIONS, CONFIRM }
data class MarkRemovalUiState(
    val entry: SeriesEntry? = null,
    val stage: RemovalStage = RemovalStage.ACTIONS,
    val visible: Boolean = false,
    val busy: Boolean = false,
    val failure: LoadFailure? = null,
)

class MarkRemovalViewModel internal constructor(
    private val session: StateFlow<AccountSession>,
    private val remove: suspend (AccountListMark, Long) -> Unit,
    private val onRemoved: (AccountListMark) -> Unit,
) : ViewModel() {
    private val mutableState = MutableStateFlow(MarkRemovalUiState())
    val state = mutableState.asStateFlow()
    private var ownerVersion = -1L
    private var job: Job? = null
    init { viewModelScope.launch { session.collect { current ->
        if (state.value.entry != null && current.version != ownerVersion) {
            job?.cancel()
            mutableState.value = MarkRemovalUiState()
        }
    } } }

    fun show(entry: SeriesEntry, stage: RemovalStage = RemovalStage.ACTIONS) {
        if (state.value.busy || session.value.user == null || entry.accountListMark == null) return
        ownerVersion = session.value.version
        mutableState.value = MarkRemovalUiState(entry, stage, visible = true)
    }
    fun ask() { if (!state.value.busy) mutableState.value = state.value.copy(stage = RemovalStage.CONFIRM, failure = null) }
    fun dismiss() {
        mutableState.value = if (state.value.busy) state.value.copy(visible = false) else MarkRemovalUiState()
    }
    fun confirm() {
        val current = state.value
        val target = current.entry?.accountListMark ?: return
        if (current.busy || current.stage != RemovalStage.CONFIRM || session.value.version != ownerVersion) return
        mutableState.value = current.copy(busy = true, failure = null)
        val version = ownerVersion
        job = viewModelScope.launch {
            try {
                remove(target, version)
                ensureActive()
                if (session.value.version != version) return@launch
                onRemoved(target)
                mutableState.value = MarkRemovalUiState()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                ensureActive()
                if (session.value.version == version) mutableState.value = state.value.copy(busy = false, failure = when (error) {
                    is IOException -> LoadFailure.NETWORK
                    is SiteHttpException -> LoadFailure.HTTP
                    else -> LoadFailure.MARKUP
                })
            }
        }
    }
    class Factory(private val account: AccountRepository, private val writer: AccountMarkWriter,
        private val onRemoved: (AccountListMark) -> Unit) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MarkRemovalViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return MarkRemovalViewModel(account.session, writer::removeMark, onRemoved) as T
        }
    }
}
