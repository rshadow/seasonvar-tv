package ru.seasonvar.tv.data.account

import ru.seasonvar.tv.data.*

internal class ResumeSnapshot(
    val user: AccountUser,
    private val tabs: Map<String, List<Lazy<SeriesEntry>>>,
    private val legacy: List<Lazy<SeriesEntry>>,
    val options: Result<PersonalFilterOptions>,
    private val duplicateTabs: Set<String>,
) {
    fun page(feed: PersonalFeed, windowed: Boolean): AccountPage {
        require(feed != PersonalFeed.HISTORY)
        if (feed.tabs.any { it in duplicateTabs }) throw MarkupChangedException()
        val selected = tabs.filterKeys { it in feed.tabs }
        if (selected.size != feed.tabs.size && (feed != PersonalFeed.RESUME || selected.isNotEmpty()))
            throw MarkupChangedException()
        val rows = if (selected.isNotEmpty()) selected.values.asSequence().flatten().map { it.value }
            else legacy.asSequence().map { it.value }.distinctBy { it.url }
        val seen = HashSet<String>()
        return AccountPage(user, rows.onEach { if (!seen.add(it.key)) throw MarkupChangedException() }.parseEntries(windowed))
    }

    val progress by lazy {
        AccountProgressParser.snapshot(page(PersonalFeed.RESUME, false).entries + page(PersonalFeed.WATCHED, false).entries)
    }
}
