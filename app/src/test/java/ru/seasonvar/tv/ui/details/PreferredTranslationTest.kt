package ru.seasonvar.tv.ui.details

import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.*

class PreferredTranslationTest {
    private val entry = SeriesEntry("1", "https://seasonvar.ru/serial-1-test.html", "Тест", 1, "", "")
    private val standard = Translation("0", "Стандартный", listOf(Episode("s1", "1"), Episode("s2", "2")))
    private val alex = Translation("25", "AlexFilm", listOf(Episode("a1", "1"), Episode("a2", "2")))
    private val ani = Translation("17", "AniDub", listOf(Episode("b1", "1"), Episode("b2", "2")))
    private val details = SeriesDetails(entry, "1", emptyList(), emptyList(), emptyList(), emptyList(), listOf(standard, alex, ani), "17")
    private fun resolve(preferred: List<String>, hint: EntrySelection = EntrySelection(), resume: ResumePoint? = null) =
        EntrySelectionResolver.resolve(details, hint, resume, preferred)
    @Test fun chooses_first_available_name_in_priority_order_not_site_order() {
        assertEquals("25", resolve(listOf("Missing", "AlexFilm", "AniDub")).translationId)
        assertEquals("17", resolve(listOf("AniDub", "AlexFilm")).translationId)
        assertEquals("25", resolve(listOf("  ALEXFILM ")).translationId)
    }
    @Test fun empty_or_entirely_unavailable_list_falls_back_to_standard() {
        assertEquals("0", resolve(emptyList()).translationId)
        assertEquals("0", resolve(listOf("Removed A", "Removed B")).translationId)
    }
    @Test fun changing_order_changes_selected_voice() {
        assertEquals("25", resolve(listOf("AlexFilm", "AniDub")).translationId)
        assertEquals("17", resolve(listOf("AniDub", "AlexFilm")).translationId)
    }
    @Test fun ambiguous_name_is_skipped_in_favour_of_next_priority() {
        val ambiguous = details.copy(translations = details.translations + alex.copy(id = "99"))
        assertEquals("17", EntrySelectionResolver.resolve(ambiguous, EntrySelection(), null, listOf("AlexFilm", "AniDub")).translationId)
        assertEquals("0", EntrySelectionResolver.resolve(ambiguous, EntrySelection(), null, listOf("AlexFilm")).translationId)
    }
    @Test fun same_name_with_a_different_id_is_selected_for_another_season() {
        val another = details.copy(translations = listOf(ani, alex.copy(id = "999"), standard))
        assertEquals("999", EntrySelectionResolver.resolve(another, EntrySelection(), null, listOf("AlexFilm")).translationId)
    }
    @Test fun explicit_source_voice_and_missing_voice_fallback_override_priorities() {
        assertEquals("17", resolve(listOf("AlexFilm"), EntrySelection(translationLabel = "AniDub")).translationId)
        assertEquals("0", resolve(listOf("AlexFilm"), EntrySelection(translationLabel = "Removed")).translationId)
    }
    @Test fun compatible_local_continuation_overrides_priorities() {
        val resume = ResumePoint("1", "17", "b2", 5000)
        assertEquals(ResolvedSelection("17", "b2"), resolve(listOf("AlexFilm"), resume = resume))
        assertEquals("25", resolve(listOf("AlexFilm"), resume = resume.copy(seasonId = "other")).translationId)
    }
    @Test fun known_episode_uses_first_priority_containing_that_episode() {
        val changed = details.copy(translations = listOf(standard, alex.copy(episodes = listOf(Episode("a1", "1"))), ani))
        assertEquals(ResolvedSelection("17", "b2"), EntrySelectionResolver.resolve(changed,
            EntrySelection(episodeLabel = "2"), null, listOf("AlexFilm", "AniDub")))
        val missing = resolve(listOf("AlexFilm"), EntrySelection(episodeLabel = "99"))
        assertNull(missing.episodeId)
        assertNotNull(missing.failure)
    }
    @Test fun unavailable_playback_does_not_invent_voice_or_episode() {
        assertEquals(ResolvedSelection(null, null), EntrySelectionResolver.resolve(details.copy(playbackUnavailableReason = "blocked"),
            EntrySelection(), null, listOf("AlexFilm")))
    }
}
