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
    @Test fun saved_local_point_selects_its_translation_and_episode_and_other_selection_starts_fresh() = runTest(dispatcher) {
        val point = ResumePoint("1", "1", "sub_2", 45000)
        val model = SeriesDetailsViewModel(loadResume = { point }) { details(it) }
        model.open(entry()); advanceUntilIdle()
        assertEquals("1", model.state.value.translationId)
        assertEquals("sub_2", model.state.value.episodeId)
        assertEquals(point, model.state.value.resume)
        assertEquals("2", model.playbackRequest()!!.episodeLabel)
        model.selectTranslation("0")
        assertEquals("2_seriya", model.playbackRequest()!!.episodeId)
        assertNull(model.state.value.resume)
        model.selectTranslation("1")
        assertEquals(point, model.state.value.resume)
    }

    @Test fun unavailable_saved_translation_or_episode_falls_back_to_available_content() = runTest(dispatcher) {
        val model = SeriesDetailsViewModel(loadResume = { ResumePoint("1", "9", "missing", 10000) }) { details(it) }
        model.open(entry()); advanceUntilIdle()
        assertEquals("0", model.state.value.translationId)
        assertEquals("1_seriya", model.state.value.episodeId)
        assertNull(model.state.value.resume)
        assertNull(model.playbackRequest("missing"))
    }

    @Test fun return_from_player_refreshes_position_without_changing_current_selection() = runTest(dispatcher) {
        var point: ResumePoint? = null
        val model = SeriesDetailsViewModel(loadResume = { point }) { details(it) }
        model.open(entry()); advanceUntilIdle()
        model.selectEpisode("2_seriya")
        point = ResumePoint("1", "0", "2_seriya", 15000)
        model.refreshResume(); advanceUntilIdle()
        assertEquals("2_seriya", model.state.value.episodeId)
        assertEquals(point, model.state.value.resume)
    }

    @Test fun canceled_progress_read_cannot_overwrite_the_new_season_resume() = runTest(dispatcher) {
        val model = SeriesDetailsViewModel(loadResume = { id ->
            if (id == "1") withContext(NonCancellable) { delay(500) }
            ResumePoint(id, "0", "2_seriya", 9000)
        }) { details(it) }
        model.open(entry("1")); runCurrent()
        model.open(entry("2")); advanceUntilIdle()
        assertEquals("2", model.state.value.entry!!.id)
        model.selectEpisode("1_seriya"); model.selectEpisode("2_seriya")
        assertEquals("2", model.state.value.resume!!.seasonId)
    }

    @Test fun unavailable_season_can_be_refreshed_without_a_no_op_watch_action() = runTest(dispatcher) {
        var refreshes = 0
        val model = SeriesDetailsViewModel(reload = { refreshes++; details(it) }) {
            details(it).copy(translations = emptyList(), defaultTranslationId = null,
                playbackUnavailableReason = "Сезон недоступен")
        }
        model.open(entry()); advanceUntilIdle()
        assertFalse(model.state.value.canPlay)
        assertNull(model.playbackRequest())
        model.retry(); advanceUntilIdle()
        assertEquals(1, refreshes)
        assertTrue(model.state.value.canPlay)
        assertNotNull(model.playbackRequest())
    }

    @Test fun explicit_site_restriction_prevents_playback_even_with_leftover_episodes() = runTest(dispatcher) {
        val model = SeriesDetailsViewModel { details(it).copy(playbackUnavailableReason = "Сезон недоступен") }
        model.open(entry()); advanceUntilIdle()
        assertFalse(model.state.value.canPlay)
        assertNull(model.playbackRequest())
    }

    @Test fun account_mark_survives_details_load_but_is_not_applied_to_another_season() = runTest(dispatcher) {
        val mark = AccountMark("Отмечено 3 из 10", "Новые серии")
        val model = SeriesDetailsViewModel { details(it) }
        model.open(entry().copy(accountMark = mark, historyVisitedAt = "03.11.2026", historyEpisode = "7")); advanceUntilIdle()
        assertEquals(mark, model.state.value.entry!!.accountMark)
        assertEquals("03.11.2026", model.state.value.entry!!.historyVisitedAt)
        assertEquals("7", model.state.value.entry!!.historyEpisode)
        model.selectSeason("2"); advanceUntilIdle()
        assertEquals("2", model.state.value.entry!!.id)
        assertNull(model.state.value.entry!!.accountMark)
        assertNull(model.state.value.entry!!.historyVisitedAt)
        assertNull(model.state.value.entry!!.historyEpisode)
    }

}
