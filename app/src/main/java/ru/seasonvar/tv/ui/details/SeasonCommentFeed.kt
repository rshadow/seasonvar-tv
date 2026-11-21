package ru.seasonvar.tv.ui.details

import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.comments.CommentPart
import ru.seasonvar.tv.data.comments.CommentsPage
import ru.seasonvar.tv.data.comments.SeasonComment
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

internal class SeasonCommentFeed {
    var entries by mutableStateOf(emptyList<SeasonComment>())
        private set
    var next by mutableStateOf<Int?>(1)
        private set
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    fun retry() { failed = false }

    suspend fun append(page: Int, entry: SeriesEntry, load: suspend (SeriesEntry, Int) -> CommentsPage) {
        if (next != page || loading || failed) return
        loading = true
        try {
            val result = load(entry, page)
            entries = (entries + result.entries).distinctBy(SeasonComment::id)
            next = result.next
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }
}

internal fun Modifier.returnFromFirstComment(returnToSeason: (() -> Unit)?) =
    if (returnToSeason == null) this else onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp) {
            returnToSeason()
            true
        } else false
    }

@Composable
internal fun SeasonCommentRow(comment: SeasonComment, first: FocusRequester?, returnToSeason: (() -> Unit)?) {
    val d = SeasonvarTheme.dimensions
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val firstSpoiler = comment.parts.indexOfFirst { it is CommentPart.Spoiler }
    val initialFocus = if (first == null) Modifier else Modifier.focusRequester(first)
    val focusRow = if (firstSpoiler < 0) initialFocus.returnFromFirstComment(returnToSeason).focusable() else Modifier.focusGroup()
    Column(
        Modifier.fillMaxWidth().padding(start = if (comment.reply) d.spacingLarge else d.spacingSmall)
            .onFocusChanged { focused = it.hasFocus }
            .border(d.focusBorderWidth, if (focused) colors.primary else colors.surfaceVariant, RoundedCornerShape(d.cardCornerRadius))
            .then(focusRow).padding(d.spacingMedium),
        verticalArrangement = Arrangement.spacedBy(d.spacingSmall),
    ) {
        val author = comment.author ?: stringResource(R.string.reviews_author_unknown)
        val title = listOfNotNull(author, comment.date).joinToString(" · ")
        Text(if (comment.reply) "↳ $title" else title, style = MaterialTheme.typography.titleSmall, color = colors.onBackground)
        comment.parts.forEachIndexed { index, part ->
            val modifier = if (index == firstSpoiler) initialFocus.returnFromFirstComment(returnToSeason) else Modifier
            CommentBody(part, modifier)
        }
    }
}

@Composable
private fun CommentBody(part: CommentPart, modifier: Modifier = Modifier) {
    when (part) {
        is CommentPart.Text -> Text(part.value, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground)
        is CommentPart.Spoiler -> {
            var revealed by rememberSaveable { mutableStateOf(false) }
            ThemeButton(stringResource(if (revealed) R.string.comment_hide_spoiler else R.string.comment_show_spoiler),
                { revealed = !revealed }, modifier)
            if (revealed) part.parts.forEach { CommentBody(it) }
        }
    }
}
