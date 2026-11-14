package ru.seasonvar.tv.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import ru.seasonvar.tv.R
import ru.seasonvar.tv.ui.SettingsChoiceDialog
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@Composable
internal fun DetailsAppearanceSetting(value: DetailsAppearance, onChange: (DetailsAppearance) -> Unit,
    focus: FocusRequester, menu: FocusRequester, previous: FocusRequester, next: FocusRequester,
    onBack: () -> Unit, onClose: () -> Unit, onInteraction: () -> Unit) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().focusRequester(focus).focusProperties { left = menu; up = previous; down = next }
        .onFocusChanged { focused = it.isFocused }
        .border(d.focusBorderWidth, if (focused) c.primary else Color.Transparent, RoundedCornerShape(d.cardCornerRadius))
        .clickable(role = Role.Button) { onInteraction(); open = true }.padding(d.spacingMedium),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.details_appearance_title), color = c.onBackground, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(d.spacingSmall))
            Text(stringResource(R.string.details_appearance_description), color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        }
        Text(stringResource(appearanceLabel(value)), color = c.primary, style = MaterialTheme.typography.bodyLarge)
    }
    if (open) SettingsChoiceDialog(R.string.details_appearance_title, DetailsAppearance.entries, value, ::appearanceLabel,
        onSelect = {
            onChange(it); open = false
            scope.launch { repeat(2) { withFrameNanos { } }; focus.requestFocus() }
        }, onClose = { open = false; onClose() }, onBack = { open = false; onBack() }, onInteraction = onInteraction)
}

private fun appearanceLabel(value: DetailsAppearance): Int = when (value) {
    DetailsAppearance.PLAIN -> R.string.details_appearance_plain
    DetailsAppearance.BLURRED_POSTER -> R.string.details_appearance_blurred
}
