package ru.seasonvar.tv.ui.account

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.*
import ru.seasonvar.tv.data.cache.CardMarkChange

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalListMarksTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val user = AccountUser("12", "Тест", null)
    private val session = MutableStateFlow(AccountSession(user, 1))
    private val row = SeriesEntry("123", "https://seasonvar.ru/serial-123-Test.html", "Тест", 1, "", "poster",
        sourceKey = "resume:marks-want:123", accountListMark = AccountListMark("123", "https://seasonvar.ru/serial-123-Test.html"))
    private var requests = 0
    private fun model(key: String, entries: List<SeriesEntry>) = PersonalListViewModel(session, {
        requests++; AccountPage(user, entries)
    }).also { store.put(key, it) }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { store.clear(); Dispatchers.resetMain() }

    @Test fun status_moves_a_known_card_between_loaded_lists_without_requests_or_changing_other_keys() = runTest(dispatcher) {
        val other = row.copy(id = "124", url = "https://seasonvar.ru/serial-124-Test.html", sourceKey = "other", accountListMark = null)
        val want = model("want", listOf(row, other)); val watched = model("watched", emptyList())
        runCurrent(); want.activate(); watched.activate(); advanceUntilIdle()
        val change = CardMarkChange(row, PlaybackMarkSnapshot(status = -2), 1)
        want.markChanged(PersonalFeed.WANT, change); watched.markChanged(PersonalFeed.WATCHED, change); advanceUntilIdle()
        assertEquals(2, requests)
        assertEquals(listOf(other), want.state.value.entries)
        assertEquals(listOf(row.id), watched.state.value.entries.map { it.id })
        assertEquals("local-mark:123", watched.state.value.entries.single().key)
        assertSame(other, want.state.value.entries.single())
    }

    @Test fun a_new_position_in_the_same_list_does_not_replace_the_list_or_its_entries() = runTest(dispatcher) {
        val model = model("resume", listOf(row)); runCurrent(); model.activate(); advanceUntilIdle()
        val entries = model.state.value.entries
        val change = CardMarkChange(row, PlaybackMarkSnapshot(mark = PlaybackMark(row.id, row.url, "3", "0", 90)), 1)
        model.markChanged(PersonalFeed.RESUME, change); runCurrent()
        assertSame(entries, model.state.value.entries); assertSame(row, model.state.value.entries.single())
        assertEquals(1, requests)
    }

    @Test fun permanent_marks_do_not_remove_or_rewrite_history_context() = runTest(dispatcher) {
        val historyRow = row.copy(sourceKey = "history:1", historyVisitedAt = "01.01.2026",
            initialSelection = EntrySelection(episodeLabel = "1", translationId = "298"))
        val history = model("history", listOf(historyRow)); runCurrent(); history.activate(); advanceUntilIdle()
        history.markChanged(PersonalFeed.HISTORY, CardMarkChange(row, PlaybackMarkSnapshot(), 1))
        assertSame(historyRow, history.state.value.entries.single()); assertEquals(1, requests)
    }

    @Test fun unknown_metadata_is_refreshed_on_next_open_without_starting_a_background_request() = runTest(dispatcher) {
        val want = model("want", emptyList()); runCurrent(); want.activate(); advanceUntilIdle()
        want.markChanged(PersonalFeed.WANT, CardMarkChange(row.copy(title = ""), PlaybackMarkSnapshot(status = -1), 1))
        runCurrent(); assertEquals(1, requests)
        want.activate(); advanceUntilIdle(); assertEquals(2, requests)
    }

    @Test fun a_stale_in_flight_list_cannot_restore_the_removed_card() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); var calls = 0
        val model = PersonalListViewModel(session, {
            calls++
            if (calls > 1) withContext(NonCancellable) { gate.await() }
            AccountPage(user, listOf(row))
        }).also { store.put("want", it) }
        runCurrent(); model.activate(); runCurrent(); model.refresh(); runCurrent()
        model.markChanged(PersonalFeed.WANT, CardMarkChange(row, PlaybackMarkSnapshot(), 1))
        gate.complete(Unit); advanceUntilIdle()
        assertTrue(model.state.value.entries.isEmpty()); assertFalse(model.state.value.loading); assertEquals(2, calls)
    }

    @Test fun unread_parsing_batches_cannot_restore_a_removed_season() = runTest(dispatcher) {
        val rows = List(300) { i -> row.copy(id = "${i + 1}", url = "https://seasonvar.ru/serial-${i + 1}-Test.html", sourceKey = "row:$i", accountListMark = null) }
        val entries = ListParsingWindow.open(rows.asSequence())
        val model = model("want", entries); runCurrent(); model.activate(); advanceUntilIdle()
        val change = CardMarkChange(rows[279], PlaybackMarkSnapshot(), 2)
        model.markChanged(PersonalFeed.WANT, change)?.join()
        val window = checkNotNull(entries.window)
        window.advance(230)
        assertFalse(window.state.value.entries.any { it.id == "280" })
        assertEquals(1, requests)
    }
}
