package ru.seasonvar.tv.ui.catalog

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.SeriesDescription
import ru.seasonvar.tv.data.images.PrefetchImage
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesListDescriptionsTest {
    private fun entries(count: Int = 100) = List(count) { index -> SeriesEntry(index.toString(), "/$index", "Сериал $index",
        1, "", "https://cdn.svstat.net/oblojka/$index.jpg") }
    private fun input(rows: List<SeriesEntry>, selected: Int = 0, visible: IntRange = 0..2, enabled: Boolean = true,
        catalog: String = "test") = SeriesListInput(catalog, rows, rows[selected].key, 3, visible, enabled, 120, 180, true)
    private fun text(entry: SeriesEntry) = SeriesDescription(listOf("Описание " + entry.id))
    private fun TestScope.engine(load: suspend (SeriesEntry) -> SeriesDescription,
        image: suspend (PrefetchImage) -> Unit = {}, permits: Semaphore = Semaphore(3)) = SeriesListEngine(
        this, { SeriesCardContent(it, it.title) }, image, permits, {}, StandardTestDispatcher(testScheduler), load)

    @Test fun off_loads_only_visible_descriptions_with_selection_first_and_no_background_images() = runTest {
        val rows = entries(33000); val calls = mutableListOf<String>(); var images = 0
        val engine = engine({ calls += it.id; text(it) }, image = { images++ })
        engine.update(input(rows, selected = 2, enabled = false)); advanceUntilIdle()
        assertEquals(listOf("2", "0", "1"), calls)
        assertEquals(3, engine.descriptionCount); assertEquals(0, images)
        assertFalse(engine.description(rows[2]).loading)
        assertEquals(text(rows[2]), engine.description(rows[2]).description)
        engine.clear()
    }
    @Test fun ahead_uses_the_same_two_screen_window_and_ready_selection_has_no_extra_request() = runTest {
        val rows = entries(); val calls = mutableListOf<String>()
        val engine = engine({ calls += it.id; text(it) })
        engine.update(input(rows)); advanceUntilIdle()
        assertEquals((0..5).map(Int::toString), calls)
        assertEquals(text(rows[3]), engine.description(rows[3]).description)
        engine.update(input(rows, selected = 3, visible = 2..4)); advanceUntilIdle()
        assertEquals(1, calls.count { it == "3" }); assertFalse(engine.description(rows[3]).loading)
        assertEquals((0..8).map(Int::toString), calls); assertEquals(7, engine.descriptionCount)
        engine.clear()
    }
    @Test fun different_cards_for_the_same_season_share_one_description() = runTest {
        val original = entries(1).single()
        val rows = List(5) { original.copy(sourceKey = "translation-$it", update = "Озвучка $it") }
        var calls = 0
        val engine = engine({ calls++; text(it) })
        engine.update(input(rows)); advanceUntilIdle()
        assertEquals(1, calls); assertEquals(1, engine.descriptionCount)
        assertEquals(rows.map { it.key }, rows.map { engine.description(it).key })
        assertTrue(rows.all { engine.description(it).description == text(original) }); engine.clear()
    }
    @Test fun visible_descriptions_precede_speculative_images_and_share_the_global_three_request_limit() = runTest {
        val rows = entries(); val permits = Semaphore(3); val gate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>(); var active = 0; var peak = 0
        suspend fun request(label: String) {
            calls += label; active++; peak = maxOf(peak, active)
            try { gate.await() } finally { active-- }
        }
        val engine = engine({ request("description:" + it.id); text(it) }, { request("image") }, permits)
        val other = engine({ request("other"); text(it) }, { request("image") }, permits)
        engine.update(input(rows, selected = 2)); runCurrent()
        other.update(input(rows)); runCurrent()
        assertEquals(listOf("description:2", "description:0", "description:1"), calls)
        assertEquals(3, active)
        gate.complete(Unit); advanceUntilIdle()
        assertTrue("Images were also loaded", "image" in calls); assertEquals(3, peak)
        assertEquals(0, active); engine.clear(); other.clear()
    }
    @Test fun selection_keeps_an_existing_unfinished_visible_request() = runTest {
        val rows = entries(); val gate = CompletableDeferred<Unit>(); val calls = mutableListOf<String>()
        val engine = engine({ calls += it.id; gate.await(); text(it) })
        engine.update(input(rows)); runCurrent()
        engine.update(input(rows, selected = 1)); runCurrent()
        assertEquals(listOf("0", "1", "2"), calls)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, calls.count { it == "1" }); assertEquals(text(rows[1]), engine.description(rows[1]).description)
        engine.clear()
    }
    @Test fun toggling_off_cancels_ahead_but_retains_visible_requests() = runTest {
        val rows = entries(); val gates = rows.associate { it.id to CompletableDeferred<Unit>() }
        val started = mutableListOf<String>(); val stopped = mutableListOf<String>()
        val engine = engine({ entry ->
            started += entry.id
            try { gates.getValue(entry.id).await(); text(entry) } finally { stopped += entry.id }
        })
        engine.update(input(rows)); runCurrent()
        gates.getValue("0").complete(Unit); runCurrent()
        assertTrue("3" in started)
        engine.update(input(rows, enabled = false)); runCurrent()
        assertTrue("3" in stopped); assertFalse("1" in stopped); assertFalse("2" in stopped)
        gates.getValue("1").complete(Unit); gates.getValue("2").complete(Unit); advanceUntilIdle()
        assertEquals(3, engine.descriptionCount); assertEquals(4, started.size); engine.clear()
    }
    @Test fun leaving_window_and_switching_source_cannot_publish_late_descriptions() = runTest {
        val rows = entries(); val gate = CompletableDeferred<Unit>()
        val engine = engine({ withContext(NonCancellable) { gate.await() }; text(it) })
        engine.update(input(rows)); runCurrent()
        engine.update(input(rows, selected = 50, visible = 50..52, catalog = "other")); runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        assertTrue(engine.description(rows[0]).loading)
        assertEquals(text(rows[50]), engine.description(rows[50]).description)
        assertEquals(6, engine.descriptionCount)
        engine.clear(); assertEquals(0, engine.descriptionCount)
    }
    @Test fun failed_speculation_is_retried_once_when_selected_and_does_not_poison_other_cards() = runTest {
        val rows = entries(); var attempts = 0
        val engine = engine({ if (it.id == "4" && ++attempts == 1) throw IOException(); text(it) })
        engine.update(input(rows)); advanceUntilIdle()
        assertTrue(engine.description(rows[4]).failed); assertNotNull(engine.description(rows[3]).description)
        engine.update(input(rows, selected = 4, visible = 2..4)); advanceUntilIdle()
        assertEquals(2, attempts); assertFalse(engine.description(rows[4]).failed)
        assertEquals(text(rows[4]), engine.description(rows[4]).description); engine.clear()
    }
    @Test fun adding_many_server_records_does_not_start_descriptions_outside_the_window() = runTest {
        val rows = entries(33000); val calls = mutableListOf<String>()
        val engine = engine({ calls += it.id; text(it) })
        engine.update(input(rows.take(100))); advanceUntilIdle()
        engine.update(input(rows)); advanceUntilIdle()
        assertEquals((0..5).map(Int::toString), calls); assertEquals(6, engine.descriptionCount); engine.clear()
    }
}
