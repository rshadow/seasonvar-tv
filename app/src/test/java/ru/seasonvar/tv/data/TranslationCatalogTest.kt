package ru.seasonvar.tv.data

import org.junit.Assert.*
import org.junit.Test

class TranslationCatalogTest {
    private fun html(options: String) = "<select data-filter='golosa'><option value='all'>Любой</option>$options</select>"
    @Test fun global_filter_values_are_scoped_and_keep_site_order() {
        val source = "<select data-filter='genre'><option value='fake'>Жанр</option></select>" +
            html("<option value='AlexFilm'>AlexFilm</option><option value='AniDub'>AniDub</option>")
        assertEquals(listOf("AlexFilm", "AniDub"), TranslationCatalogParser.parse(source))
    }
    @Test fun equivalent_names_are_deduplicated_and_html_entities_decoded() {
        assertEquals(listOf("A & B", "AlexFilm"), TranslationCatalogParser.parse(html(
            "<option value='A &amp; B'>A &amp; B</option><option value='AlexFilm'>AlexFilm</option><option value=' alexfilm '>alexfilm</option>")))
    }
    @Test fun block_page_missing_filter_empty_or_invalid_filter_is_not_an_empty_catalog() {
        listOf("<html>Blocked</html>", html(""), html("<option value=''>Название</option>"),
            html("<option value='Studio'></option>"), html("<option value='Studio'>Studio</option>").repeat(2)).forEach {
            assertThrows(MarkupChangedException::class.java) { TranslationCatalogParser.parse(it) }
        }
    }

    @Test fun preferences_roundtrip_preserves_order_and_discards_duplicates() {
        val values = listOf(" AlexFilm ", "AniDub", "alexfilm", "", " A  B ")
        val encoded = TranslationPriorities.encode(values)
        assertEquals(listOf("AlexFilm", "AniDub", "A B"), TranslationPriorities.decode(encoded))
        assertEquals(emptyList<String>(), TranslationPriorities.decode(TranslationPriorities.encode(emptyList())))
    }
    @Test fun malformed_saved_preferences_are_not_guessed_or_treated_as_a_single_selection() {
        listOf(null, "AlexFilm", "{}", "[1]", "[\"A\",null]").forEach {
            assertTrue(TranslationPriorities.decode(it).isEmpty())
        }
    }
}
