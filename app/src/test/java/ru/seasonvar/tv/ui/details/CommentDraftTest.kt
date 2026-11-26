package ru.seasonvar.tv.ui.details

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class CommentDraftTest {
    @Test fun spoiler_inserts_at_selection_and_voice_edits_only_that_block() {
        val draft = CommentDraft()
        draft.edit(0, TextFieldValue("До секрет после", TextRange(3, 9)))
        val spoiler = draft.insertSpoiler()
        assertEquals("До [spoiler]секрет[/spoiler] после", draft.text())
        draft.insertVoice(spoiler, "один")
        draft.insertVoice(spoiler, "два")
        assertEquals("До [spoiler]секрет один два[/spoiler] после", draft.text())
    }

    @Test fun blank_spoilers_do_not_enable_sending_a_comment() {
        val draft = CommentDraft()
        draft.insertSpoiler()
        assertEquals("", draft.text())
    }

    @Test fun removing_a_spoiler_preserves_text_and_cursor() {
        val draft = CommentDraft()
        draft.edit(0, TextFieldValue("  До секрет\nпосле  ", TextRange(5, 11)))
        val spoiler = draft.insertSpoiler()
        draft.edit(spoiler, TextFieldValue("секрет", TextRange(2)))
        draft.active = spoiler + 1
        assertEquals(0, draft.removeSpoiler(spoiler))
        assertEquals(0, draft.active)
        assertEquals(TextFieldValue("  До секрет\nпосле  ", TextRange(7)), draft.blocks.single().value)
    }

    @Test fun removing_a_spoiler_inserts_missing_spaces_and_preserves_selection() {
        val draft = CommentDraft()
        draft.edit(0, TextFieldValue("Досекретпосле", TextRange(2, 8)))
        val spoiler = draft.insertSpoiler()
        draft.edit(spoiler, TextFieldValue("секрет", TextRange(2, 4)))

        draft.removeSpoiler(spoiler)

        assertEquals(TextFieldValue("До секрет после", TextRange(5, 7)), draft.blocks.single().value)
    }

    @Test fun removing_one_spoiler_preserves_other_spoilers() {
        val draft = CommentDraft()
        draft.edit(0, TextFieldValue("начало  конец", TextRange(7)))
        val first = draft.insertSpoiler()
        draft.edit(first, TextFieldValue("первый "))
        val second = draft.insertSpoiler()
        draft.edit(second, TextFieldValue("второй"))
        draft.removeSpoiler(first)
        assertEquals("начало первый [spoiler]второй[/spoiler] конец", draft.text())
        draft.removeSpoiler(1)
        assertEquals("начало первый второй конец", draft.text())
    }
}
