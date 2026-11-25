package ru.seasonvar.tv.ui.details

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal data class CommentDraftBlock(val value: TextFieldValue = TextFieldValue(), val spoiler: Boolean = false)

internal class CommentDraft {
    var blocks by mutableStateOf(listOf(CommentDraftBlock()))
        private set
    var active by mutableStateOf(0)

    fun edit(index: Int, value: TextFieldValue) {
        blocks = blocks.toMutableList().apply { this[index] = this[index].copy(value = value) }
    }

    fun insertSpoiler(): Int {
        val current = blocks[active]
        val index = active + 1
        val updated = blocks.toMutableList()
        if (current.spoiler) {
            updated.add(index, CommentDraftBlock(spoiler = true))
        } else {
            val (from, to) = current.value.selection.let { it.min to it.max }
            val text = current.value.text
            updated[active] = CommentDraftBlock(TextFieldValue(text.substring(0, from)))
            updated.add(index, CommentDraftBlock(TextFieldValue(text.substring(from, to), TextRange(to - from)), spoiler = true))
            updated.add(index + 1, CommentDraftBlock(TextFieldValue(text.substring(to))))
        }
        blocks = updated
        active = index
        return index
    }

    fun removeSpoiler(): Int {
        val current = blocks[active]
        require(current.spoiler)
        val first = (active - 1).takeIf { blocks.getOrNull(it)?.spoiler == false } ?: active
        val last = (active + 1).takeIf { blocks.getOrNull(it)?.spoiler == false } ?: active
        val text = blocks.subList(first, last + 1).joinToString("") { it.value.text }
        val offset = if (first < active) blocks[first].value.text.length else 0
        val selection = TextRange(offset + current.value.selection.start, offset + current.value.selection.end)
        blocks = blocks.take(first) + CommentDraftBlock(TextFieldValue(text, selection)) + blocks.drop(last + 1)
        active = first
        return first
    }

    fun insertVoice(index: Int, spoken: String) {
        val value = blocks[index].value
        val from = value.selection.min
        val to = value.selection.max
        val left = value.text.substring(0, from)
        val right = value.text.substring(to)
        val prefix = if (left.lastOrNull()?.isLetterOrDigit() == true && spoken.firstOrNull()?.isLetterOrDigit() == true) " " else ""
        val suffix = if (right.firstOrNull()?.isLetterOrDigit() == true && spoken.lastOrNull()?.isLetterOrDigit() == true) " " else ""
        val inserted = left + prefix + spoken + suffix
        edit(index, TextFieldValue(inserted + right, TextRange(inserted.length)))
    }

    fun text(): String = blocks.joinToString("") { block ->
        val text = block.value.text
        when {
            !block.spoiler -> text
            text.isBlank() -> ""
            else -> "[spoiler]" + text + "[/spoiler]"
        }
    }.trim()
}
