package ru.seasonvar.tv.ui.account

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ru.seasonvar.tv.data.account.AccountListStatus
import ru.seasonvar.tv.R
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.navigation.backNavigationKeys
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@Composable
internal fun MarkRemovalDialog(state: MarkRemovalUiState, onDismiss: () -> Unit, onAsk: () -> Unit,
    onConfirm: () -> Unit, onInteraction: () -> Unit, onRefresh: () -> Unit = {},
    onSelectStatus: (AccountListStatus) -> Unit = {}) {
    val context = LocalContext.current
    val error = stringResource(if (state.stage != RemovalStage.CONFIRM) R.string.mark_list_error else R.string.mark_remove_error)
    LaunchedEffect(state.failure, state.visible) {
        if (!state.visible && state.failure != null) Toast.makeText(context, error, Toast.LENGTH_LONG).show()
    }
    val entry = state.entry ?: return
    if (!state.visible) return
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val first = remember { FocusRequester() }
    val second = remember { FocusRequester() }
    val refresh = remember { FocusRequester() }
    val delete = remember { FocusRequester() }
    val choices = remember { AccountListStatus.entries.map { FocusRequester() } }
    var lastChoice by remember { mutableStateOf<FocusRequester?>(null) }
    var initialFocusPlaced by remember(entry.key) { mutableStateOf(false) }
    val openedAt = remember { android.os.SystemClock.uptimeMillis() }
    val confirm = state.stage == RemovalStage.CONFIRM
    val showDelete = state.stage == RemovalStage.ACTIONS
    val pending = state.loadingStatus || state.busy
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onDismiss)
        Column(Modifier.width(d.dialogWidth)
            .then(if (!confirm) Modifier.heightIn(max = d.dialogHeight) else Modifier)
            .onPreviewKeyEvent { event ->
                val openingPress = event.nativeKeyEvent.keyCode in setOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) &&
                    event.nativeKeyEvent.downTime < openedAt
                if (!openingPress && !confirm && event.type == KeyEventType.KeyDown && event.key !in setOf(Key.Back, Key.Escape)) {
                    initialFocusPlaced = true
                }
                openingPress
            }
            .pointerInput(entry.key) {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }) initialFocusPlaced = true
                    }
                }
            }
            .backNavigationKeys(onDismiss, onInteraction)
            .background(c.background, RoundedCornerShape(d.cardCornerRadius)).padding(d.spacingLarge),
            verticalArrangement = Arrangement.spacedBy(if (confirm) d.spacingMedium else d.spacingSmall)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                Text(stringResource(when { confirm -> R.string.mark_remove_question; state.stage == RemovalStage.STATUS -> R.string.mark_list_status_title; else -> R.string.mark_actions_title }),
                    modifier = Modifier.weight(1f), color = c.onBackground, style = MaterialTheme.typography.headlineSmall)
                if (!confirm) ThemeButton(stringResource(R.string.mark_list_refresh), onRefresh,
                    Modifier.focusRequester(refresh).focusProperties {
                        val target = lastChoice ?: choices[state.status?.ordinal ?: 0]
                        left = target; down = target; up = FocusRequester.Cancel; right = FocusRequester.Cancel
                    }, icon = R.drawable.ic_reload, loading = pending, actionEnabled = !pending,
                    loadingLabel = stringResource(if (state.busy) R.string.mark_list_saving else R.string.mark_list_loading))
            }
            Text(listOfNotNull(entry.title, entry.season?.let { stringResource(R.string.season_number, it) }).joinToString(" · "),
                color = c.onBackground, style = MaterialTheme.typography.titleMedium)
            if (confirm) {
                Text(stringResource(R.string.mark_remove_explanation), color = c.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
                Text(if (state.failure != null) error else "", minLines = 2, maxLines = 2,
                    color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                    ThemeButton(stringResource(R.string.mark_remove_cancel), onDismiss,
                        Modifier.height(d.headerButtonSize).focusRequester(first).focusProperties { left = FocusRequester.Cancel; right = second })
                    ThemeButton(stringResource(R.string.mark_remove_action), onConfirm,
                        Modifier.height(d.headerButtonSize).focusRequester(second).focusProperties { left = first; right = FocusRequester.Cancel },
                        loading = state.busy, actionEnabled = !state.busy, loadingLabel = stringResource(R.string.mark_removing))
                }
            } else {
                Text(if (state.failure != null) stringResource(R.string.mark_list_request_error) else "",
                    Modifier.fillMaxWidth(), minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = c.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                    AccountListStatus.entries.forEachIndexed { index, option ->
                        ThemeButton(stringResource(statusLabel(option)), { onSelectStatus(option) },
                            Modifier.fillMaxWidth().height(d.headerButtonSize).focusRequester(choices[index])
                                .onFocusChanged { if (it.isFocused) { lastChoice = choices[index] } }
                                .focusProperties {
                                    up = choices.getOrNull(index - 1) ?: refresh
                                    down = choices.getOrNull(index + 1) ?: if (showDelete) delete else FocusRequester.Cancel
                                    left = FocusRequester.Cancel; right = refresh
                                }, active = state.status == option, actionEnabled = !pending, checked = state.status == option)
                    }
                    if (showDelete) ThemeButton(stringResource(R.string.mark_remove_from_list), onAsk,
                        Modifier.fillMaxWidth().height(d.headerButtonSize).focusRequester(delete)
                            .onFocusChanged { if (it.isFocused) { lastChoice = delete } }
                            .focusProperties {
                                up = choices.last(); down = FocusRequester.Cancel; left = FocusRequester.Cancel; right = refresh
                            }, actionEnabled = !pending && entry.accountListMark != null, reserveCheckSpace = true)
                }
            }
        }
        LaunchedEffect(state.stage, state.loadingStatus) {
            if (confirm) {
                repeat(2) { withFrameNanos { } }
                first.requestFocus()
            } else if (!initialFocusPlaced) {
                repeat(2) { withFrameNanos { } }
                if (!initialFocusPlaced) {
                    if (state.loadingStatus) refresh.requestFocus()
                    else {
                        initialFocusPlaced = true
                        choices[state.status?.ordinal ?: 0].requestFocus()
                    }
                }
            }
        }
    }
}

internal fun statusLabel(status: AccountListStatus): Int = when (status) {
    AccountListStatus.WANT_TO_WATCH -> R.string.mark_list_want
    AccountListStatus.WATCHING -> R.string.mark_list_watching
    AccountListStatus.WATCHED -> R.string.mark_list_watched
    AccountListStatus.NOT_WATCHING -> R.string.mark_list_not_watching
}
