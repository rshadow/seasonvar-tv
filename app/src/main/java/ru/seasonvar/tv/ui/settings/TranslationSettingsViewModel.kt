package ru.seasonvar.tv.ui.settings

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
import ru.seasonvar.tv.data.TranslationPriorities
import ru.seasonvar.tv.data.TranslationCatalogRepository
import ru.seasonvar.tv.data.SiteHttpException
import ru.seasonvar.tv.ui.catalog.LoadFailure
import java.io.IOException

data class TranslationChoicesState(val loading: Boolean = false, val options: List<String> = emptyList(), val failure: LoadFailure? = null)

internal class TranslationSettingsViewModel(
    val preferred: StateFlow<List<String>>,
    private val save: (List<String>) -> Unit,
    private val load: suspend () -> List<String>,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TranslationChoicesState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun openChoices() {
        job?.cancel()
        mutableState.value = TranslationChoicesState(loading = true)
        job = viewModelScope.launch {
            try {
                val options = load()
                ensureActive()
                mutableState.value = TranslationChoicesState(options = options)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                ensureActive()
                mutableState.value = TranslationChoicesState(failure = when (error) {
                    is IOException -> LoadFailure.NETWORK
                    is SiteHttpException -> LoadFailure.HTTP
                    else -> LoadFailure.MARKUP
                })
            }
        }
    }
    fun closeChoices() { job?.cancel(); mutableState.value = TranslationChoicesState() }
    fun add(value: String): List<String> {
        val current = preferred.value
        val option = state.value.options.singleOrNull { TranslationPriorities.name(it) == TranslationPriorities.name(value) }
            ?: return current
        if (current.any { TranslationPriorities.name(it) == TranslationPriorities.name(option) }) return current
        return (current + option).also(save)
    }
    fun move(value: String, offset: Int): List<String> {
        val current = preferred.value
        val index = current.indexOf(value)
        val destination = index + offset
        if (offset !in listOf(-1, 1) || index < 0 || destination !in current.indices) return current
        return current.toMutableList().apply { add(destination, removeAt(index)) }.toList().also(save)
    }
    fun remove(value: String): List<String> {
        val current = preferred.value
        if (value !in current) return current
        return current.filterNot { it == value }.also(save)
    }

    class Factory(private val repository: TranslationCatalogRepository, private val settings: InterfaceSettings) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TranslationSettingsViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return TranslationSettingsViewModel(settings.preferredTranslations, settings::setPreferredTranslations, repository::load) as T
        }
    }
}
