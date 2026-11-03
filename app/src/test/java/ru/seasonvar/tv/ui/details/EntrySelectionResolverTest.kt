package ru.seasonvar.tv.ui.details

import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.*

class EntrySelectionResolverTest {
    private val entry = SeriesEntry("1", "https://seasonvar.ru/serial-1-test.html", "Тест", 2, "", "")
    private val a = Translation("a", "Озвучка A", listOf(Episode("a7", "7"), Episode("a10", "10")))
    private val b = Translation("b", "Озвучка B", listOf(Episode("b7", "7"), Episode("b10", "10"), Episode("special", "Спецвыпуск")))
    private val details = SeriesDetails(entry, "100", emptyList(), emptyList(), emptyList(), emptyList(), listOf(a, b), "a")
    private val resume = ResumePoint("1", "b", "b10", 15000)
    @Test fun explicit_episode_and_voice_override_local_resume() {
        assertEquals(ResolvedSelection("a", "a7"), EntrySelectionResolver.resolve(details,
            EntrySelection(episodeLabel = "7", translationLabel = "Озвучка A"), resume))
    }
    @Test fun explicit_ids_take_precedence_over_labels() {
        assertEquals(ResolvedSelection("a", "a7"), EntrySelectionResolver.resolve(details,
            EntrySelection(episodeId = "a7", episodeLabel = "10", translationId = "a", translationLabel = "Озвучка B"), resume))
    }
    @Test fun episode_without_voice_uses_a_compatible_local_voice() {
        assertEquals(ResolvedSelection("b", "b7"), EntrySelectionResolver.resolve(details, EntrySelection(episodeLabel = "7"), resume))
    }
    @Test fun unknown_parameters_use_valid_local_resume_then_site_default() {
        assertEquals(ResolvedSelection("b", "b10"), EntrySelectionResolver.resolve(details, EntrySelection(), resume))
        assertEquals(ResolvedSelection("a", "a7"), EntrySelectionResolver.resolve(details, EntrySelection(), null))
    }
    @Test fun explicit_voice_does_not_take_an_episode_from_another_voice_resume() {
        assertEquals(ResolvedSelection("a", "a7"), EntrySelectionResolver.resolve(details, EntrySelection(translationId = "a"), resume))
    }
    @Test fun default_voice_is_skipped_when_the_requested_episode_only_exists_elsewhere() {
        assertEquals(ResolvedSelection("b", "special"), EntrySelectionResolver.resolve(details,
            EntrySelection(episodeLabel = "Спецвыпуск"), null))
    }
    @Test fun unavailable_episode_in_an_explicit_voice_is_not_replaced_by_another_episode_or_voice() {
        val result = EntrySelectionResolver.resolve(details, EntrySelection(episodeLabel = "Спецвыпуск", translationId = "a"), resume)
        assertEquals("a", result.translationId); assertNull(result.episodeId)
        assertNotNull(result.failure)
    }
    @Test fun unavailable_or_ambiguous_voice_falls_back_to_standard_instead_of_local_resume() {
        assertEquals(ResolvedSelection("a", "a7"), EntrySelectionResolver.resolve(details,
            EntrySelection(episodeLabel = "7", translationLabel = "Неизвестная"), resume))
        val duplicated = details.copy(translations = listOf(a, b.copy(label = a.label)))
        assertEquals(ResolvedSelection("a", "a7"), EntrySelectionResolver.resolve(duplicated,
            EntrySelection(episodeLabel = "7", translationLabel = a.label), resume))
    }
    @Test fun standard_voice_id_is_preferred_even_when_the_site_default_is_another_voice() {
        val standard = a.copy(id = "0")
        val page = details.copy(translations = listOf(b, standard), defaultTranslationId = "b")
        assertEquals(ResolvedSelection("0", "a7"), EntrySelectionResolver.resolve(page,
            EntrySelection(episodeLabel = "7", translationId = "removed"), resume))
    }
    @Test fun missing_voice_does_not_jump_to_another_voice_when_episode_is_missing_in_standard() {
        val selected = EntrySelectionResolver.resolve(details,
            EntrySelection(episodeLabel = "Спецвыпуск", translationLabel = "Удалённая"), resume)
        assertEquals("a", selected.translationId)
        assertNotNull(selected.failure)
        assertNull(selected.episodeId)
    }
    @Test fun labels_normalise_case_whitespace_and_numeric_padding_without_using_list_indexes() {
        assertEquals(ResolvedSelection("b", "b7"), EntrySelectionResolver.resolve(details,
            EntrySelection(episodeLabel = "007", translationLabel = "  ОЗВУЧКА   b "), null))
    }
    @Test fun stale_local_resume_is_ignored_instead_of_selecting_another_season() {
        assertNull(EntrySelectionResolver.validResume(resume.copy(seasonId = "2"), details))
        assertNull(EntrySelectionResolver.validResume(resume.copy(episodeId = "missing"), details))
        assertNull(EntrySelectionResolver.validResume(resume.copy(positionMillis = 0), details))
    }
    @Test fun ambiguous_episode_label_is_not_guessed() {
        val duplicate = details.copy(translations = listOf(a.copy(episodes = listOf(Episode("x", "7"), Episode("y", "7")))))
        val selected = EntrySelectionResolver.resolve(duplicate, EntrySelection(episodeLabel = "7"), null)
        assertNull(selected.episodeId)
        assertNotNull(selected.failure)
    }
    @Test fun missing_episode_data_is_not_reported_as_a_confirmed_missing_episode() {
        val page = details.copy(translations = listOf(a.copy(episodes = null)))
        val selection = EntrySelectionResolver.resolve(page, EntrySelection(episodeLabel = "7"), null)
        assertEquals("a", selection.translationId)
        assertNull(selection.episodeId)
        assertNull(selection.failure)
    }

}
