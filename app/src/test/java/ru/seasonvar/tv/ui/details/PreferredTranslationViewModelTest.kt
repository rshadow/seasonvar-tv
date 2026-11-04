package ru.seasonvar.tv.ui.details

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.seasonvar.tv.data.*

@OptIn(ExperimentalCoroutinesApi::class)
class PreferredTranslationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private val entry = SeriesEntry("1", "https://seasonvar.ru/serial-1-test.html", "Test", 1, "", "")
    private val details = SeriesDetails(entry, "1", emptyList(), emptyList(), emptyList(), emptyList(),
        listOf(Translation("0", "Стандартный", listOf(Episode("s1", "1"))),
            Translation("12", "AlexFilm", listOf(Episode("a1", "1")))), "0")
    @Test fun settings_are_read_when_opening_and_manual_switch_does_not_rewrite_preference() = runTest(dispatcher) {
        var preferred = listOf("AlexFilm")
        val model = SeriesDetailsViewModel(preferredTranslations = { preferred }) { details }
        model.open(entry); advanceUntilIdle()
        assertEquals("12", model.state.value.translationId)
        model.selectTranslation("0")
        assertEquals(listOf("AlexFilm"), preferred)
        assertEquals("0", model.state.value.translationId)
        model.close(); preferred = emptyList()
        model.open(entry); advanceUntilIdle()
        assertEquals("0", model.state.value.translationId)
    }
    @Test fun changing_seasons_uses_the_same_name_with_the_new_seasons_translation_id() = runTest(dispatcher) {
        val next = entry.copy(id = "2", url = "https://seasonvar.ru/serial-2-test.html", season = 2)
        val seasons = listOf(SeasonOption("1", entry.url, "1", 1), SeasonOption("2", next.url, "2", 2))
        val model = SeriesDetailsViewModel(preferredTranslations = { listOf("AlexFilm") }) { requested ->
            details.copy(entry = requested, seasons = seasons, translations = if (requested.id == "1") details.translations
                else listOf(details.translations[0], details.translations[1].copy(id = "99")))
        }
        model.open(entry); advanceUntilIdle()
        model.selectSeason("2"); advanceUntilIdle()
        assertEquals("99", model.state.value.translationId)
        assertEquals("2", model.state.value.entry!!.id)
    }
}
