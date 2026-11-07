package ru.seasonvar.tv.ui.settings

import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.ui.navigation.Section

class MenuVisibilityPreferencesTest {
    @Test fun missing_preference_shows_all_sections() {
        val preferences = MenuVisibilityPreferences(read = { null }, write = { error("Unexpected write") })
        assertTrue(preferences.hiddenSections.value.isEmpty())
    }

    @Test fun hidden_sections_and_reenabled_sections_survive_recreation() {
        var stored: Set<String>? = null
        fun create() = MenuVisibilityPreferences(read = { stored }, write = { stored = it })
        val preferences = create()
        preferences.setVisible(Section.TODAY, false)
        preferences.setVisible(Section.HISTORY, false)
        assertEquals(setOf(Section.TODAY, Section.HISTORY), create().hiddenSections.value)
        preferences.setVisible(Section.TODAY, true)
        assertEquals(setOf(Section.HISTORY), create().hiddenSections.value)
    }

    @Test fun unknown_values_and_fixed_controls_cannot_hide_settings_or_shortcuts() {
        val preferences = MenuVisibilityPreferences(
            read = { setOf("settings", "search", "account", "unknown", "today") },
            write = { error("Unexpected write") },
        )
        assertEquals(setOf(Section.TODAY), preferences.hiddenSections.value)
        listOf(Section.SETTINGS, Section.SEARCH, Section.ACCOUNT).forEach { preferences.setVisible(it, false) }
        assertEquals(setOf(Section.TODAY), preferences.hiddenSections.value)
    }

    @Test fun an_existing_hidden_set_keeps_other_and_new_sections_visible() {
        val preferences = MenuVisibilityPreferences(read = { setOf("today") }, write = {})
        assertEquals(setOf(Section.TODAY), preferences.hiddenSections.value)
        assertTrue(Section.ANNOUNCEMENTS in Section.startPages(false, preferences.hiddenSections.value))
    }

    @Test fun hiding_all_sections_still_leaves_a_way_to_restore_them() {
        var stored: Set<String>? = null
        val preferences = MenuVisibilityPreferences(read = { stored }, write = { stored = it })
        Section.startPages(true).forEach { preferences.setVisible(it, false) }
        assertEquals(listOf(Section.SETTINGS), Section.startPages(true, preferences.hiddenSections.value))
        val restored = MenuVisibilityPreferences(read = { stored }, write = { stored = it })
        restored.setVisible(Section.TODAY, true)
        assertEquals(listOf(Section.TODAY, Section.SETTINGS), Section.startPages(false, restored.hiddenSections.value))
    }
}
