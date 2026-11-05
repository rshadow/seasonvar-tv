package ru.seasonvar.tv.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SearchHistoryTest {
    private class MemoryStore(var saved: List<String> = emptyList()) : SearchHistoryStore {
        var fail = false
        override suspend fun read() = saved
        override suspend fun write(queries: List<String>) { if (fail) throw IOException(); saved = queries }
    }
    @Test fun whitespace_and_case_duplicates_are_normalized_and_repeated_query_moves_to_front() {
        val queries = SearchHistoryQueries.remember(listOf("Игра престолов", "Naruto", "Тест"), "  ИГРА  престолов\t ")
        assertEquals(listOf("ИГРА престолов", "Naruto", "Тест"), queries)
        assertEquals(listOf("NARUTO", "ИГРА престолов", "Тест"), SearchHistoryQueries.remember(queries, " NARUTO "))
    }
    @Test fun recent_history_is_bounded_and_blank_or_oversized_queries_are_ignored() {
        val history = (1..30).map { "Запрос $it" }
        assertEquals(20, SearchHistoryQueries.ordered(history).size)
        assertEquals("новый", SearchHistoryQueries.remember(history.take(20), "новый").first())
        assertEquals(history, SearchHistoryQueries.remember(history, " "))
        assertEquals(history, SearchHistoryQueries.remember(history, "x".repeat(201)))
    }
    @Test fun stored_json_handles_unicode_duplicates_non_strings_and_corruption() {
        val queries = listOf("Игра престолов", "Naruto")
        assertEquals(queries, SearchHistoryQueries.decode(SearchHistoryQueries.encode(queries)))
        assertEquals(listOf("Naruto"), SearchHistoryQueries.decode("[42,null,\"Naruto\",\"naruto\",\" \"]"))
        assertTrue(SearchHistoryQueries.decode("broken").isEmpty())
        assertTrue(SearchHistoryQueries.decode(null).isEmpty())
    }
    @Test fun records_survive_repository_recreation_and_clear_is_persistent() = runTest {
        val store = MemoryStore(listOf("old")); val history = SearchHistory(store)
        history.remember("new"); assertEquals(listOf("new", "old"), store.saved)
        val recreated = SearchHistory(store); recreated.load(); assertEquals(store.saved, recreated.queries.value)
        recreated.clear(); assertTrue(store.saved.isEmpty())
        val empty = SearchHistory(store); empty.load(); assertTrue(empty.queries.value.isEmpty())
    }
    @Test fun failed_writes_do_not_claim_a_saved_or_cleared_history() = runTest {
        val store = MemoryStore(listOf("old")); val history = SearchHistory(store); history.load(); store.fail = true
        try { history.remember("new"); fail() } catch (_: IOException) { }
        assertEquals(listOf("old"), history.queries.value)
        try { history.clear(); fail() } catch (_: IOException) { }
        assertEquals(listOf("old"), history.queries.value)
        store.fail = false; history.clear(); assertTrue(history.queries.value.isEmpty())
    }
    @Test fun clearing_waits_for_an_in_flight_write_and_removes_it() = runTest {
        val gate = CompletableDeferred<Unit>(); var saved = emptyList<String>()
        val history = SearchHistory(object : SearchHistoryStore {
            override suspend fun read() = saved
            override suspend fun write(queries: List<String>) { if (queries.isNotEmpty()) gate.await(); saved = queries }
        })
        val record = launch { history.remember("new") }; runCurrent()
        val clear = launch { history.clear() }; runCurrent(); gate.complete(Unit); record.join(); clear.join()
        assertTrue(saved.isEmpty()); assertTrue(history.queries.value.isEmpty())
    }
}
