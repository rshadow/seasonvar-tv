package ru.seasonvar.tv.ui.catalog

import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.ui.filters.*
import java.time.LocalDate

class BestListFilterSource(private val repository: CatalogRepository, private val preferences: ListFilterPreferences,
    private val account: ru.seasonvar.tv.data.account.AccountRepository? = null,
    private val accountOwner: () -> String? = { null }) : ListFilterSource<CatalogPage> {
    private val selectors = listOf(ListFilterField("genre", R.string.filter_genre, defaultValue = ""),
        ListFilterField("period", R.string.filter_period, defaultValue = "1"))
    private fun toggle(flag: BestSwitch): ListFilterField {
        val title = when (flag) {
            BestSwitch.FHD -> R.string.best_filter_fhd
            BestSwitch.HD -> R.string.best_filter_hd
            BestSwitch.SUBTITLES -> R.string.best_filter_subtitles
            BestSwitch.FOREIGN_ONLY -> R.string.best_filter_russian
            BestSwitch.AVAILABLE -> R.string.best_filter_available
            BestSwitch.HIDE_WATCHING -> R.string.best_filter_watching
            BestSwitch.HIDE_HISTORY -> R.string.best_filter_history
            BestSwitch.HIDE_BLACKLIST -> R.string.best_filter_blacklist
        }
        return ListFilterField(flag.id, title, defaultValue = (flag == BestSwitch.FOREIGN_ONLY).toString(),
            kind = ListFilterKind.TOGGLE, activeTitle = when (flag) {
                BestSwitch.FHD -> R.string.best_filter_summary_fhd
                BestSwitch.HD -> R.string.best_filter_summary_hd
                BestSwitch.AVAILABLE -> R.string.best_filter_summary_available
                BestSwitch.FOREIGN_ONLY -> R.string.best_filter_foreign
                BestSwitch.HIDE_WATCHING -> R.string.best_filter_summary_watching
                BestSwitch.HIDE_HISTORY -> R.string.best_filter_summary_history
                BestSwitch.HIDE_BLACKLIST -> R.string.best_filter_summary_blacklist
                else -> title
            })
    }
    private fun allowed() = BestSwitch.entries.filter { !it.personal || accountOwner() != null }
    override val fields get() = selectors + allowed().map(::toggle)
    private fun fields(options: BestFilterOptions) = listOf(selectors[0].copy(options = options.genres), selectors[1].copy(options = options.periods)) +
        allowed().filter { it in options.switches }.map(::toggle)
    override suspend fun options() = fields(repository.bestFilterOptions())
    override suspend fun load(values: Map<String, String>): FilteredListPage<CatalogPage> {
        val requested = BestListFilters.from(values)
        val filters = requested.copy(switches = requested.switches.filter { !it.personal || accountOwner() != null }.toSet())
        return result(repository.best(LocalDate.now(), filters, account?.let { it::bestFilterMetadata }))
    }
    override suspend fun cached(values: Map<String, String>) = repository.cachedBest(LocalDate.now(), BestListFilters.from(values))?.let(::result)
    private fun result(value: FilteredBestPage): FilteredListPage<CatalogPage> {
        val fields = fields(value.options)
        return FilteredListPage(value.page, fields, value.filters.values().filterKeys { id -> fields.any { it.id == id } })
    }
    private fun personal(id: String) = BestSwitch.entries.any { it.personal && it.id == id }
    override fun restore(owner: String) = preferences.read(owner, "best").filterKeys { !personal(it) } +
        accountOwner()?.let { preferences.read(it, "best-personal").filterKeys(::personal) }.orEmpty()
    override fun save(owner: String, values: Map<String, String>) {
        preferences.write(owner, "best", values.filterKeys { !personal(it) })
        accountOwner()?.let { preferences.write(it, "best-personal", values.filterKeys(::personal)) }
    }
}
