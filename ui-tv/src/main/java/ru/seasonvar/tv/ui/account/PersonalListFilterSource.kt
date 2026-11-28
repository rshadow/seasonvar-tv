package ru.seasonvar.tv.ui.account

import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.account.*
import ru.seasonvar.tv.ui.filters.*

class PersonalListFilterSource(
    private val account: AccountRepository,
    private val feed: PersonalFeed,
    private val preferences: ListFilterPreferences,
) : ListFilterSource<AccountPage> {
    init { require(feed != PersonalFeed.HISTORY) }
    override val fields = listOf(ListFilterField("genre", R.string.filter_genre), ListFilterField("country", R.string.filter_country))
    private fun fields(options: PersonalFilterOptions) = listOf(
        fields[0].copy(options = options.genres), fields[1].copy(options = options.countries),
    )
    override suspend fun options() = fields(account.personalFilterOptions())
    override suspend fun load(values: Map<String, String>): FilteredListPage<AccountPage> {
        val result = account.filteredPersonalList(feed, PersonalListFilters.from(values), windowed = true)
        return FilteredListPage(result.page, fields(result.options), result.filters.values())
    }
    override suspend fun cached(values: Map<String, String>): FilteredListPage<AccountPage>? {
        val result = account.cachedFilteredPersonalList(feed, PersonalListFilters.from(values), windowed = true) ?: return null
        return FilteredListPage(result.page, fields(result.options), result.filters.values())
    }
    override fun restore(owner: String) = preferences.read(owner, feed.catalogKey)
    override fun save(owner: String, values: Map<String, String>) = preferences.write(owner, feed.catalogKey, values)
}
