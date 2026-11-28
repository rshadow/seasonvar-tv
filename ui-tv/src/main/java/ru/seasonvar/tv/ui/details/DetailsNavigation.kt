package ru.seasonvar.tv.ui.details

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.seasonvar.tv.ui.settings.InterfaceTheme

/** Объекты фокуса и незавершённые переходы живут столько же, сколько открытая карточка. */
@Stable
internal class DetailsNavigation(private val scope: CoroutineScope, val scroll: LazyListState) {
    val selectedEpisodeFocus = FocusRequester()
    val play = FocusRequester()
    val actionsMenu = FocusRequester()
    val reviews = FocusRequester()
    val season = FocusRequester()
    val translation = FocusRequester()
    val allEpisodes = FocusRequester()
    val actions = BringIntoViewRequester()
    val allEpisodesView = BringIntoViewRequester()
    var actorReturnFocus by mutableStateOf(play)
    var restoreActions by mutableStateOf(false)
    var descriptionAnchor by mutableStateOf<Int?>(null)
    var revealActionsAfterLoad by mutableStateOf(true)
    var dialog by mutableStateOf<DetailsDialog?>(null)
    var restoreFocus by mutableStateOf<FocusRequester?>(null)

    fun dismiss(onInteraction: () -> Unit) { onInteraction(); dialog = null }
    fun show(which: DetailsDialog, focus: FocusRequester, onInteraction: () -> Unit) {
        onInteraction(); restoreFocus = focus; dialog = which
    }

    /** actorFocus содержит по одному объекту на каждого видимого актёра; индекс читается после прокрутки. */
    fun actionScrolling(origin: FocusRequester, actorListState: LazyListState, actorFocus: List<FocusRequester>,
        actorIndex: MutableIntState, step: Float, episodesEmpty: Boolean, commentsReady: Boolean,
        onInteraction: () -> Unit) = Modifier.onPreviewKeyEvent {
        if (it.type != KeyEventType.KeyDown) false else when {
            it.key == Key.DirectionUp -> {
                onInteraction()
                if (actorFocus.isNotEmpty()) {
                    actorReturnFocus = origin
                    scope.launch {
                        scroll.scrollToItem(0)
                        val index = actorIndex.intValue.coerceIn(actorFocus.indices)
                        if (actorListState.layoutInfo.visibleItemsInfo.none { item -> item.index == index }) {
                            actorListState.scrollToItem(index)
                        }
                        withFrameNanos { }
                        actorFocus[index].requestFocus()
                    }
                } else scope.launch { scroll.scrollBy(-step) }
                true
            }
            it.key == Key.DirectionDown && episodesEmpty -> {
                readDescription(true, step, commentsReady)
            }
            else -> false
        }
    }

    fun pageScrolling(step: Float): Modifier = Modifier.onPreviewKeyEvent {
        if (it.type != KeyEventType.KeyDown) false else when (it.key) {
            Key.PageDown -> { scope.launch { scroll.scrollBy(step) }; true }
            Key.PageUp -> { scope.launch { scroll.scrollBy(-step) }; true }
            else -> false
        }
    }

    /** true означает обработанную стрелку; возврат вверх останавливается на исходной позиции описания. */
    fun readDescription(forward: Boolean, step: Float, comments: Boolean): Boolean {
        val offset = scroll.firstVisibleItemScrollOffset
        return if (forward) {
            if (comments && scroll.layoutInfo.visibleItemsInfo.any { it.index == 1 }) return false
            if (descriptionAnchor == null) descriptionAnchor = offset
            scope.launch { scroll.scrollBy(step) }
            true
        } else {
            val anchor = descriptionAnchor
            if (anchor != null && offset > anchor) {
                val distance = minOf(step, (offset - anchor).toFloat())
                scope.launch { scroll.scrollBy(-distance) }
                true
            } else { descriptionAnchor = null; false }
        }
    }

    fun returnFromComments(target: FocusRequester) {
        scope.launch {
            scroll.scrollToItem(0, descriptionAnchor ?: 0)
            repeat(2) { withFrameNanos { } }
            target.requestFocus()
        }
    }
}

@Composable
internal fun rememberDetailsNavigation(): DetailsNavigation {
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    return remember(scope, scroll) { DetailsNavigation(scope, scroll) }
}

/** Два кадра дают целевым узлам время появиться и подключить объекты фокуса. */
@Composable
internal fun DetailsFocusEffects(navigation: DetailsNavigation, entryId: String, translationId: String?,
    preparing: Boolean, appearance: InterfaceTheme, removalDialogOpen: Boolean, canOpenActions: Boolean) = with(navigation) {
    LaunchedEffect(Unit) { play.requestFocus() }
    LaunchedEffect(entryId, preparing) {
        if (!preparing) {
            repeat(2) { withFrameNanos { } }
            if (revealActionsAfterLoad) {
                actions.bringIntoView()
                play.requestFocus()
                revealActionsAfterLoad = false
                DetailsPerformance.ready(appearance)
            }
            descriptionAnchor = null
        }
    }
    LaunchedEffect(entryId, translationId) { descriptionAnchor = null }
    LaunchedEffect(dialog) {
        if (dialog == null) restoreFocus?.let { focus ->
            repeat(2) { withFrameNanos { } }
            if (focus == allEpisodes) {
                descriptionAnchor = null
                allEpisodesView.bringIntoView()
            }
            focus.requestFocus()
            restoreFocus = null
        }
    }
    LaunchedEffect(removalDialogOpen) {
        if (!removalDialogOpen && restoreActions) {
            repeat(2) { withFrameNanos { } }
            if (canOpenActions) actionsMenu.requestFocus() else play.requestFocus()
            restoreActions = false
        }
    }
}
