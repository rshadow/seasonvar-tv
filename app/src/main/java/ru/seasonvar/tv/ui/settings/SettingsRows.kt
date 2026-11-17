package ru.seasonvar.tv.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.tv.material3.Checkbox
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import ru.seasonvar.tv.ui.SettingsChoiceDialog
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@Composable
internal fun SettingsToggle(@StringRes title: Int, @StringRes description: Int, value: Boolean,
    onChange: (Boolean) -> Unit, focus: FocusRequester, menu: FocusRequester,
    previous: FocusRequester, next: FocusRequester) {
    SettingsRow(title, description, focus, menu, previous, next,
        Modifier.toggleable(value, role = Role.Checkbox, onValueChange = onChange)) {
        Checkbox(checked = value, onCheckedChange = null)
    }
}

@Composable
internal fun <T> SettingsChoice(@StringRes title: Int, @StringRes description: Int, @StringRes dialogTitle: Int = title,
    options: List<T>, selected: T, label: (T) -> Int, onChange: (T) -> Unit,
    focus: FocusRequester, menu: FocusRequester, previous: FocusRequester, next: FocusRequester,
    onBack: () -> Unit, onClose: () -> Unit, onInteraction: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val c = MaterialTheme.colorScheme
    SettingsRow(title, description, focus, menu, previous, next,
        Modifier.clickable(role = Role.Button) { onInteraction(); open = true }) { focused ->
        Text(stringResource(label(selected)), style = MaterialTheme.typography.bodyLarge,
            color = SeasonvarTheme.textColor({ focused }, c.primary), fontWeight = SeasonvarTheme.textWeight { focused })
    }
    if (open) SettingsChoiceDialog(dialogTitle, options, selected, label,
        onSelect = {
            onChange(it); open = false
            scope.launch { repeat(2) { withFrameNanos { } }; focus.requestFocus() }
        }, onClose = { open = false; onClose() }, onBack = { open = false; onBack() }, onInteraction = onInteraction)
}

/** Одна точка фокуса на строку; trailing содержит только значение или декоративную галочку. */
@Composable
private fun SettingsRow(@StringRes title: Int, @StringRes description: Int,
    focus: FocusRequester, menu: FocusRequester, previous: FocusRequester, next: FocusRequester,
    action: Modifier, trailing: @Composable (Boolean) -> Unit) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().focusRequester(focus)
        .focusProperties { left = menu; up = previous; down = next }
        .onFocusChanged { focused = it.isFocused }
        .border(d.focusBorderWidth, if (focused) c.primary else Color.Transparent, RoundedCornerShape(d.cardCornerRadius))
        .then(action).padding(d.spacingMedium), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge,
                color = SeasonvarTheme.textColor({ focused }, c.onBackground), fontWeight = SeasonvarTheme.textWeight { focused })
            Spacer(Modifier.height(d.spacingSmall))
            Text(stringResource(description), style = MaterialTheme.typography.bodyLarge,
                color = SeasonvarTheme.textColor({ focused }, c.onSurfaceVariant), fontWeight = SeasonvarTheme.textWeight { focused })
        }
        Spacer(Modifier.width(d.spacingMedium))
        trailing(focused)
    }
}
