package ru.seasonvar.tv.ui.navigation

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.ui.focus.FocusRequester
import ru.seasonvar.tv.data.account.PersonalFeed

/** Все объекты фокуса принадлежат одному экземпляру главного экрана. */
internal class MainFocus {
    val contentFocus = FocusRequester()
    val layoutFocus = FocusRequester()
    val browseFocus = FocusRequester()
    val browseFiltersFocus = FocusRequester()
    val searchFocus = FocusRequester()
    val smartFocus = FocusRequester()
    val accountFocus = FocusRequester()
    val accountSignOutFocus = FocusRequester()
    val personalFocus = PersonalFeed.entries.associateWith { FocusRequester() }
    val menuBringIntoView = Section.entries.associateWith { BringIntoViewRequester() }
    val searchInputFocus = FocusRequester()
    val searchHistoryFocus = FocusRequester()
    val todayFocus = FocusRequester()
    val updatesFocus = FocusRequester()
    val topFocus = FocusRequester()
    val newestFocus = FocusRequester()
    val bestFocus = FocusRequester()
    val announcementsFocus = FocusRequester()
    val settingsFocus = FocusRequester()
    val settingsContentFocus = FocusRequester()
    val aboutFocus = FocusRequester()
    val aboutContentFocus = FocusRequester()
    val refreshFocus = FocusRequester()
    val retryFocus = FocusRequester()
    val cards = mutableMapOf<String, FocusRequester>()

    fun sectionFocus(item: Section): FocusRequester = when (item) {
        Section.CATALOG -> browseFocus
        Section.SEARCH -> searchFocus
        Section.SMART_CONTINUE -> smartFocus
        Section.ACCOUNT -> accountFocus
        Section.RESUME, Section.PERSONAL_UPDATES, Section.PERSONAL_NO_UPDATES, Section.WANT, Section.WATCHED, Section.HISTORY ->
            personalFocus.getValue(requireNotNull(item.personalFeed))
        Section.TODAY -> todayFocus
        Section.UPDATES -> updatesFocus
        Section.TOP -> topFocus
        Section.NEWEST -> newestFocus
        Section.BEST -> bestFocus
        Section.ANNOUNCEMENTS -> announcementsFocus
        Section.SETTINGS -> settingsFocus
        Section.ABOUT -> aboutFocus
    }
}
