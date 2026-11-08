package ru.seasonvar.tv.ui.smart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.*
import ru.seasonvar.tv.data.playback.markOwner
import ru.seasonvar.tv.data.smart.*
import ru.seasonvar.tv.ui.catalog.*
import java.io.IOException

class SmartContinueViewModel internal constructor(
    private val session: StateFlow<AccountSession>,
    private val load: suspend () -> SmartContinuePage,
    private val cache: SmartContinueCache,
    private val describe: suspend (SeriesEntry) -> SeriesDescription,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CatalogUiState(loading = false))
    val state = mutableState.asStateFlow()
    private val mutableDescription = MutableStateFlow(DescriptionState())
    val description = mutableDescription.asStateFlow()
    private var refreshJob: Job? = null
    private var descriptionJob: Job? = null
    private var describedSeason: String? = null
    private var visible = false
    private var dirty = true
    private var owner: String? = null
    private var ownerVersion = -1L
    init { viewModelScope.launch { session.collect { current ->
        val next = markOwner(current.user)
        if (ownerVersion != current.version) {
            ownerVersion = current.version
            val previous = owner
            owner = next
            refreshJob?.cancel(); descriptionJob?.cancel()
            mutableState.value = CatalogUiState(loading = false)
            mutableDescription.value = DescriptionState()
            dirty = true
            if (previous != null && previous != next) cache.clear(previous)
            if (visible && next != null) refresh()
        }
    } } }

    fun setVisible(value: Boolean) {
        val entering = value && !visible
        visible = value
        if (value && (entering || dirty || !state.value.loaded) && !state.value.loading) refresh()
    }
    fun invalidate() {
        dirty = true
        refreshJob?.cancel()
        if (visible) refresh() else mutableState.value = state.value.copy(loading = false)
    }
    fun refresh() {
        if (session.value.user == null) return
        val expectedOwner = markOwner(session.value.user) ?: run {
            mutableState.value = state.value.copy(loading = false, failure = LoadFailure.MARKUP)
            return
        }
        val version = session.value.version
        refreshJob?.cancel()
        mutableState.value = state.value.copy(loading = true, failure = null)
        refreshJob = viewModelScope.launch {
            val cached = if (state.value.loaded) state.value.entries else cache.read(expectedOwner)
            ensureActive()
            if (session.value.version != version) return@launch
            mutableState.value = state.value.copy(entries = cached.orEmpty(), loaded = cached != null, cached = cached != null)
            try {
                val page = load()
                ensureActive()
                if (session.value.version != version) return@launch
                val merged = if (!page.incomplete) page.entries else page.entries + cached.orEmpty().filter {
                    it.key !in page.resolvedKeys && page.entries.none { fresh -> fresh.key == it.key }
                }
                cache.write(expectedOwner, merged)
                ensureActive()
                if (session.value.version != version) return@launch
                dirty = page.incomplete
                mutableState.value = CatalogUiState(entries = merged, loading = false, loaded = true,
                    cached = page.incomplete, failure = page.failure?.let(::failure) ?: if (page.incomplete) LoadFailure.MARKUP else null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                ensureActive()
                if (session.value.version == version) mutableState.value = state.value.copy(loading = false,
                    failure = failure(e))
            }
        }
    }
    private fun failure(error: Exception) = when (error) {
        is IOException -> LoadFailure.NETWORK
        is SiteHttpException -> LoadFailure.HTTP
        else -> LoadFailure.MARKUP
    }
    fun select(entry: SeriesEntry, retry: Boolean) {
        if (!retry && description.value.key == entry.key && describedSeason == entry.id) return
        descriptionJob?.cancel()
        describedSeason = entry.id
        mutableDescription.value = DescriptionState(entry.key, loading = true)
        val version = session.value.version
        descriptionJob = viewModelScope.launch {
            try {
                delay(180)
                val text = describe(entry)
                if (session.value.version == version) mutableDescription.value = DescriptionState(entry.key, text)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (session.value.version == version) mutableDescription.value = DescriptionState(entry.key, failed = true) }
        }
    }
    class Factory(private val account: AccountRepository, private val catalog: CatalogRepository,
        private val cache: SmartContinueCache, private val preferences: () -> List<String>) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SmartContinueViewModel::class.java))
            val repository = SmartContinueRepository(account, preferences)
            @Suppress("UNCHECKED_CAST")
            return SmartContinueViewModel(account.session, repository::load, cache, catalog::description) as T
        }
    }
}
