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
import ru.seasonvar.tv.data.SiteHttpException
import ru.seasonvar.tv.data.account.AccountRepository
import ru.seasonvar.tv.data.account.AccountSession
import ru.seasonvar.tv.ui.catalog.LoadFailure
import java.io.IOException

data class HistoryClearUiState(val visible: Boolean = false, val busy: Boolean = false, val failure: LoadFailure? = null)

interface HistoryClearController {
    val state: StateFlow<HistoryClearUiState>
    fun ask()
    fun dismiss()
    fun confirm()
}

class HistoryClearViewModel internal constructor(
    private val session: StateFlow<AccountSession>,
    private val clear: suspend (Long) -> Unit,
    private val onStarted: () -> Unit,
    private val onHistoryCleared: () -> Unit,
) : ViewModel(), HistoryClearController {
    private val mutableState = MutableStateFlow(HistoryClearUiState())
    override val state = mutableState.asStateFlow()
    private var ownerVersion = -1L
    private var job: Job? = null

    init {
        viewModelScope.launch {
            session.collect { current ->
                if ((state.value.visible || state.value.busy) && current.version != ownerVersion) {
                    job?.cancel()
                    mutableState.value = HistoryClearUiState()
                }
            }
        }
    }

    override fun ask() {
        if (state.value.busy || session.value.user == null) return
        ownerVersion = session.value.version
        mutableState.value = HistoryClearUiState(visible = true)
    }
    override fun dismiss() {
        mutableState.value = if (state.value.busy) state.value.copy(visible = false) else HistoryClearUiState()
    }

    override fun confirm() {
        val current = state.value
        if (!current.visible || current.busy || session.value.user == null || session.value.version != ownerVersion) return
        val expected = ownerVersion
        mutableState.value = current.copy(busy = true, failure = null)
        onStarted()
        job = viewModelScope.launch {
            try {
                clear(expected)
                ensureActive()
                if (session.value.version != expected) return@launch
                onHistoryCleared()
                mutableState.value = HistoryClearUiState()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ensureActive()
                if (session.value.version == expected) {
                    mutableState.value = state.value.copy(busy = false, failure = when (error) {
                        is IOException -> LoadFailure.NETWORK
                        is SiteHttpException -> LoadFailure.HTTP
                        else -> LoadFailure.MARKUP
                    })
                }
            }
        }
    }
    class Factory(
        private val account: AccountRepository,
        private val onStarted: () -> Unit,
        private val onCleared: () -> Unit,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HistoryClearViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return HistoryClearViewModel(account.session, account::clearHistory, onStarted, onCleared) as T
        }
    }
}
