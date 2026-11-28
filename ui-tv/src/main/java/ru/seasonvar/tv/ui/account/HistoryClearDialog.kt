package ru.seasonvar.tv.ui.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ru.seasonvar.tv.R
import ru.seasonvar.tv.ui.ShortMessageEffect
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.navigation.backNavigationKeys
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@Composable
internal fun HistoryClearDialog(controller: HistoryClearController, onInteraction: () -> Unit = {}) {
    val state by controller.state.collectAsStateWithLifecycle()
    ShortMessageEffect(if (state.failure != null) stringResource(R.string.history_clear_error) else null)
    if (!state.visible) return
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val cancel = remember { FocusRequester() }
    val confirm = remember { FocusRequester() }
    val openedAt = remember { android.os.SystemClock.uptimeMillis() }
    val dismiss = { onInteraction(); controller.dismiss() }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = dismiss)
        Column(Modifier.width(d.dialogWidth)
            .onPreviewKeyEvent { event ->
                event.nativeKeyEvent.keyCode in setOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) &&
                    event.nativeKeyEvent.downTime < openedAt
            }
            .backNavigationKeys(dismiss, onInteraction)
            .background(c.background, RoundedCornerShape(d.cardCornerRadius)).padding(d.spacingLarge),
            verticalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
            Text(stringResource(R.string.history_clear_question), color = c.onBackground,
                style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.history_clear_explanation), color = c.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                ThemeButton(stringResource(if (state.busy) R.string.history_clear_close else R.string.history_clear_cancel),
                    dismiss, Modifier.weight(1f).height(d.navigationButtonHeight).focusRequester(cancel)
                        .focusProperties { left = FocusRequester.Cancel; right = confirm; up = FocusRequester.Cancel; down = FocusRequester.Cancel })
                ThemeButton(stringResource(if (state.busy) R.string.history_clearing else R.string.history_clear_action),
                    controller::confirm, Modifier.weight(1f).height(d.navigationButtonHeight).focusRequester(confirm)
                        .focusProperties { left = cancel; right = FocusRequester.Cancel; up = FocusRequester.Cancel; down = FocusRequester.Cancel },
                    actionEnabled = !state.busy)
            }
        }
        LaunchedEffect(Unit) {
            onInteraction()
            repeat(2) { withFrameNanos { } }
            cancel.requestFocus()
        }
    }
}
