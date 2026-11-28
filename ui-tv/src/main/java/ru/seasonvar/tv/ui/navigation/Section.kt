package ru.seasonvar.tv.ui.navigation

import ru.seasonvar.tv.data.PublicFeed
import ru.seasonvar.tv.data.account.PersonalFeed

internal enum class MenuGroup(val storageId: String) {
    CATALOG("group_catalog"), SMART("group_smart"), PERSONAL("group_personal"), SYSTEM("group_system")
}

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

    fun availableAtStart(signedIn: Boolean, hiddenSections: Set<Section> = emptySet(), order: MenuOrder = MenuOrder()): Section =
        startPages(signedIn, hiddenSections, order).let { pages -> if (this in pages) this else pages.first() }

    fun atStartup(signedIn: Boolean, restoringAccount: Boolean, hiddenSections: Set<Section> = emptySet(),
        order: MenuOrder = MenuOrder()): Section? =
        if (requiresAccount && this !in hiddenSections && restoringAccount) null else availableAtStart(signedIn, hiddenSections, order)

    companion object {
        val catalogPages = listOf(TODAY, UPDATES, TOP, NEWEST, BEST, ANNOUNCEMENTS)
        val smartPages = listOf(SMART_CONTINUE)
        val personalPages = entries.filter { it.personalFeed != null }
        val systemPages = listOf(SETTINGS, ABOUT)
        val defaultMenuGroups = listOf(MenuGroup.CATALOG to catalogPages, MenuGroup.SMART to smartPages,
            MenuGroup.PERSONAL to personalPages, MenuGroup.SYSTEM to systemPages)

        fun menuGroups(signedIn: Boolean, hiddenSections: Set<Section> = emptySet(),
            order: MenuOrder = MenuOrder()): List<Pair<MenuGroup, List<Section>>> {
            return order.groups().mapNotNull { (group, pages) ->
                pages.filter { (signedIn || !it.requiresAccount) && (!it.canHide || it !in hiddenSections) }
                    .takeIf { it.isNotEmpty() }?.let { group to it }
            }
        }

        fun startPages(signedIn: Boolean, hiddenSections: Set<Section> = emptySet(), order: MenuOrder = MenuOrder()): List<Section> =
            menuGroups(signedIn, hiddenSections, order).flatMap { it.second }

        fun fromStorage(value: String?): Section =
            startPages(true).firstOrNull { it.storageId == value } ?: TODAY
    }
}
