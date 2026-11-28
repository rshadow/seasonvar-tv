package ru.seasonvar.tv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import ru.seasonvar.tv.data.network.ConnectionChecker
import ru.seasonvar.tv.data.network.ConnectionFailure
import ru.seasonvar.tv.data.network.ConnectionResult
import ru.seasonvar.tv.data.network.ConnectionStatus
import ru.seasonvar.tv.data.network.ConnectionTarget
import ru.seasonvar.tv.data.network.NetworkConfiguration
import ru.seasonvar.tv.data.settings.ConnectionCheckSettings

data class ConnectionCheckState(
    val results: Map<ConnectionTarget, ConnectionResult> = ConnectionTarget.entries.associateWith { ConnectionResult.checking },
) {
    val checking get() = results.values.any { it.status == ConnectionStatus.CHECKING }
    val failed get() = ConnectionTarget.entries.filter { results.getValue(it).status == ConnectionStatus.FAILED }
}

class ConnectionCheckViewModel internal constructor(
    private val configuration: StateFlow<NetworkConfiguration>,
    private val prepare: suspend (NetworkConfiguration) -> Unit = {},
    changes: StateFlow<Long>? = null,
    private val check: suspend (NetworkConfiguration, (ConnectionTarget, ConnectionResult) -> Unit) -> Unit,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ConnectionCheckState())
    val state = mutableState.asStateFlow()
    private var visible = false
    private var generation = 0L
    private var job: Job? = null
    private var checkedConfiguration: NetworkConfiguration? = null
    private var configuring = false

    suspend fun reconfigure(apply: suspend () -> Boolean): Boolean {
        configuring = true
        generation++
        job?.cancel()
        mutableState.value = ConnectionCheckState()
        try { return apply() }
        finally { configuring = false; refresh() }
    }

    init {
        if (changes != null) viewModelScope.launch { changes.collect { if (visible) refresh() } }
        viewModelScope.launch {
            configuration.collect { if (visible && it != checkedConfiguration) refresh() }
        }
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (value) refresh() else {
            generation++
            job?.cancel()
            finishPending(ConnectionFailure.NETWORK)
        }
    }

    fun refresh() {
        if (!visible) return
        if (configuring) return
        val current = ++generation
        job?.cancel()
        val selected = configuration.value
        checkedConfiguration = selected
        mutableState.value = ConnectionCheckState()
        job = viewModelScope.launch {
            var failure = ConnectionFailure.NETWORK
            try {
                withTimeout(ConnectionCheckSettings.CYCLE_TIMEOUT_MILLIS) {
                    prepare(selected)
                    check(selected) { target, result ->
                        if (current == generation && visible) mutableState.value = state.value.copy(
                            results = state.value.results + (target to result))
                    }
                }
            } catch (_: TimeoutCancellationException) { failure = ConnectionFailure.TIMEOUT }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { failure = ConnectionFailure.NETWORK }
            finally {
                if (current == generation) {
                    generation++
                    finishPending(failure)
                }
            }
        }
    }

    private fun finishPending(failure: ConnectionFailure) {
        mutableState.value = state.value.copy(results = state.value.results.mapValues { (_, result) ->
            if (result.status == ConnectionStatus.CHECKING) ConnectionResult.failed(failure) else result
        })
    }

    class Factory(private val configuration: StateFlow<NetworkConfiguration>,
        private val pac: ru.seasonvar.tv.data.network.pac.PacCoordinator? = null,
        private val prepare: suspend (NetworkConfiguration) -> Unit = {}) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ConnectionCheckViewModel(configuration, prepare, pac?.changes, ConnectionChecker(pac = pac)::check) as T
        }
    }
}
