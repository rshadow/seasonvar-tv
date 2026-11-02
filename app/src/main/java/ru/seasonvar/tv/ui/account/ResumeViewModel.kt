package ru.seasonvar.tv.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.*
import ru.seasonvar.tv.ui.catalog.*
import java.io.IOException

class ResumeViewModel(private val account: AccountRepository, private val catalog: CatalogRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(CatalogUiState(loading = false))
    val state = mutableState.asStateFlow()
    private val mutableDescription = MutableStateFlow(DescriptionState())
    val description = mutableDescription.asStateFlow()
    private var refreshJob: Job? = null
    private var descriptionJob: Job? = null
    private var version = -1L
    private var active = false
    init { viewModelScope.launch { account.session.collect { session ->
        if (version != session.version) {
            version = session.version
            refreshJob?.cancel(); descriptionJob?.cancel()
            mutableState.value = CatalogUiState(loading = false)
            mutableDescription.value = DescriptionState()
            if (active && session.user != null) refresh()
        }
    } } }
    fun activate() { active = true; if (!state.value.loaded && !state.value.loading) refresh() }
    fun refresh() {
        if (account.session.value.user == null) return
        refreshJob?.cancel()
        mutableState.value = state.value.copy(loading = true, failure = null)
        val expected = account.session.value.version
        refreshJob = viewModelScope.launch {
            try {
                val page = account.resume()
                if (account.session.value.version == expected) mutableState.value = CatalogUiState(entries = page.entries, loading = false, loaded = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (account.session.value.version == expected) mutableState.value = state.value.copy(loading = false,
                    failure = when (e) { is IOException -> LoadFailure.NETWORK; is SiteHttpException -> LoadFailure.HTTP; else -> LoadFailure.MARKUP })
            }
        }
    }
    fun select(entry: SeriesEntry, retry: Boolean) {
        if (!retry && description.value.key == entry.key) return
        descriptionJob?.cancel()
        mutableDescription.value = DescriptionState(entry.key, loading = true)
        val expected = account.session.value.version
        descriptionJob = viewModelScope.launch {
            try {
                delay(180)
                val result = catalog.description(entry)
                if (account.session.value.version == expected) mutableDescription.value = DescriptionState(entry.key, result)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (account.session.value.version == expected) mutableDescription.value = DescriptionState(entry.key, failed = true) }
        }
    }
    class Factory(private val account: AccountRepository, private val catalog: CatalogRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ResumeViewModel::class.java))
            @Suppress("UNCHECKED_CAST") return ResumeViewModel(account, catalog) as T
        }
    }
}
