package ru.seasonvar.tv.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.comments.CommentPart
import ru.seasonvar.tv.data.comments.CommentsPage
import ru.seasonvar.tv.data.comments.SeasonComment
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

internal class SeasonCommentFeed {
    private val mutex = Mutex()
    private val pages = sortedMapOf<Int, CommentsPage>()
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
            mutex.withLock {
                if (next == page && !failed) {
                    pages[page] = load(entry, page)
                    updateEntries()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }

    suspend fun refresh(parentId: String?, entry: SeriesEntry, load: suspend (SeriesEntry, Int) -> CommentsPage) {
        mutex.withLock {
            val page = pages.entries.firstOrNull { (_, result) -> result.entries.any { it.id == parentId } }?.key
            if (page != null) pages[page] = load(entry, page)
            else {
                val refreshed = sortedMapOf<Int, CommentsPage>()
                var nextPage: Int? = 1
                repeat(pages.size.coerceAtLeast(1)) {
                    val current = nextPage ?: return@repeat
                    val result = load(entry, current)
                    refreshed[current] = result
                    nextPage = result.next
                }
                pages.clear()
                pages.putAll(refreshed)
            }
            failed = false
            updateEntries()
        }
    }

    private fun updateEntries() {
        entries = pages.values.flatMap(CommentsPage::entries).distinctBy(SeasonComment::id)
        next = pages[pages.lastKey()]?.next
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
internal fun SeasonCommentRow(comment: SeasonComment, first: FocusRequester?, returnToSeason: (() -> Unit)?,
    onReply: ((FocusRequester) -> Unit)? = null) {
    val d = SeasonvarTheme.dimensions
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    var replyFocused by remember { mutableStateOf(false) }
    var allowReplyFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val defaultReadingFocus = remember { FocusRequester() }
    val readingFocus = first ?: defaultReadingFocus
    var lastReadingFocus by remember(readingFocus) { mutableStateOf(readingFocus) }
    val replyFocus = remember { FocusRequester() }
    val firstSpoiler = comment.parts.indexOfFirst { it is CommentPart.Spoiler }
    val highlight = if (focused) {
        val shape = RoundedCornerShape(d.cardCornerRadius)
        Modifier.background(colors.primary.copy(alpha = 0.08f), shape).border(d.focusBorderWidth, colors.primary, shape)
    } else Modifier
    Row(
        Modifier.fillMaxWidth().padding(horizontal = d.spacingSmall)
            .then(if (comment.reply) Modifier else Modifier.padding(top = d.spacingSmall).commentThreadDivider())
            .padding(top = SeasonvarTheme.surfaceOutlineWidth)
            .onFocusChanged { focused = it.hasFocus }
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown || onReply == null) false
                else when {
                    it.key == Key.DirectionRight && !replyFocused -> {
                        allowReplyFocus = true
                        scope.launch { withFrameNanos { }; if (focused) replyFocus.requestFocus() }
                        true
                    }
                    it.key == Key.DirectionLeft && replyFocused -> lastReadingFocus.requestFocus()
                    replyFocused && it.key == Key.DirectionUp && returnToSeason != null -> {
                        returnToSeason(); true
                    }
                    replyFocused && it.key in listOf(Key.DirectionUp, Key.DirectionDown) -> {
                        if (lastReadingFocus.requestFocus()) {
                            focusManager.moveFocus(if (it.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down)
                        }
                        true
                    }
                    else -> false
                }
            }.then(highlight).focusGroup().padding(d.spacingSmall),
        horizontalArrangement = Arrangement.spacedBy(d.spacingMedium),
    ) {
        Row(Modifier.weight(1f).then(if (firstSpoiler < 0) Modifier.focusRequester(readingFocus)
            .onFocusChanged { if (it.isFocused) lastReadingFocus = readingFocus }
            .returnFromFirstComment(returnToSeason).focusable() else Modifier),
            horizontalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
            Row(Modifier.weight(0.22f), horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                if (comment.reply) {
                    Text("↳", Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurfaceVariant)
                }
                Column {
                    Text(comment.author ?: stringResource(R.string.reviews_author_unknown),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                    comment.date?.let { date ->
                        Text(date, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            Column(Modifier.weight(0.78f), verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                comment.parts.forEachIndexed { index, part ->
                    CommentBody(part,
                        if (index == firstSpoiler) Modifier.returnFromFirstComment(returnToSeason) else Modifier,
                        readingFocus.takeIf { index == firstSpoiler }, { lastReadingFocus = it })
                }
            }
        }
        if (onReply != null) {
            ThemeButton(stringResource(R.string.comment_reply), { onReply(lastReadingFocus) },
                Modifier.alpha(if (focused) 1f else 0f).focusRequester(replyFocus)
                    .focusProperties { canFocus = allowReplyFocus }
                    .onFocusChanged {
                        if (replyFocused && !it.isFocused) allowReplyFocus = false
                        replyFocused = it.isFocused
                    }.then(if (focused) Modifier else Modifier.clearAndSetSemantics {}),
                icon = R.drawable.ic_comment_reply, actionEnabled = focused, showOutline = false)
        }
    }
}

@Composable
private fun Modifier.commentThreadDivider(): Modifier {
    val d = SeasonvarTheme.dimensions
    val colors = MaterialTheme.colorScheme
    val lineWidth = SeasonvarTheme.surfaceOutlineWidth
    return drawWithCache {
        val stroke = lineWidth.toPx()
        val dashStart = d.spacingSmall.toPx()
        val dashEnd = d.spacingLarge.toPx()
        val lineStart = d.spacingExtraLarge.toPx()
        val lineEnd = size.width - dashStart
        val fade = Brush.horizontalGradient(
            0f to colors.onBackground.copy(alpha = 0.25f),
            1f to colors.onBackground.copy(alpha = 0f),
            startX = lineStart, endX = lineEnd,
        )
        onDrawBehind {
            drawLine(colors.primary.copy(alpha = 0.45f), Offset(dashStart, stroke), Offset(dashEnd, stroke),
                strokeWidth = stroke * 2, cap = StrokeCap.Round)
            drawLine(fade, Offset(lineStart, stroke), Offset(lineEnd, stroke), strokeWidth = stroke)
        }
    }
}

@Composable
private fun CommentBody(part: CommentPart, modifier: Modifier = Modifier,
    first: FocusRequester? = null, onReadingFocus: (FocusRequester) -> Unit) {
    when (part) {
        is CommentPart.Text -> Text(part.value, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground)
        is CommentPart.Spoiler -> {
            var revealed by rememberSaveable { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val spoiler = remember { BringIntoViewRequester() }
            val localFocus = remember { FocusRequester() }
            val focus = first ?: localFocus
            Column(Modifier.bringIntoViewRequester(spoiler),
                verticalArrangement = Arrangement.spacedBy(SeasonvarTheme.dimensions.spacingSmall)) {
                ThemeButton(stringResource(if (revealed) R.string.comment_hide_spoiler else R.string.comment_show_spoiler), {
                    revealed = !revealed
                    if (revealed) scope.launch {
                        repeat(2) { withFrameNanos { } }
                        if (revealed) spoiler.bringIntoView()
                    }
                }, modifier.focusRequester(focus).onFocusChanged { if (it.isFocused) onReadingFocus(focus) })
                if (revealed) part.parts.forEach { CommentBody(it, onReadingFocus = onReadingFocus) }
            }
        }
    }
}
