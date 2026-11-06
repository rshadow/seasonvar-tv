package ru.seasonvar.tv.ui.navigation

import ru.seasonvar.tv.data.PublicFeed
import ru.seasonvar.tv.data.account.PersonalFeed

internal enum class Section(
    val storageId: String,
    val publicFeed: PublicFeed? = null,
    val personalFeed: PersonalFeed? = null,
) {
    SEARCH("search"), ACCOUNT("account"),
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

    fun availableAtStart(signedIn: Boolean): Section =
        if (this in startPages(signedIn)) this else TODAY

    fun atStartup(signedIn: Boolean, restoringAccount: Boolean): Section? =
        if (requiresAccount && restoringAccount) null else availableAtStart(signedIn)

    companion object {
        val catalogPages = listOf(TODAY, UPDATES, TOP, NEWEST, BEST, ANNOUNCEMENTS)
        val smartPages = listOf(SMART_CONTINUE)
        val personalPages = entries.filter { it.personalFeed != null }
        val systemPages = listOf(SETTINGS, ABOUT)

        fun startPages(signedIn: Boolean): List<Section> =
            catalogPages + (if (signedIn) smartPages + personalPages else emptyList()) + systemPages

        fun fromStorage(value: String?): Section =
            startPages(true).firstOrNull { it.storageId == value } ?: TODAY
    }
}
