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
        listOf(Section.UPDATES, Section.SETTINGS).forEach {
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
}
