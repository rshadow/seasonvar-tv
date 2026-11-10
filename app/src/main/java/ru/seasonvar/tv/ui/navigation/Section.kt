package ru.seasonvar.tv.ui.navigation

import ru.seasonvar.tv.data.PublicFeed
import ru.seasonvar.tv.data.account.PersonalFeed

internal enum class MenuGroup { CATALOG, SMART, PERSONAL, SYSTEM }

internal enum class Section(
    val storageId: String,
    val publicFeed: PublicFeed? = null,
    val personalFeed: PersonalFeed? = null,
) {
    SEARCH("search"), CATALOG("catalog"), ACCOUNT("account"),
    SMART_CONTINUE("smart_continue"),
    RESUME("resume", personalFeed = PersonalFeed.RESUME),
    PERSONAL_UPDATES("personal_updates", personalFeed = PersonalFeed.UPDATES),
    PERSONAL_NO_UPDATES("personal_no_updates", personalFeed = PersonalFeed.NO_UPDATES),
    WANT("want", personalFeed = PersonalFeed.WANT),
    WATCHED("watched", personalFeed = PersonalFeed.WATCHED),
    HISTORY("history", personalFeed = PersonalFeed.HISTORY),
    TODAY("today", PublicFeed.TODAY), UPDATES("updates", PublicFeed.UPDATES),
    TOP("top", PublicFeed.TOP), NEWEST("newest", PublicFeed.NEWEST), BEST("best", PublicFeed.BEST),
    ANNOUNCEMENTS("announcements", PublicFeed.ANNOUNCEMENTS),
    SETTINGS("settings"), ABOUT("about");

    val requiresAccount: Boolean get() = personalFeed != null || this == SMART_CONTINUE
    val canHide: Boolean get() = this != SETTINGS && this != SEARCH && this != ACCOUNT && this != CATALOG

    fun availableAtStart(signedIn: Boolean, hiddenSections: Set<Section> = emptySet()): Section =
        startPages(signedIn, hiddenSections).let { pages -> if (this in pages) this else pages.first() }

    fun atStartup(signedIn: Boolean, restoringAccount: Boolean, hiddenSections: Set<Section> = emptySet()): Section? =
        if (requiresAccount && this !in hiddenSections && restoringAccount) null else availableAtStart(signedIn, hiddenSections)

    companion object {
        val catalogPages = listOf(TODAY, UPDATES, TOP, NEWEST, BEST, ANNOUNCEMENTS)
        val smartPages = listOf(SMART_CONTINUE)
        val personalPages = entries.filter { it.personalFeed != null }
        val systemPages = listOf(SETTINGS, ABOUT)

        fun menuGroups(signedIn: Boolean, hiddenSections: Set<Section> = emptySet()): List<Pair<MenuGroup, List<Section>>> {
            val groups = listOf(MenuGroup.CATALOG to catalogPages) +
                (if (signedIn) listOf(MenuGroup.SMART to smartPages, MenuGroup.PERSONAL to personalPages) else emptyList()) +
                listOf(MenuGroup.SYSTEM to systemPages)
            return groups.mapNotNull { (group, pages) ->
                pages.filter { !it.canHide || it !in hiddenSections }.takeIf { it.isNotEmpty() }?.let { group to it }
            }
        }

        fun startPages(signedIn: Boolean, hiddenSections: Set<Section> = emptySet()): List<Section> =
            menuGroups(signedIn, hiddenSections).flatMap { it.second }

        fun fromStorage(value: String?): Section =
            startPages(true).firstOrNull { it.storageId == value } ?: TODAY
    }
}
