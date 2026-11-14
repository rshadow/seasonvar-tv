package ru.seasonvar.tv.ui.catalog

import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.ui.navigation.Section

class CatalogLayoutTest {
    @Test fun absent_or_unknown_preference_keeps_existing_strip() {
        assertEquals(CatalogLayout.STRIP, CatalogLayout.fromStorage(null))
        assertEquals(CatalogLayout.STRIP, CatalogLayout.fromStorage("future-layout"))
        for (layout in CatalogLayout.entries) {
            assertEquals(layout, CatalogLayout.fromStorage(layout.storageId))
            assertEquals(layout, layout.other().other())
            assertNotEquals(layout, layout.other())
        }
    }

    @Test fun each_list_keeps_its_own_layout_and_restores_it_after_restarting() {
        val saved = mutableMapOf<String, String>()
        fun preferences() = CatalogLayoutPreferences(saved::get) { key, value -> saved[key] = value }
        val prefs = preferences()
        prefs.set(Section.UPDATES, CatalogLayout.GRID)
        prefs.set(Section.HISTORY, CatalogLayout.GRID)
        prefs.set(Section.SEARCH, CatalogLayout.GRID)
        assertEquals(CatalogLayout.STRIP, prefs.layouts.value[Section.TOP])
        assertEquals(CatalogLayout.STRIP, prefs.layouts.value[Section.RESUME])
        prefs.set(Section.UPDATES, CatalogLayout.STRIP)
        val restored = preferences().layouts.value
        assertEquals(CatalogLayout.STRIP, restored[Section.UPDATES])
        assertEquals(CatalogLayout.GRID, restored[Section.HISTORY])
        assertEquals(CatalogLayout.GRID, restored[Section.SEARCH])
        assertEquals(CatalogLayout.STRIP, restored[Section.TOP])
        assertEquals(3, saved.size)
    }

    @Test fun non_catalog_screens_have_no_layout_preference() {
        val saved = mutableMapOf<String, String>()
        val prefs = CatalogLayoutPreferences(saved::get) { key, value -> saved[key] = value }
        assertFalse(prefs.layouts.value.containsKey(Section.SETTINGS))
        assertThrows(IllegalArgumentException::class.java) { prefs.set(Section.ACCOUNT, CatalogLayout.GRID) }
        assertTrue(saved.isEmpty())
    }

    @Test fun actor_lists_share_one_persistent_layout_independent_of_other_lists() {
        val saved = mutableMapOf<String, String>()
        fun preferences() = CatalogLayoutPreferences(saved::get) { key, value -> saved[key] = value }
        val prefs = preferences()
        prefs.set(Section.SEARCH, CatalogLayout.GRID)
        assertEquals(CatalogLayout.STRIP, prefs.actorLayout.value)
        prefs.setActorLayout(CatalogLayout.GRID)
        assertEquals(CatalogLayout.GRID, preferences().actorLayout.value)
        assertEquals(CatalogLayout.STRIP, prefs.layouts.value[Section.TOP])
        prefs.setActorLayout(CatalogLayout.STRIP)
        val restored = preferences()
        assertEquals(CatalogLayout.STRIP, restored.actorLayout.value)
        assertEquals(CatalogLayout.GRID, restored.layouts.value[Section.SEARCH])
        assertEquals(CatalogLayout.STRIP, restored.layouts.value[Section.TOP])
        assertEquals(setOf("catalog_layout.search", "catalog_layout.actor"), saved.keys)
    }

    @Test fun unknown_saved_actor_layout_falls_back_to_strip() {
        val prefs = CatalogLayoutPreferences({ "future-layout" }, { _, _ -> error("Unexpected write") })
        assertEquals(CatalogLayout.STRIP, prefs.actorLayout.value)
    }

    @Test fun incomplete_last_row_stays_inside_the_catalog_on_down_and_right() {
        assertEquals(GridCardEdges(false, true, false, true), catalogGridEdges(6, 7, 5))
        assertEquals(GridCardEdges(false, true, true, false), catalogGridEdges(5, 7, 5))
        assertEquals(GridCardEdges(true, false, false, true), catalogGridEdges(4, 7, 5))
        assertEquals(GridCardEdges(true, false, true, false), catalogGridEdges(0, 7, 5))
    }

    @Test fun edges_follow_the_actual_column_count_after_resizing() {
        assertTrue(catalogGridEdges(4, 7, 4).left)
        assertFalse(catalogGridEdges(4, 7, 5).left)
        assertTrue(catalogGridEdges(4, 7, 4).bottom)
        assertFalse(catalogGridEdges(4, 7, 5).bottom)
        assertEquals(GridCardEdges(true, true, true, true), catalogGridEdges(0, 1, 5))
        assertTrue(catalogGridEdges(1, 2, 1).left)
        assertTrue(catalogGridEdges(1, 2, 1).right)
        assertTrue(catalogGridEdges(1, 2, 1).bottom)
    }
}
