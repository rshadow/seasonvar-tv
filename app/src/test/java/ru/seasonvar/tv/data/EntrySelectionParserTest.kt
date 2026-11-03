package ru.seasonvar.tv.data

import org.junit.Assert.*
import org.junit.Test

class EntrySelectionParserTest {
    @Test fun reads_episode_and_voice_as_independent_optional_fields() {
        assertEquals(EntrySelection(episodeLabel = "7", translationLabel = "Студия A"), EntrySelectionParser.text(", 2 сезон, 7 серия (Студия A)"))
        assertEquals(EntrySelection(episodeLabel = "7–8"), EntrySelectionParser.text("7 – 8 серии"))
        assertEquals(EntrySelection(), EntrySelectionParser.text("Сезон 2"))
    }
    @Test fun multiple_updates_keep_only_parameters_common_to_all_records() {
        assertEquals(EntrySelection(episodeLabel = "8"), EntrySelectionParser.updates(listOf("8 серия (A)", "8 серия (B)")))
        assertEquals(EntrySelection(translationLabel = "A"), EntrySelectionParser.updates(listOf("8 серия (A)", "9 серия (A)")))
        assertEquals(EntrySelection(), EntrySelectionParser.updates(listOf("8 серия (A)", "Без номера")))
    }
    @Test fun only_a_recognised_site_mark_yields_an_episode_id() {
        assertEquals(EntrySelection(episodeId = "7_seriya"), EntrySelectionParser.markLink("/serial-1-test.html#rewind=7_seriya_minute_3_sekunde_10"))
        listOf("/serial-1-test.html#rewind=-1_seriya", "/serial-1-test.html#rewind=3_example", "/serial-1-test.html",
            "https://foreign.example.test/serial-1.html#rewind=7_seriya", "//foreign.example.test/serial-1.html#rewind=7_seriya")
            .forEach { assertFalse(EntrySelectionParser.markLink(it).hasEpisode) }
    }
}
