package ru.seasonvar.tv.ui.navigation

import org.junit.Assert.*
import org.junit.Test

class SectionTest {
    @Test fun missing_invalid_and_non_menu_values_open_today() {
        listOf(null, "unknown", "search", "account").forEach {
            assertEquals(Section.TODAY, Section.fromStorage(it))
        }
    }

    @Test fun stable_saved_values_restore_public_personal_and_system_pages() {
        assertEquals(Section.UPDATES, Section.fromStorage("updates"))
        assertEquals(Section.ANNOUNCEMENTS, Section.fromStorage("announcements"))
        assertEquals(Section.RESUME, Section.fromStorage("resume"))
        assertEquals(Section.HISTORY, Section.fromStorage("history"))
        assertEquals(Section.SETTINGS, Section.fromStorage("settings"))
        assertEquals(Section.ABOUT, Section.fromStorage("about"))
    }

    @Test fun personal_start_waits_for_account_and_falls_back_without_losing_preference() {
        val preferred = Section.HISTORY
        assertNull(preferred.atStartup(signedIn = false, restoringAccount = true))
        assertEquals(Section.TODAY, preferred.atStartup(signedIn = false, restoringAccount = false))
        assertEquals(Section.HISTORY, preferred.atStartup(signedIn = true, restoringAccount = false))
    }

    @Test fun public_and_system_start_do_not_wait_for_network_or_account() {
        listOf(Section.UPDATES, Section.ANNOUNCEMENTS, Section.SETTINGS).forEach {
            assertEquals(it, it.atStartup(signedIn = false, restoringAccount = true))
        }
    }

    @Test fun choices_exclude_sign_in_and_search_and_only_show_personal_pages_after_login() {
        val guest = Section.startPages(false)
        val signedIn = Section.startPages(true)
        assertFalse(Section.SEARCH in signedIn || Section.ACCOUNT in signedIn)
        assertTrue(guest.none { it.personalFeed != null })
        assertTrue(Section.RESUME in signedIn && Section.HISTORY in signedIn)
        assertTrue(signedIn.containsAll(guest))
        assertEquals(signedIn.size, signedIn.map { it.storageId }.toSet().size)
    }
    @Test fun smart_continue_is_separate_from_site_feeds_and_requires_account() {
        assertEquals(Section.SMART_CONTINUE, Section.fromStorage("smart_continue"))
        assertNull(Section.SMART_CONTINUE.atStartup(false, true))
        assertEquals(Section.TODAY, Section.SMART_CONTINUE.availableAtStart(false))
        assertTrue(Section.SMART_CONTINUE in Section.startPages(true))
        assertFalse(Section.SMART_CONTINUE in Section.startPages(false))
        assertNull(Section.SMART_CONTINUE.personalFeed)
    }

    @Test fun hiding_a_whole_group_removes_it_and_reenabling_one_section_restores_it() {
        val hidden = Section.catalogPages.toSet()
        assertFalse(Section.menuGroups(true, hidden).any { it.first == MenuGroup.CATALOG })
        val groups = Section.menuGroups(true, hidden - Section.TOP)
        assertEquals(MenuGroup.CATALOG to listOf(Section.TOP), groups.first())
    }

    @Test fun personal_groups_follow_login_and_visibility_independently() {
        val hidden = (Section.smartPages + Section.personalPages).toSet()
        assertEquals(listOf(MenuGroup.CATALOG, MenuGroup.SYSTEM), Section.menuGroups(true, hidden).map { it.first })
        assertEquals(listOf(MenuGroup.CATALOG, MenuGroup.PERSONAL, MenuGroup.SYSTEM),
            Section.menuGroups(true, hidden - Section.HISTORY).map { it.first })
        assertEquals(listOf(MenuGroup.CATALOG, MenuGroup.SYSTEM),
            Section.menuGroups(false, hidden - Section.HISTORY).map { it.first })
    }

    @Test fun hidden_start_page_falls_back_to_the_first_visible_section() {
        val hidden = setOf(Section.TODAY, Section.TOP)
        assertEquals(Section.UPDATES, Section.TOP.availableAtStart(false, hidden))
        assertEquals(Section.BEST, Section.BEST.availableAtStart(false, hidden))
        assertEquals(Section.TOP, Section.TOP.availableAtStart(false, emptySet()))
        assertFalse(Section.TOP in Section.startPages(true, hidden))
    }

    @Test fun hidden_personal_start_does_not_wait_for_account_restoration() {
        val hidden = setOf(Section.HISTORY, Section.TODAY)
        assertEquals(Section.UPDATES, Section.HISTORY.atStartup(false, true, hidden))
        assertNull(Section.HISTORY.atStartup(false, true, setOf(Section.TODAY)))
        assertEquals(Section.UPDATES, Section.HISTORY.atStartup(false, false, setOf(Section.TODAY)))
    }

    @Test fun all_hidden_pages_fall_back_to_settings_even_during_login() {
        val hidden = Section.startPages(true).toSet()
        assertEquals(listOf(MenuGroup.SYSTEM to listOf(Section.SETTINGS)), Section.menuGroups(true, hidden))
        assertEquals(Section.SETTINGS, Section.HISTORY.atStartup(false, true, hidden))
        assertEquals(Section.SETTINGS, Section.TODAY.availableAtStart(true, hidden))
    }

    @Test fun hiding_the_first_item_preserves_the_order_of_remaining_menu_items() {
        val hidden = setOf(Section.TODAY, Section.RESUME)
        val expected = Section.startPages(true).filterNot { it in hidden }
        assertEquals(expected, Section.startPages(true, hidden))
        assertEquals(Section.UPDATES, Section.startPages(false, hidden).first())
    }

}
