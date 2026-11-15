package ru.seasonvar.tv.ui.catalog

import kotlinx.coroutines.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ru.seasonvar.tv.data.network.NetworkConfiguration
import ru.seasonvar.tv.ui.navigation.Section

internal class ListRefreshCoordinator(
    private val scope: CoroutineScope?,
    initial: NetworkConfiguration,
    private val prepare: suspend (NetworkConfiguration) -> Unit,
    private val targets: Map<Section, Target>,
) : ViewModel() {
    class Target(val cancel: () -> Unit, val refresh: () -> Unit)
    var visible: Section? = null
        private set
    private var configuration = initial
    private var revision = 0L
    private var job: Job? = null

    fun setVisible(section: Section?) { visible = section?.takeIf { it in targets } }

    fun networkChanged(selected: NetworkConfiguration) {
        if (configuration == selected) return
        configuration = selected
        val expected = ++revision
        job?.cancel()
        targets.values.forEach { it.cancel() }
        job = (scope ?: viewModelScope).launch {
            try { prepare(selected) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { }
            ensureActive()
            if (revision != expected) return@launch
            val first = visible
            targets[first]?.refresh?.invoke()
            targets.forEach { (section, target) -> if (section != first) target.refresh() }
        }
    }

    class Factory(private val initial: NetworkConfiguration,
        private val prepare: suspend (NetworkConfiguration) -> Unit,
        private val targets: Map<Section, Target>) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ListRefreshCoordinator::class.java))
            @Suppress("UNCHECKED_CAST")
            return ListRefreshCoordinator(null, initial, prepare, targets) as T
        }
    }
}
