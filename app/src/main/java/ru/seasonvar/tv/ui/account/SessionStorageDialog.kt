package ru.seasonvar.tv.ui.account

import android.os.SystemClock
import android.view.KeyEvent
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ru.seasonvar.tv.R
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@Composable
internal fun SessionStorageDialog(onChoice: (Boolean) -> Unit) {
    val dimensions = SeasonvarTheme.dimensions
    val colors = MaterialTheme.colorScheme
    val withoutSaving = remember { FocusRequester() }
    val rememberLogin = remember { FocusRequester() }
    val openedAt = remember { SystemClock.uptimeMillis() }
    Dialog(onDismissRequest = { onChoice(false) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(dimensions.dialogWidth)
            .onPreviewKeyEvent {
                when (it.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                        it.nativeKeyEvent.downTime < openedAt
                    else -> false
                }
            }
            .background(colors.background, RoundedCornerShape(dimensions.cardCornerRadius))
            .padding(dimensions.spacingLarge),
            verticalArrangement = Arrangement.spacedBy(dimensions.spacingMedium)) {
            Text(stringResource(R.string.account_storage_choice_title), color = colors.onBackground,
                style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.account_storage_choice_description), color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(dimensions.spacingSmall)) {
                ThemeButton(stringResource(R.string.account_storage_do_not_remember), { onChoice(false) },
                    Modifier.weight(1f).height(dimensions.navigationButtonHeight).focusRequester(withoutSaving)
                        .focusProperties { left = FocusRequester.Cancel; right = rememberLogin })
                ThemeButton(stringResource(R.string.account_storage_remember), { onChoice(true) },
                    Modifier.weight(1f).height(dimensions.navigationButtonHeight).focusRequester(rememberLogin)
                        .focusProperties { left = withoutSaving; right = FocusRequester.Cancel })
            }
        }
        LaunchedEffect(Unit) {
            repeat(2) { withFrameNanos { } }
            withoutSaving.requestFocus()
        }
    }
}
