package ru.seasonvar.tv.ui.catalog

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
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.AccountRepository
import ru.seasonvar.tv.ui.filters.ListFilterPreferences

data class BrowseCatalogState(
    val page: CatalogUiState = CatalogUiState(loading = false),
    val options: CatalogFilterOptions? = null,
    val applied: CatalogFilters = CatalogFilters(),
    val draft: CatalogFilters = CatalogFilters(),
) {
    val changed get() = CatalogFilters.defaults.keys.any { applied[it] != draft[it] }
}

interface BrowseCatalogController {
    val state: StateFlow<BrowseCatalogState>
    fun activate()
    fun refresh()
    fun choose(key: String, value: String)
    fun apply()
    fun reset()
}

class BrowseCatalogViewModel(
    private val repository: CatalogRepository,
    private val preferences: ListFilterPreferences = ListFilterPreferences(),
    private val account: AccountRepository? = null,
    private val owner: () -> String? = { null },
) : ViewModel(), BrowseCatalogController {
    private fun restored() = CatalogFilters(preferences.read("public", "catalog").filterKeys { it !in CatalogFilters.personal } +
        owner()?.let { preferences.read(it, "catalog-personal").filterKeys { key -> key in CatalogFilters.personal } }.orEmpty())
    private val mutableState = MutableStateFlow(restored().let { BrowseCatalogState(applied = it, draft = it) })
    override val state = mutableState.asStateFlow()
    init { manageListParsing(viewModelScope, state) { it.page.entries } }
    private var job: Job? = null
    private var started = false
    private var generation = 0L

    override fun activate() { if (!started) refresh() }
    override fun refresh() = request(state.value.applied, applying = false)
    override fun apply() { if (!state.value.page.loading && state.value.changed) request(state.value.draft, applying = true) }
    override fun reset() {
        if (state.value.page.loading) return
        mutableState.value = state.value.copy(draft = CatalogFilters())
        request(CatalogFilters(), applying = true)
    }
    override fun choose(key: String, value: String) {
        if (state.value.page.loading || key !in CatalogFilters.defaults) return
        mutableState.value = state.value.copy(draft = state.value.draft.with(key, value))
    }

    private fun request(requested: CatalogFilters, applying: Boolean) {
        started = true
        job?.cancel()
        val expected = ++generation
        val expectedOwner = owner()
        mutableState.value = state.value.copy(page = state.value.page.copy(loading = true, failure = null))
        job = viewModelScope.launch {
            fun current() = expected == generation && owner() == expectedOwner
            try {
                if (!applying && !state.value.page.loaded) {
                    val options = repository.cachedCatalogOptions()
                    val filters = options?.normalize(requested, expectedOwner != null)
                    if (filters != null) repository.cachedCatalog(filters)?.let { page ->
                        ensureActive()
                        if (current()) mutableState.value = BrowseCatalogState(
                            CatalogUiState(date = page.date, entries = page.entries, loaded = true, cached = true), options, filters, filters)
                    }
                }
                val options = repository.catalogOptions(if (expectedOwner != null) account?.let { it::catalogMetadata } else null)
                ensureActive()
                if (!current()) return@launch
                val filters = options.normalize(requested, expectedOwner != null)
                mutableState.value = state.value.copy(options = options)
                val page = repository.catalog(filters)
                ensureActive()
                if (!current()) return@launch
                preferences.write("public", "catalog", filters.values.filterKeys { it !in CatalogFilters.personal })
                expectedOwner?.let { preferences.write(it, "catalog-personal", filters.values.filterKeys { key -> key in CatalogFilters.personal }) }
                val draft = if (applying || !state.value.changed) filters else options.normalize(state.value.draft, expectedOwner != null)
                mutableState.value = BrowseCatalogState(CatalogUiState(date = page.date, entries = page.entries, loading = false, loaded = true),
                    options, filters, draft)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                ensureActive()
                if (current()) mutableState.value = state.value.copy(page = state.value.page.copy(loading = false, failure = when (e) {
                    is MarkupChangedException -> LoadFailure.MARKUP
                    is SiteHttpException -> LoadFailure.HTTP
                    else -> LoadFailure.NETWORK
                }))
            }
        }
    }

    fun sessionChanged() {
        val reload = started
        job?.cancel(); generation++
        mutableState.value = restored().let { BrowseCatalogState(applied = it, draft = it) }
        started = false
        if (reload) refresh()
    }

    fun refreshIfStarted() { if (started) refresh() }

    class Factory(private val repository: CatalogRepository, private val preferences: ListFilterPreferences,
        private val account: AccountRepository, private val owner: () -> String?) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BrowseCatalogViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return BrowseCatalogViewModel(repository, preferences, account, owner) as T
        }
    }
}
