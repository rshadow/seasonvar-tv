package ru.seasonvar.tv.ui.account

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import ru.seasonvar.tv.R
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.navigation.backNavigationKeys
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@Composable
internal fun MarkRemovalDialog(state: MarkRemovalUiState, onDismiss: () -> Unit, onAsk: () -> Unit,
    onConfirm: () -> Unit, onOpen: () -> Unit, onInteraction: () -> Unit) {
    val context = LocalContext.current
    val error = stringResource(R.string.mark_remove_error)
    LaunchedEffect(state.failure, state.visible) {
        if (!state.visible && state.failure != null) Toast.makeText(context, error, Toast.LENGTH_LONG).show()
    }
    val entry = state.entry ?: return
    if (!state.visible) return
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val first = remember { FocusRequester() }
    val second = remember { FocusRequester() }
    val openedAt = remember { android.os.SystemClock.uptimeMillis() }
    val confirm = state.stage == RemovalStage.CONFIRM
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onDismiss)
        Column(Modifier.width(d.dialogWidth)
            .onPreviewKeyEvent { event ->
                event.nativeKeyEvent.keyCode in setOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) &&
                    event.nativeKeyEvent.downTime < openedAt
            }
            .backNavigationKeys(onDismiss, onInteraction)
            .background(c.background, RoundedCornerShape(d.cardCornerRadius)).padding(d.spacingLarge),
            verticalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
            Text(stringResource(if (confirm) R.string.mark_remove_question else R.string.mark_actions_title),
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
                ThemeButton(stringResource(R.string.mark_open_series), onOpen, Modifier.fillMaxWidth().height(d.headerButtonSize)
                    .focusRequester(first).focusProperties { up = FocusRequester.Cancel; down = second })
                ThemeButton(stringResource(R.string.mark_remove_from_list), onAsk, Modifier.fillMaxWidth().height(d.headerButtonSize)
                    .focusRequester(second).focusProperties { up = first; down = FocusRequester.Cancel })
            }
        }
        LaunchedEffect(state.stage) { repeat(2) { withFrameNanos { } }; first.requestFocus() }
    }
}
