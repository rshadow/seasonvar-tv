package ru.seasonvar.tv.data

import org.junit.Assert.*
import org.junit.Test

class SeriesListWindowTest {
    @Test fun two_screens_start_at_selection_and_visible_items_have_priority() {
        val window = seriesListWindow(33000, 4, 5, 0..4, true)
        assertEquals(4..13, window.ahead)
        assertEquals(listOf(4, 0, 1, 2, 3) + (5..13), window.preparationOrder(4))
    }
    @Test fun distant_viewport_does_not_prepare_the_gap() {
        val window = seriesListWindow(33000, 32000, 5, 0..4, true)
        assertEquals(15, window.preparationOrder(32000).size)
        assertFalse(20000 in window.preparationOrder(32000))
    }
    @Test fun disabled_prefetch_still_serves_the_visible_range() {
        val window = seriesListWindow(33000, 4, 5, 2..6, false)
        assertTrue(window.ahead.isEmpty())
        assertEquals(listOf(4, 2, 3, 5, 6), window.preparationOrder(4))
    }
    @Test fun ranges_are_clamped_and_large_page_size_cannot_overflow() {
        val window = seriesListWindow(12, 8, Int.MAX_VALUE, -3..30, true)
        assertEquals(0..11, window.visible)
        assertEquals(8..11, window.ahead)
        assertTrue(seriesListWindow(0, 0, 5, 0..4, true).preparationOrder(0).isEmpty())
        assertTrue(seriesListWindow(12, -1, 0, 20..30, true).preparationOrder(-1).isEmpty())
    }
    @Test fun appending_a_page_does_not_enlarge_a_full_window() {
        assertEquals(seriesListWindow(50, 4, 5, 0..4, true), seriesListWindow(100, 4, 5, 0..4, true))
        assertEquals(48..49, seriesListWindow(50, 48, 5, 45..49, true).ahead)
        assertEquals(48..57, seriesListWindow(100, 48, 5, 45..49, true).ahead)
    }
}
