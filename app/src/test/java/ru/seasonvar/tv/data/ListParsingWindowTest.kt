package ru.seasonvar.tv.data

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ListParsingWindowTest {
    private fun entry(index: Int) = SeriesEntry(index.toString(), "/$index", "Сериал $index", null, "", "")

    @Test fun reads_250_then_extends_at_200_and_450_without_restarting() = runBlocking {
        var parsed = 0; var closed = 0
        val first = ListParsingWindow.open((1..600).asSequence().map { parsed++; entry(it) }) { closed++ }
        val window = first.window!!
        assertEquals(250, first.size)
        assertTrue(parsed <= 251)
        window.advance(198)
        assertEquals(250, window.state.value.entries.size)
        window.advance(199)
        assertEquals(500, window.state.value.entries.size)
        window.advance(448)
        assertEquals(500, window.state.value.entries.size)
        window.advance(449)
        assertEquals(600, window.state.value.entries.size)
        assertFalse(window.state.value.hasMore)
        assertEquals(600, parsed); assertEquals(1, closed)
        assertEquals((1..600).map { it.toString() }, window.state.value.entries.map { it.id })
    }

    @Test fun ordinary_small_lists_finish_in_first_batch_and_release_source() {
        for (size in listOf(0, 1, 200, 249, 250)) {
            var closed = false
            val page = ListParsingWindow.open((1..size).asSequence().map(::entry)) { closed = true }
            assertEquals(size, page.size); assertNull(page.window); assertTrue(closed)
        }
    }

    @Test fun concurrent_requests_do_not_append_duplicate_batches_and_close_prevents_more_work() = runBlocking {
        var closed = 0
        val page = ListParsingWindow.open((1..2000).asSequence().map(::entry)) { closed++ }
        val window = page.window!!
        val first = async { window.advance(199) }
        val second = async { window.advance(199) }
        first.await(); second.await()
        assertEquals(500, window.state.value.entries.size)
        window.close(); window.close(); window.advance(499)
        assertEquals(500, window.state.value.entries.size); assertEquals(1, closed)
    }

    @Test fun broken_later_markup_keeps_published_entries_and_closes_parser() = runBlocking {
        var closed = false
        val page = ListParsingWindow.open((1..600).asSequence().map { if (it == 300) throw MarkupChangedException() else entry(it) }) { closed = true }
        val window = page.window!!
        window.advance(199)
        assertEquals(250, window.state.value.entries.size)
        assertTrue(window.state.value.failed); assertTrue(closed)
    }

    @Test fun removal_also_excludes_records_from_unparsed_tail() = runBlocking {
        val page = ListParsingWindow.open((1..600).asSequence().map(::entry))
        val window = page.window!!
        window.excludeSeason("1"); window.excludeSeason("400")
        window.advance(199)
        assertEquals(499, window.state.value.entries.size)
        assertTrue(window.state.value.entries.none { it.id in setOf("1", "400") })
        window.close()
    }

    @Test fun equal_initial_entries_do_not_hide_a_different_continuation_from_ui_state() {
        val first = ListParsingWindow.open((1..600).asSequence().map(::entry))
        val second = ListParsingWindow.open((1..800).asSequence().map(::entry))
        assertEquals(first, second)
        val day = java.time.LocalDate.of(2026, 11, 10)
        assertNotEquals(ru.seasonvar.tv.ui.catalog.CatalogUiState(date = day, entries = first), ru.seasonvar.tv.ui.catalog.CatalogUiState(date = day, entries = second))
        first.window?.close(); second.window?.close()
    }
}
