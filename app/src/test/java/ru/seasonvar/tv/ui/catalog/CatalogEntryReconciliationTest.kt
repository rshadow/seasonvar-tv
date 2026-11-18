package ru.seasonvar.tv.ui.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import ru.seasonvar.tv.data.SeriesEntry

class CatalogEntryReconciliationTest {
    private val a = entry("a")
    private val b = entry("b")
    private val c = entry("c")
    private val d = entry("d")
    private val previous = listOf(a, b, c)

    @Test fun matching_response_reuses_the_same_list() {
        val incoming = previous.map { it.copy() }
        assertSame(previous, reconcileCatalogEntries(previous, incoming, keepUnresolved = false))
        assertSame(previous, reconcileCatalogEntries(previous, incoming.take(2), keepUnresolved = true))
    }

    @Test fun follows_server_order_and_reuses_only_unchanged_cards() {
        val updatedB = b.copy(update = "Вы посмотрели 2 из 3")
        val incoming = listOf(c.copy(), updatedB, d)
        val result = reconcileCatalogEntries(previous, incoming, keepUnresolved = false)

        assertEquals(incoming, result)
        assertSame(c, result[0])
        assertSame(updatedB, result[1])
        assertSame(d, result[2])
    }

    @Test fun partial_response_retains_unresolved_cards_until_full_response() {
        val partial = reconcileCatalogEntries(previous, listOf(c.copy(), d), keepUnresolved = true)
        assertEquals(listOf(c, d, a, b), partial)
        assertSame(b, partial.last())

        val full = reconcileCatalogEntries(partial, listOf(c.copy(), d.copy()), keepUnresolved = false)
        assertEquals(listOf(c, d), full)
        assertSame(d, full.last())
        val empty = emptyList<SeriesEntry>()
        assertSame(empty, reconcileCatalogEntries(full, empty, keepUnresolved = false))
    }

    @Test fun partial_response_respects_explicit_local_removals_even_outside_the_loaded_prefix() {
        val result = reconcileCatalogEntries(previous, listOf(a.copy()), keepUnresolved = true, excludedSeasons = setOf(b.id))
        assertEquals(listOf(a, c), result)
        assertSame(c, result.last())
    }

    private fun entry(key: String) = SeriesEntry(key, "/$key", key, 1, "", "", sourceKey = key)
}
