package ru.seasonvar.tv.ui.catalog

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.images.PrefetchImage

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesListEngineTest {
    private fun entries(count: Int) = List(count) { index -> SeriesEntry(index.toString(), "/$index", "Сериал $index",
        1, "", "https://cdn.svstat.net/oblojka/$index.jpg") }
    private fun input(rows: List<SeriesEntry>, selected: Int = 4, visible: IntRange = 0..4, enabled: Boolean = true,
        catalog: String = "test") = SeriesListInput(catalog, rows, rows[selected].key, 5, visible, enabled, 120, 180, true)
    private fun TestScope.engine(prepare: suspend (SeriesEntry) -> SeriesCardContent = { SeriesCardContent(it, it.title) },
        load: suspend (PrefetchImage) -> Unit = {}, frame: suspend () -> Unit = {}, permits: Semaphore = Semaphore(3)) =
        SeriesListEngine(this, prepare, load, permits, frame, StandardTestDispatcher(testScheduler))

    @Test fun large_list_prepares_only_window_and_append_reuses_the_same_cards_and_images() = runTest {
        val all = entries(33000); val prepared = mutableListOf<String>(); val loaded = mutableListOf<String>()
        val engine = engine(prepare = { prepared += it.id; SeriesCardContent(it, it.title) }, load = { loaded += it.key })
        engine.update(input(all.take(100))); advanceUntilIdle()
        assertEquals(listOf("4", "0", "1", "2", "3") + (5..13).map(Int::toString), prepared)
        val requests = loaded.toList()
        engine.update(input(all)); advanceUntilIdle()
        assertEquals(14, engine.preparedCount); assertEquals(14, prepared.size); assertEquals(requests, loaded)
        assertNotNull(engine.content("test|" + all[4].key, all[4]))
        engine.clear()
    }
    @Test fun moving_focus_keeps_unfinished_overlap_and_reprioritizes_the_queue() = runTest {
        val rows = entries(40); val started = mutableListOf<String>(); val gate = CompletableDeferred<Unit>()
        val engine = engine(prepare = {
            started += it.id
            if (it.id == "4") gate.await()
            SeriesCardContent(it, it.title)
        })
        engine.update(input(rows)); runCurrent()
        engine.update(input(rows, selected = 5, visible = 1..5)); runCurrent()
        assertEquals(listOf("4"), started)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("4", "5", "1", "2", "3") + (6..14).map(Int::toString), started)
        assertNull(engine.content("test|" + rows[0].key, rows[0]))
        assertEquals(14, engine.preparedCount); engine.clear()
    }
    @Test fun disabling_prefetch_cancels_images_and_future_cards_but_keeps_visible_content() = runTest {
        val rows = entries(50); var active = 0; var cancelled = 0
        val engine = engine(load = { active++; try { awaitCancellation() } finally { active--; cancelled++ } })
        engine.update(input(rows)); runCurrent()
        assertEquals(3, active); assertEquals(14, engine.preparedCount)
        engine.update(input(rows, enabled = false)); advanceUntilIdle()
        assertEquals(0, active); assertEquals(3, cancelled); assertEquals(5, engine.preparedCount)
        assertNotNull(engine.content("test|" + rows[4].key, rows[4]))
        engine.update(input(rows, selected = 5, visible = 1..5, enabled = false)); advanceUntilIdle()
        assertEquals(5, engine.preparedCount); assertEquals(3, cancelled); engine.clear()
    }
    @Test fun late_completion_cannot_restore_an_obsolete_card_after_switch_or_clear() = runTest {
        val rows = entries(20); val gate = CompletableDeferred<Unit>()
        val engine = engine(prepare = { withContext(NonCancellable) { gate.await() }; SeriesCardContent(it, it.title) })
        engine.update(input(rows)); runCurrent()
        engine.clear(); gate.complete(Unit); advanceUntilIdle()
        assertEquals(0, engine.preparedCount)
        engine.update(input(rows, catalog = "other", enabled = false)); advanceUntilIdle()
        assertNull(engine.content("test|" + rows[4].key, rows[4]))
        assertNotNull(engine.content("other|" + rows[4].key, rows[4])); engine.clear()
    }
    @Test fun changed_entry_with_same_key_replaces_its_prepared_content() = runTest {
        val rows = entries(20); val engine = engine()
        engine.update(input(rows)); advanceUntilIdle()
        val updated = rows.toMutableList().apply { this[4] = this[4].copy(title = "Новое название") }
        engine.update(input(updated)); advanceUntilIdle()
        assertNull(engine.content("test|" + rows[4].key, rows[4]))
        assertEquals("Новое название", engine.content("test|" + updated[4].key, updated[4])?.subtitle)
        engine.clear()
    }
    @Test fun enriched_card_is_reused_until_its_source_entry_changes() = runTest {
        val rows = entries(20); var preparations = 0
        val engine = engine(prepare = { preparations++; SeriesCardContent(it.copy(originalTitle = "Original"), it.title) })
        engine.update(input(rows)); advanceUntilIdle()
        val first = engine.content("test|" + rows[4].key, rows[4])
        assertEquals("Original", first?.entry?.originalTitle)
        engine.update(input(rows)); advanceUntilIdle()
        assertSame(first, engine.content("test|" + rows[4].key, rows[4]))
        assertEquals(14, preparations)
        val changed = rows.toMutableList().apply { this[4] = this[4].copy(title = "Другое название") }
        engine.update(input(changed)); advanceUntilIdle()
        assertNull(engine.content("test|" + rows[4].key, rows[4]))
        assertEquals("Другое название", engine.content("test|" + changed[4].key, changed[4])?.entry?.title)
        assertEquals(15, preparations); engine.clear()
    }
    @Test fun window_planning_does_not_walk_the_whole_list_on_normal_focus_moves() = runTest {
        val rows = entries(33000); var reads = 0
        val measured = object : AbstractList<SeriesEntry>() {
            override val size = rows.size
            override fun get(index: Int): SeriesEntry { reads++; return rows[index] }
        }
        val engine = engine()
        engine.update(input(measured)); advanceUntilIdle()
        reads = 0
        engine.update(input(measured, selected = 5, visible = 1..5)); advanceUntilIdle()
        assertTrue("Read $reads rows", reads < 100)
        assertEquals(33000, measured.size); engine.clear()
    }
    @Test fun cards_are_published_after_a_frame_without_waiting_for_posters() = runTest {
        val rows = entries(20); val gate = CompletableDeferred<Unit>()
        val engine = engine(load = { awaitCancellation() }, frame = { gate.await() })
        engine.update(input(rows)); runCurrent(); assertEquals(0, engine.preparedCount)
        gate.complete(Unit); runCurrent(); assertEquals(14, engine.preparedCount)
        engine.clear(); advanceUntilIdle()
    }
}
