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
        model.open(entry().copy(accountMark = mark, historyVisitedAt = "03.11.2026", initialSelection = EntrySelection(episodeLabel = "7"))); advanceUntilIdle()
        assertEquals(mark, model.state.value.entry!!.accountMark)
        assertEquals("03.11.2026", model.state.value.entry!!.historyVisitedAt)
        assertEquals("7", model.state.value.entry!!.initialSelection.episodeLabel)
        model.selectSeason("2"); advanceUntilIdle()
        assertEquals("2", model.state.value.entry!!.id)
        assertNull(model.state.value.entry!!.accountMark)
        assertNull(model.state.value.entry!!.historyVisitedAt)
        assertNull(model.state.value.entry!!.initialSelection.episodeLabel)
    }

    @Test fun history_context_selects_exact_episode_and_voice_instead_of_local_progress() = runTest(dispatcher) {
        val point = ResumePoint("1", "0", "1_seriya", 12000)
        val model = SeriesDetailsViewModel(loadResume = { point }) { details(it) }
        model.open(entry().copy(initialSelection = EntrySelection(episodeLabel = "2", translationLabel = "Субтитры")))
        advanceUntilIdle()
        assertEquals("1", model.state.value.translationId)
        assertEquals("sub_2", model.state.value.episodeId)
        assertNull(model.state.value.resume)
        assertEquals("sub_2", model.playbackRequest()!!.episodeId)
    }
    @Test fun exact_source_selection_keeps_resume_time_only_for_the_same_episode_and_voice() = runTest(dispatcher) {
        val point = ResumePoint("1", "1", "sub_2", 12000)
        val model = SeriesDetailsViewModel(loadResume = { point }) { details(it) }
        model.open(entry().copy(initialSelection = EntrySelection(episodeLabel = "2", translationId = "1")))
        advanceUntilIdle(); assertEquals(point, model.state.value.resume)
        model.open(entry().copy(initialSelection = EntrySelection(episodeLabel = "1", translationId = "0")))
        advanceUntilIdle(); assertNull(model.state.value.resume)
    }
    @Test fun missing_source_voice_falls_back_to_standard_without_losing_the_source_episode() = runTest(dispatcher) {
        val model = SeriesDetailsViewModel { details(it) }
        model.open(entry().copy(initialSelection = EntrySelection(episodeLabel = "2", translationLabel = "Удалённая")))
        advanceUntilIdle()
        assertEquals("0", model.state.value.translationId)
        assertEquals("2_seriya", model.state.value.episodeId)
        assertNull(model.state.value.selectionFailure)
        assertTrue(model.state.value.canPlay)
        model.selectTranslation("1")
        assertEquals("sub_2", model.state.value.episodeId)
        assertTrue(model.state.value.canPlay)
    }
    @Test fun missing_source_episode_is_not_played_as_the_first_episode() = runTest(dispatcher) {
        val model = SeriesDetailsViewModel { details(it) }
        model.open(entry().copy(initialSelection = EntrySelection(episodeLabel = "99", translationId = "0")))
        advanceUntilIdle()
        assertNotNull(model.state.value.selectionFailure)
        assertNull(model.state.value.episodeId); assertNull(model.playbackRequest())
        model.selectEpisode("2_seriya")
        assertNull(model.state.value.selectionFailure)
        assertEquals("2_seriya", model.playbackRequest()!!.episodeId)
    }
    @Test fun retry_and_late_resume_read_do_not_undo_manual_selection() = runTest(dispatcher) {
        var point = ResumePoint("1", "0", "1_seriya", 12000)
        val model = SeriesDetailsViewModel(loadResume = { delay(100); point }) { details(it) }
        model.open(entry().copy(initialSelection = EntrySelection(episodeLabel = "2", translationId = "1")))
        advanceUntilIdle(); model.selectTranslation("0"); model.selectEpisode("2_seriya")
        model.refreshResume(); runCurrent(); model.selectEpisode("1_seriya"); advanceUntilIdle()
        assertEquals("1_seriya", model.state.value.episodeId)
        model.selectTranslation("1"); model.retry(); advanceUntilIdle()
        assertEquals("1", model.state.value.translationId)
        assertEquals("sub_2", model.state.value.episodeId)
        assertNull(model.state.value.resume)
    }
    @Test fun two_records_of_the_same_season_do_not_share_their_initial_selection() = runTest(dispatcher) {
        val model = SeriesDetailsViewModel {
            if (it.initialSelection.episodeLabel == "1") withContext(NonCancellable) { delay(500) }
            details(it)
        }
        model.open(entry().copy(initialSelection = EntrySelection(episodeLabel = "1", translationId = "0")))
        runCurrent()
        model.open(entry().copy(initialSelection = EntrySelection(episodeLabel = "2", translationId = "1")))
        advanceUntilIdle()
        assertEquals("1", model.state.value.translationId)
        assertEquals("sub_2", model.state.value.episodeId)
    }

    @Test fun manual_selection_and_retry_do_not_rewrite_metadata_of_the_original_history_record() = runTest(dispatcher) {
        val source = entry().copy(historyVisitedAt = "03.11.2026", initialSelection = EntrySelection(episodeLabel = "1", translationId = "0"))
        val model = SeriesDetailsViewModel { details(it) }
        model.open(source); advanceUntilIdle(); model.selectEpisode("2_seriya"); model.retry(); advanceUntilIdle()
        assertEquals("2_seriya", model.state.value.episodeId)
        assertEquals(source.initialSelection, model.state.value.entry!!.initialSelection)
        assertEquals(source.historyVisitedAt, model.state.value.entry!!.historyVisitedAt)
    }

    @Test fun choosing_voice_when_episode_data_is_missing_keeps_the_known_episode() = runTest(dispatcher) {
        val model = SeriesDetailsViewModel { entry -> details(entry).copy(translations = listOf(
            Translation("0", "Стандартная", null),
            Translation("1", "Субтитры", listOf(Episode("sub3", "3"), Episode("sub_2", "2"))),
        )) }
        model.open(entry().copy(initialSelection = EntrySelection(episodeLabel = "2", translationId = "0")))
        advanceUntilIdle(); assertNull(model.state.value.episodeId)
        model.selectTranslation("1")
        assertEquals("sub_2", model.state.value.episodeId)
        assertTrue(model.state.value.canPlay)
    }

    @Test fun progress_loads_in_background_without_resetting_manual_episode_selection() = runTest(dispatcher) {
        val pending = kotlinx.coroutines.CompletableDeferred<AccountProgress?>()
        val model = SeriesDetailsViewModel(loadAccountProgress = { pending.await() }) { details(it) }
        model.open(entry()); runCurrent()
        assertFalse(model.state.value.loading)
        assertTrue(model.state.value.canPlay)
        model.selectEpisode("2_seriya")
        val progress = AccountProgress(ProgressCounter(2, 5), SeasonEpisodeProgress("1", ProgressCounter(3, 10)))
        pending.complete(progress); advanceUntilIdle()
        assertEquals("2_seriya", model.state.value.episodeId)
        assertEquals(progress, model.state.value.entry!!.accountProgress)
        assertEquals(progress, model.state.value.details!!.entry.accountProgress)
    }
    @Test fun progress_network_error_does_not_break_playback_or_fabricate_zero_counters() = runTest(dispatcher) {
        val model = SeriesDetailsViewModel(loadAccountProgress = { throw IOException() }) { details(it) }
        model.open(entry()); advanceUntilIdle()
        assertTrue(model.state.value.canPlay)
        assertEquals(LoadFailure.NETWORK, model.state.value.progressFailure)
        assertNull(model.state.value.entry!!.accountProgress)
    }
    @Test fun season_change_clears_the_previous_mark_until_new_progress_arrives() = runTest(dispatcher) {
        val pending = kotlinx.coroutines.CompletableDeferred<AccountProgress?>()
        val model = SeriesDetailsViewModel(loadAccountProgress = { d ->
            if (d.entry.id == "1") AccountProgress(ProgressCounter(2, 5), SeasonEpisodeProgress("1", ProgressCounter(3, 10)))
            else pending.await()
        }) { details(it) }
        model.open(entry()); advanceUntilIdle(); model.selectSeason("2"); runCurrent()
        assertNull(model.state.value.entry!!.accountProgress)
        pending.complete(AccountProgress(ProgressCounter(2, 5), SeasonEpisodeProgress("2", ProgressCounter(1, 8))))
        advanceUntilIdle()
        assertEquals("2", model.state.value.entry!!.accountProgress!!.episodes!!.seasonId)
    }
    @Test fun old_progress_cannot_overwrite_another_record_or_restore_a_closed_card() = runTest(dispatcher) {
        val model = SeriesDetailsViewModel(loadAccountProgress = { d ->
            if (d.entry.title == "old") withContext(NonCancellable) { delay(1000) }
            AccountProgress(ProgressCounter(if (d.entry.title == "old") 1 else 2, 5))
        }) { details(it) }
        model.open(entry().copy(title = "old")); runCurrent()
        model.open(entry().copy(title = "new")); advanceUntilIdle()
        assertEquals(ProgressCounter(2, 5), model.state.value.entry!!.accountProgress!!.seasons)
        model.open(entry().copy(title = "old")); runCurrent(); model.close(); advanceUntilIdle()
        assertNull(model.state.value.entry)
    }

    @Test fun playback_request_contains_only_selected_translations_ordered_episodes() = runTest(dispatcher) {
        val model = SeriesDetailsViewModel { details(it) }
        model.open(entry()); advanceUntilIdle()
        assertEquals(listOf("1_seriya", "2_seriya"), model.playbackRequest()!!.episodes.map { it.id })
        model.selectTranslation("1")
        assertEquals(listOf(Episode("sub_2", "2")), model.playbackRequest()!!.episodes)
    }

    @Test fun player_return_selects_last_episode_only_in_matching_season_and_translation() = runTest(dispatcher) {
        val original = entry().copy(initialSelection = EntrySelection(episodeLabel = "1"))
        val model = SeriesDetailsViewModel { details(it) }
        model.open(original); advanceUntilIdle()
        model.onPlaybackFinished("2", "0", "2_seriya")
        model.onPlaybackFinished("1", "1", "2_seriya")
        model.onPlaybackFinished("1", "0", "missing")
        assertEquals("1_seriya", model.state.value.episodeId)
        model.onPlaybackFinished("1", "0", "2_seriya")
        assertEquals("2_seriya", model.state.value.episodeId)
        assertEquals(original, model.state.value.entry)
    }


    @Test fun account_mark_is_resolved_for_the_selected_season_and_removed_without_changing_episode_selection() = runTest(dispatcher) {
        val model = SeriesDetailsViewModel(loadAccountMark = { ru.seasonvar.tv.data.account.AccountListMark(it.id, it.url) }) { details(it) }
        model.open(entry("1")); advanceUntilIdle()
        model.selectEpisode("2_seriya")
        val target = model.state.value.entry!!.accountListMark!!
        assertEquals("1", target.seasonId)
        model.markRemoved(target)
        assertNull(model.state.value.entry!!.accountListMark)
        assertEquals("2_seriya", model.state.value.episodeId)
        model.selectSeason("2"); advanceUntilIdle()
        assertEquals("2", model.state.value.entry!!.accountListMark!!.seasonId)
    }
}
