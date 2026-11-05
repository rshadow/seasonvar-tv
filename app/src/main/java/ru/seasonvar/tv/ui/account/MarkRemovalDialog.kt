package ru.seasonvar.tv.ui.account

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
    onConfirm: () -> Unit, onInteraction: () -> Unit,
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
    val delete = remember { FocusRequester() }
    val choices = remember { AccountListStatus.entries.map { FocusRequester() } }
    val openedAt = remember { android.os.SystemClock.uptimeMillis() }
    val confirm = state.stage == RemovalStage.CONFIRM
    val canDelete = state.stage == RemovalStage.ACTIONS && entry.accountListMark != null
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onDismiss)
        Column(Modifier.width(d.dialogWidth)
            .then(if (!confirm) Modifier.heightIn(max = d.dialogHeight) else Modifier)
            .onPreviewKeyEvent { event ->
                event.nativeKeyEvent.keyCode in setOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) &&
                    event.nativeKeyEvent.downTime < openedAt
            }
            .backNavigationKeys(onDismiss, onInteraction)
            .background(c.background, RoundedCornerShape(d.cardCornerRadius)).padding(d.spacingLarge),
            verticalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
            Text(stringResource(when { confirm -> R.string.mark_remove_question; state.stage == RemovalStage.STATUS -> R.string.mark_list_status_title; else -> R.string.mark_actions_title }),
                color = c.onBackground, style = MaterialTheme.typography.headlineSmall)
            Text(listOfNotNull(entry.title, entry.season?.let { stringResource(R.string.season_number, it) }).joinToString(" · "),
                color = c.onBackground, style = MaterialTheme.typography.titleMedium)
            if (confirm) {
                Text(stringResource(R.string.mark_remove_explanation), color = c.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
                if (state.failure != null) Text(error, color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                    ThemeButton(stringResource(R.string.mark_remove_cancel), onDismiss,
                        Modifier.height(d.headerButtonSize).focusRequester(first).focusProperties { left = FocusRequester.Cancel; right = second })
                    ThemeButton(stringResource(if (state.failure != null) R.string.retry else R.string.mark_remove_action), onConfirm,
                        Modifier.height(d.headerButtonSize).focusRequester(second).focusProperties { left = first; right = FocusRequester.Cancel },
                        loading = state.busy, loadingLabel = stringResource(R.string.mark_removing))
                }
            } else {
                if (state.loadingStatus || state.busy) Text(stringResource(if (state.busy) R.string.mark_list_saving else R.string.mark_list_loading),
                    color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                if (state.failure != null) Text(error, color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                    AccountListStatus.entries.forEachIndexed { index, option ->
                        val label = stringResource(statusLabel(option))
                        ThemeButton((if (state.status == option) "✓  " else "") + label, { onSelectStatus(option) },
                            Modifier.fillMaxWidth().height(d.headerButtonSize).focusRequester(choices[index]).focusProperties {
                                up = choices.getOrNull(index - 1) ?: FocusRequester.Cancel
                                down = choices.getOrNull(index + 1) ?: if (canDelete) delete else FocusRequester.Cancel
                            }, active = state.status == option, enabled = !state.loadingStatus && !state.busy)
                    }
                    if (canDelete) ThemeButton(stringResource(R.string.mark_remove_from_list), onAsk,
                        Modifier.fillMaxWidth().height(d.headerButtonSize).focusRequester(delete).focusProperties {
                            up = choices.last(); down = FocusRequester.Cancel
                        }, enabled = !state.loadingStatus && !state.busy)
                }
            }
        }
        LaunchedEffect(state.stage, state.loadingStatus, state.busy) {
            if (!state.loadingStatus && !state.busy) {
                repeat(2) { withFrameNanos { } }
                if (confirm) first.requestFocus() else choices[state.status?.ordinal ?: 0].requestFocus()
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
