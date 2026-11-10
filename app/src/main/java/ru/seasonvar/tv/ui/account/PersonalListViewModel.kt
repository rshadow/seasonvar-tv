package ru.seasonvar.tv.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.*
import ru.seasonvar.tv.ui.catalog.*
import java.io.IOException
import ru.seasonvar.tv.ui.filters.*
import ru.seasonvar.tv.data.playback.markOwner

class PersonalListViewModel internal constructor(
    private val session: StateFlow<AccountSession>,
    private val load: suspend () -> AccountPage,
    private val loadNext: suspend (String) -> AccountPage = { throw MarkupChangedException() },
    private val filterSource: ListFilterSource<AccountPage>? = null,
    private val cached: suspend () -> AccountPage? = { null },
) : ViewModel() {
    constructor(account: AccountRepository, feed: PersonalFeed,
        preferences: ListFilterPreferences = ListFilterPreferences()) :
        this(account.session, { account.personalList(feed, windowed = true) }, { account.personalList(feed, it, windowed = true) },
            if (feed == PersonalFeed.HISTORY) null else PersonalListFilterSource(account, feed, preferences),
            { account.cachedPersonalList(feed, windowed = true) })
    private val mutableState = MutableStateFlow(CatalogUiState(loading = false))
    val state = mutableState.asStateFlow()
    init { ru.seasonvar.tv.ui.catalog.manageListParsing(viewModelScope, state) { it.entries } }
    private var refreshJob: Job? = null
    private var moreJob: Job? = null
    private var nextPage: String? = null
    private val visitedPages = mutableSetOf<String>()
    private var version = -1L
    private var active = false
    private var dirty = false
    private var generation = 0L
    private var filterOptionsJob: Job? = null
    private var filterApplyJob: Job? = null
    private val mutableFilters = MutableStateFlow(initialFilters())
    val filters: ListFiltersController? = filterSource?.let { object : ListFiltersController {
        override val state = mutableFilters.asStateFlow()
        override fun open() = openFilters()
        override fun dismiss() = dismissFilters()
        override fun choose(field: String, value: String) {
            val current = state.value
            if (!current.loading && !current.applying && current.fields.find { it.id == field }?.options?.any { it.value == value } == true)
                mutableFilters.value = current.copy(draft = current.draft + (field to value), applyFailure = null)
        }
        override fun reset() {
            val current = state.value
            if (!current.open || current.loading || current.applying || current.failure != null) return
            mutableFilters.value = current.copy(draft = defaults(), applyFailure = null)
            applyFilters()
        }
        override fun apply() = applyFilters()
    } }
    private fun defaults() = filterSource?.fields.orEmpty().associate { it.id to it.defaultValue }
    private fun initialFilters(owner: String? = null): ListFiltersState {
        val saved = owner?.let { filterSource?.restore(it) }.orEmpty()
        val values = defaults().mapValues { (key, value) -> saved[key] ?: value }
        return ListFiltersState(fields = filterSource?.fields.orEmpty(), values = values, draft = values)
    }
    init { viewModelScope.launch { session.collect { session ->
        if (version != session.version) {
            version = session.version
            generation++
            dirty = false
            refreshJob?.cancel(); moreJob?.cancel()
            filterOptionsJob?.cancel(); filterApplyJob?.cancel()
            mutableFilters.value = initialFilters(markOwner(session.user))
            nextPage = null; visitedPages.clear()
            mutableState.value = CatalogUiState(loading = false)
            if (active && session.user != null) refresh()
        }
    } } }
    fun activate() { active = true; if ((dirty || !state.value.loaded) && !state.value.loading) refresh() }
    fun invalidate() {
        dirty = true
        generation++
        refreshJob?.cancel(); moreJob?.cancel()
        nextPage = null; visitedPages.clear()
        mutableState.value = state.value.copy(loading = false, loadingMore = false, moreFailure = null, hasMore = false)
    }
    fun refreshIfActive() { if (active) refresh() }
    fun historyCleared() {
        invalidate()
        dirty = false
        mutableState.value = CatalogUiState(loading = false, loaded = true)
    }
    fun retryOrRefresh() { if (state.value.moreFailure != null) loadMore(retry = true) else refresh() }
    fun refresh() {
        if (session.value.user == null) return
        if (mutableFilters.value.applying) return
        refreshJob?.cancel(); moreJob?.cancel()
        val expectedGeneration = ++generation
        nextPage = null; visitedPages.clear()
        mutableState.value = state.value.copy(loading = true, failure = null, loadingMore = false, moreFailure = null, hasMore = false)
        val expected = session.value.version
        val expectedOwner = markOwner(session.value.user)
        refreshJob = viewModelScope.launch {
            try {
                if (!state.value.loaded) {
                    val filteredCache = filterSource?.cached(mutableFilters.value.values)
                    val saved = filteredCache?.page ?: if (filterSource == null) cached() else null
                    ensureActive()
                    if (saved != null && session.value.version == expected && generation == expectedGeneration) {
                        mutableState.value = state.value.copy(entries = saved.entries, loaded = true, cached = true,
                            parsingWindow = (saved.entries as? ParsedSeriesEntries)?.window)
                    }
                }
                val filtered = filterSource?.load(mutableFilters.value.values)
                val page = filtered?.page ?: load()
                ensureActive()
                if (session.value.version == expected && generation == expectedGeneration) {
                    dirty = false
                    nextPage = page.nextPage
                    mutableState.value = CatalogUiState(entries = page.entries, loading = false, loaded = true, hasMore = page.nextPage != null)
                    if (filtered != null) acceptFilters(filtered, close = false, owner = expectedOwner)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                ensureActive()
                if (session.value.version == expected && generation == expectedGeneration) mutableState.value = state.value.copy(loading = false,
                    failure = when (e) { is IOException -> LoadFailure.NETWORK; is SiteHttpException -> LoadFailure.HTTP; else -> LoadFailure.MARKUP })
            }
        }
    }
    private fun acceptFilters(result: FilteredListPage<AccountPage>, close: Boolean, owner: String?) {
        val current = mutableFilters.value
        mutableFilters.value = current.copy(open = current.open && !close, fields = result.fields,
            values = result.values, draft = if (current.open && !close) current.draft else result.values,
            applying = false, applyFailure = null)
        owner?.let { filterSource?.save(it, result.values) }
    }
    private fun openFilters() {
        val source = filterSource ?: return
        if (session.value.user == null || mutableFilters.value.applying) return
        filterOptionsJob?.cancel()
        val expected = session.value.version
        mutableFilters.value = mutableFilters.value.copy(open = true, draft = mutableFilters.value.values,
            loading = true, failure = null, applyFailure = null)
        filterOptionsJob = viewModelScope.launch {
            try {
                val fields = source.options()
                ensureActive()
                if (session.value.version != expected) return@launch
                val current = mutableFilters.value
                val draft = fields.associate { field -> field.id to (current.draft[field.id]
                    ?.takeIf { value -> field.options.any { it.value == value } } ?: field.defaultValue) }
                mutableFilters.value = current.copy(fields = fields, draft = draft, loading = false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                ensureActive()
                if (session.value.version == expected) mutableFilters.value = mutableFilters.value.copy(loading = false, failure = failure(e))
            }
        }
    }
    private fun dismissFilters() {
        filterOptionsJob?.cancel(); filterApplyJob?.cancel()
        mutableFilters.value = mutableFilters.value.copy(open = false, loading = false, applying = false,
            draft = mutableFilters.value.values, applyFailure = null)
    }
    private fun applyFilters() {
        val source = filterSource ?: return
        val selected = mutableFilters.value
        if (!selected.open || selected.loading || selected.applying || selected.failure != null || session.value.user == null) return
        if (selected.draft == selected.values) { dismissFilters(); return }
        refreshJob?.cancel(); moreJob?.cancel()
        val expectedGeneration = ++generation
        val expected = session.value.version
        val expectedOwner = markOwner(session.value.user)
        mutableFilters.value = selected.copy(applying = true, applyFailure = null)
        mutableState.value = state.value.copy(loading = false, loadingMore = false)
        filterApplyJob = viewModelScope.launch {
            try {
                val result = source.load(selected.draft)
                ensureActive()
                if (session.value.version != expected || generation != expectedGeneration) return@launch
                dirty = false; visitedPages.clear(); nextPage = result.page.nextPage
                mutableState.value = CatalogUiState(entries = result.page.entries, loaded = true, loading = false, hasMore = nextPage != null)
                acceptFilters(result, close = true, owner = expectedOwner)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                ensureActive()
                if (session.value.version == expected && generation == expectedGeneration) {
                    mutableFilters.value = mutableFilters.value.copy(applying = false, applyFailure = failure(e))
                }
            }
        }
    }
    private fun failure(e: Exception) = when (e) { is IOException -> LoadFailure.NETWORK; is SiteHttpException -> LoadFailure.HTTP; else -> LoadFailure.MARKUP }
    fun loadMore(retry: Boolean = false) {
        val parsed = (state.value.entries as? ParsedSeriesEntries)?.window?.state?.value
        if (parsed?.hasMore == true || parsed?.failed == true) return
        if (dirty) { activate(); return }
        val next = nextPage ?: return
        if (session.value.user == null || state.value.loading || state.value.loadingMore || (!retry && state.value.moreFailure != null)) return
        val expected = session.value.version
        val expectedGeneration = generation
        mutableState.value = state.value.copy(loadingMore = true, moreFailure = null)
        moreJob = viewModelScope.launch {
            try {
                val page = loadNext(next)
                ensureActive()
                if (session.value.version != expected || generation != expectedGeneration) return@launch
                if (next in visitedPages || page.nextPage == next || page.nextPage in visitedPages ||
                    (page.entries.isEmpty() && page.nextPage != null)) throw MarkupChangedException()
                val previous = (state.value.entries as? ParsedSeriesEntries)?.window?.state?.value?.entries ?: state.value.entries
                val entries = (page.entries as? ParsedSeriesEntries)?.prepend(previous) ?: (previous + page.entries)
                if (entries.map { it.key }.toSet().size != entries.size) throw MarkupChangedException()
                visitedPages += next
                nextPage = page.nextPage
                mutableState.value = state.value.copy(entries = entries, loadingMore = false, hasMore = page.nextPage != null,
                    parsingWindow = (entries as? ParsedSeriesEntries)?.window)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                ensureActive()
                if (session.value.version == expected && generation == expectedGeneration) mutableState.value = state.value.copy(loadingMore = false,
                    moreFailure = when (e) { is IOException -> LoadFailure.NETWORK; is SiteHttpException -> LoadFailure.HTTP; else -> LoadFailure.MARKUP })
            }
        }
    }
    fun markRemoved(target: AccountListMark) {
        val parsed = (state.value.entries as? ParsedSeriesEntries)?.window
        if (parsed != null) {
            viewModelScope.launch { parsed.excludeSeason(target.seasonId) }
            return
        }
        val entries = state.value.entries.filterNot { it.accountListMark?.seasonId == target.seasonId }
        mutableState.value = state.value.copy(entries = entries)
    }

    class Factory(private val account: AccountRepository, private val feed: PersonalFeed,
        private val preferences: ListFilterPreferences = ListFilterPreferences()) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PersonalListViewModel::class.java))
            @Suppress("UNCHECKED_CAST") return PersonalListViewModel(account, feed, preferences) as T
        }
    }
}
