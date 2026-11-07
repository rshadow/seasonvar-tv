package ru.seasonvar.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Checkbox
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import ru.seasonvar.tv.R
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.menuGroupTitle
import ru.seasonvar.tv.ui.sectionTitle
import ru.seasonvar.tv.ui.navigation.Section
import ru.seasonvar.tv.ui.navigation.backNavigationKeys
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@Composable
internal fun MenuVisibilitySetting(
    hiddenSections: Set<Section>,
    onVisibilityChange: (Section, Boolean) -> Unit,
    focus: FocusRequester,
    menuFocus: FocusRequester,
    previousFocus: FocusRequester,
    nextFocus: FocusRequester,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onInteraction: () -> Unit,
) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().focusRequester(focus)
        .focusProperties { left = menuFocus; up = previousFocus; down = nextFocus }
        .onFocusChanged { focused = it.isFocused }
        .border(d.focusBorderWidth, if (focused) c.primary else Color.Transparent, RoundedCornerShape(d.cardCornerRadius))
        .clickable(role = Role.Button) { onInteraction(); open = true }
        .padding(d.spacingMedium), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.menu_visibility_title), style = MaterialTheme.typography.titleLarge, color = c.onBackground)
            Spacer(Modifier.height(d.spacingSmall))
            Text(stringResource(R.string.menu_visibility_description), style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
        }
        Spacer(Modifier.width(d.spacingMedium))
        Text(stringResource(R.string.menu_visibility_count, Section.startPages(true, hiddenSections).size, Section.startPages(true).size),
            style = MaterialTheme.typography.bodyLarge, color = c.primary)
    }
    if (open) {
        MenuVisibilityDialog(hiddenSections, onVisibilityChange,
            onDone = {
                open = false
                scope.launch { repeat(2) { withFrameNanos { } }; focus.requestFocus() }
            },
            onBack = { open = false; onBack() }, onClose = { open = false; onClose() }, onInteraction = onInteraction)
    }
}

@Composable
private fun MenuVisibilityDialog(
    hiddenSections: Set<Section>,
    onVisibilityChange: (Section, Boolean) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onInteraction: () -> Unit,
) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val groups = remember { Section.menuGroups(true) }
    val options = remember { Section.startPages(true).filter { it.canHide } }
    val optionFocus = remember { options.associateWith { FocusRequester() } }
    val doneFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onBack)
        Column(Modifier.width(d.dialogWidth).heightIn(max = d.dialogHeight)
            .backNavigationKeys(onBack, onInteraction)
            .background(c.background, RoundedCornerShape(d.cardCornerRadius))
            .padding(d.spacingLarge), verticalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
            Text(stringResource(R.string.menu_visibility_title), style = MaterialTheme.typography.headlineMedium, color = c.onBackground)
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                groups.forEach { (group, sections) ->
                    Text(stringResource(menuGroupTitle(group)), modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                    sections.forEach { section ->
                        key(section) {
                            var focused by remember { mutableStateOf(false) }
                            val index = options.indexOf(section)
                            val visible = !section.canHide || section !in hiddenSections
                            Row(Modifier.fillMaxWidth().heightIn(min = d.navigationButtonHeight)
                                .then(if (section.canHide) Modifier.focusRequester(optionFocus.getValue(section)) else Modifier)
                                .focusProperties {
                                    up = options.getOrNull(index - 1)?.let(optionFocus::getValue) ?: FocusRequester.Cancel
                                    down = options.getOrNull(index + 1)?.let(optionFocus::getValue) ?: doneFocus
                                }
                                .onFocusChanged { focused = it.isFocused }
                                .border(d.focusBorderWidth, if (focused) c.primary else Color.Transparent, RoundedCornerShape(d.cardCornerRadius))
                                .toggleable(visible, enabled = section.canHide, role = Role.Checkbox,
                                    onValueChange = { onInteraction(); onVisibilityChange(section, it) })
                                .padding(d.spacingMedium), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(sectionTitle(section)), style = MaterialTheme.typography.titleLarge, color = c.onBackground)
                                    if (!section.canHide) Text(stringResource(R.string.menu_visibility_settings_required),
                                        style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                                }
                                Spacer(Modifier.width(d.spacingMedium))
                                Checkbox(checked = visible, enabled = section.canHide, onCheckedChange = null)
                            }
                        }
                    }
                }
            }
            ThemeButton(stringResource(R.string.menu_visibility_done), onClick = { onInteraction(); onDone() },
                modifier = Modifier.fillMaxWidth().focusRequester(doneFocus)
                    .focusProperties { up = optionFocus.getValue(options.last()); down = FocusRequester.Cancel })
        }
    }
    LaunchedEffect(Unit) { optionFocus.getValue(options.first()).requestFocus() }
}
