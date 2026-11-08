package ru.seasonvar.tv.ui.catalog

import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.ui.filters.*
import java.time.LocalDate

class BestListFilterSource(private val repository: CatalogRepository, private val preferences: ListFilterPreferences) : ListFilterSource<CatalogPage> {
    override val fields = listOf(ListFilterField("genre", R.string.filter_genre, defaultValue = ""),
        ListFilterField("period", R.string.filter_period, defaultValue = "1"))
    private fun fields(options: BestFilterOptions) = listOf(fields[0].copy(options = options.genres), fields[1].copy(options = options.periods))
    override suspend fun options() = fields(repository.bestFilterOptions())
    override suspend fun load(values: Map<String, String>) = result(repository.best(LocalDate.now(), BestListFilters.from(values)))
    suspend fun cached(values: Map<String, String>) = repository.cachedBest(LocalDate.now(), BestListFilters.from(values))?.let(::result)
    private fun result(value: FilteredBestPage) = FilteredListPage(value.page, fields(value.options), value.filters.values())
    override fun restore(owner: String) = preferences.read(owner, "best")
    override fun save(owner: String, values: Map<String, String>) = preferences.write(owner, "best", values)
}
