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
    private val preloadOnStart: Boolean = false,
) : ViewModel() {
    class Target(val cancel: () -> Unit, val refresh: () -> Unit, val preload: (() -> Unit)? = null)
    var visible: Section? = null
        private set
    private var configuration = initial
    private var revision = 0L
    private var job: Job? = null
    private val preloaded = mutableSetOf<Section>()

    fun setVisible(section: Section?) { visible = section?.takeIf { it in targets } }

    fun preloadMenuLists(accountBusy: Boolean, signedIn: Boolean, hiddenSections: Set<Section>) {
        if (!preloadOnStart || accountBusy) return
        val eligible = Section.catalogPages + if (signedIn) Section.smartPages + Section.personalPages else emptyList()
        for (section in eligible) {
            if (section in hiddenSections) continue
            val preload = targets[section]?.preload ?: continue
            if (preloaded.add(section)) preload()
        }
    }

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
        private val targets: Map<Section, Target>, private val preloadOnStart: Boolean = false) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ListRefreshCoordinator::class.java))
            @Suppress("UNCHECKED_CAST")
            return ListRefreshCoordinator(null, initial, prepare, targets, preloadOnStart) as T
        }
    }
}
