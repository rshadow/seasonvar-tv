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
}
