package ru.seasonvar.tv.ui.navigation

import org.junit.Assert.*
import org.junit.Test

class RemovalFocusTest {
    @Test fun selects_the_next_card_and_uses_previous_for_last_card() {
        assertEquals("c", removalNeighbor(listOf("a", "b", "c"), listOf("a", "c"), "b"))
        assertEquals("b", removalNeighbor(listOf("a", "b", "c"), listOf("a", "b"), "c"))
    }
    @Test fun skips_all_removed_duplicates_without_jumping_to_the_beginning() {
        assertEquals("d", removalNeighbor(listOf("a", "b", "c", "d"), listOf("a", "d"), "b"))
        assertEquals("a", removalNeighbor(listOf("a", "b", "c"), listOf("a"), "c"))
    }
    @Test fun empty_list_and_concurrent_refresh_have_a_defined_destination() {
        assertNull(removalNeighbor(listOf("a"), emptyList(), "a"))
        assertEquals("new", removalNeighbor(listOf("a"), listOf("new"), "a"))
        assertEquals("a", removalNeighbor(listOf("a", "b"), listOf("a", "b"), "a"))
    }
}
