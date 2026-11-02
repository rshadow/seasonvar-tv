package ru.seasonvar.tv.ui.details

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.ui.catalog.LoadFailure
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesDetailsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun entry(id: String = "1") = SeriesEntry(id, "https://seasonvar.ru/serial-$id-test.html", "Тест", 1, "", "")
    private fun details(entry: SeriesEntry) = SeriesDetails(entry, "100", listOf("Описание"), emptyList(), emptyList(),
        listOf(SeasonOption("1", entry("1").url, "Сезон 1", 1), SeasonOption("2", entry("2").url, "Сезон 2", 2)),
        listOf(Translation("0", "Стандартный", listOf(Episode("1_seriya", "1"), Episode("2_seriya", "2"))),
            Translation("1", "Субтитры", listOf(Episode("sub_2", "2"))),
            Translation("68", "Трейлеры", listOf(Episode("main", "Основной")))), "0")

    @Test fun opens_exact_season_and_selects_first_episode_without_faking_resume() = runTest(dispatcher) {
        val model = SeriesDetailsViewModel { details(it) }
        model.open(entry("2")); advanceUntilIdle()
        assertEquals("2", model.state.value.entry!!.id)
        assertEquals("0", model.state.value.translationId)
        assertEquals("1_seriya", model.state.value.episodeId)
        assertNull(model.state.value.resume)
    }

    @Test fun translation_preserves_episode_label_or_falls_back_to_first_available() = runTest(dispatcher) {
        val model = SeriesDetailsViewModel { details(it) }
        model.open(entry()); advanceUntilIdle()
        model.selectEpisode("2_seriya")
        model.selectTranslation("1")
        assertEquals("sub_2", model.state.value.episodeId)
        model.selectTranslation("68")
        assertEquals("main", model.state.value.episodeId)
        model.selectEpisode("invalid")
        assertEquals("main", model.state.value.episodeId)
    }

    @Test fun season_change_resets_episode_and_stale_response_cannot_replace_new_page() = runTest(dispatcher) {
        val model = SeriesDetailsViewModel {
            if (it.id == "1") withContext(NonCancellable) { delay(500) }
            details(it)
        }
        model.open(entry("1")); runCurrent()
        model.open(entry("2")); runCurrent()
        assertEquals("2", model.state.value.entry!!.id)
        advanceUntilIdle()
        assertEquals("2", model.state.value.details!!.entry.id)
        model.selectEpisode("2_seriya")
        model.selectSeason("1"); advanceUntilIdle()
        assertEquals("1", model.state.value.entry!!.id)
        assertEquals("1_seriya", model.state.value.episodeId)
    }

    @Test fun closing_card_cancels_loading_and_discards_late_response() = runTest(dispatcher) {
        val model = SeriesDetailsViewModel { withContext(NonCancellable) { delay(500) }; details(it) }
        model.open(entry()); runCurrent()
        model.close(); advanceUntilIdle()
        assertNull(model.state.value.entry)
        assertNull(model.state.value.details)
    }

    @Test fun distinguishes_network_http_and_markup_errors_and_can_retry() = runTest(dispatcher) {
        val failures = listOf(IOException() to LoadFailure.NETWORK, SiteHttpException(503) to LoadFailure.HTTP,
            MarkupChangedException() to LoadFailure.MARKUP)
        for ((exception, expected) in failures) {
            var fail = true
            val model = SeriesDetailsViewModel { if (fail) throw exception else details(it) }
            model.open(entry()); advanceUntilIdle()
            assertEquals(expected, model.state.value.failure)
            assertFalse(model.state.value.loading)
            fail = false; model.retry(); advanceUntilIdle()
            assertNull(model.state.value.failure)
            assertNotNull(model.state.value.details)
        }
    }
}
