package ru.seasonvar.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import ru.seasonvar.tv.R
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.menuGroupTitle
import ru.seasonvar.tv.ui.sectionTitle
import ru.seasonvar.tv.ui.navigation.MenuGroup
import ru.seasonvar.tv.ui.navigation.MenuOrder
import ru.seasonvar.tv.ui.navigation.Section
import ru.seasonvar.tv.ui.navigation.backNavigationKeys
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@Composable
internal fun MenuVisibilitySetting(
    hiddenSections: Set<Section>,
    onVisibilityChange: (Section, Boolean) -> Unit,
    order: MenuOrder,
    onOrderChange: (MenuOrder) -> Unit,
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
            Text(stringResource(R.string.menu_visibility_title), style = MaterialTheme.typography.titleLarge, color = SeasonvarTheme.textColor({ focused }, c.onBackground), fontWeight = SeasonvarTheme.textWeight { focused })
            Spacer(Modifier.height(d.spacingSmall))
            Text(stringResource(R.string.menu_visibility_description), style = MaterialTheme.typography.bodyLarge, color = SeasonvarTheme.textColor({ focused }, c.onSurfaceVariant), fontWeight = SeasonvarTheme.textWeight { focused })
        }
        Spacer(Modifier.width(d.spacingMedium))
        Text(stringResource(R.string.menu_visibility_count, Section.startPages(true, hiddenSections).size, Section.startPages(true).size),
            style = MaterialTheme.typography.bodyLarge, color = SeasonvarTheme.textColor({ focused }, c.primary), fontWeight = SeasonvarTheme.textWeight { focused })
    }
    if (open) {
        MenuVisibilityDialog(hiddenSections, onVisibilityChange, order, onOrderChange,
            onDone = {
                open = false
                scope.launch { repeat(2) { withFrameNanos { } }; focus.requestFocus() }
            },
            onBack = { open = false; onBack() }, onClose = { open = false; onClose() }, onInteraction = onInteraction)
    }
}

private enum class MenuAction { UP, DOWN, VISIBILITY }

private fun MenuAction.label(visible: Boolean) = when (this) {
    MenuAction.UP -> R.string.menu_order_move_up
    MenuAction.DOWN -> R.string.menu_order_move_down
    MenuAction.VISIBILITY -> if (visible) R.string.menu_order_hide else R.string.menu_order_show
}

private data class MenuRow(val group: MenuGroup, val section: Section?, val index: Int, val count: Int) {
    val id get() = section?.storageId ?: group.storageId
    val actions get() = MenuAction.entries.filter { action ->
        when (action) {
            MenuAction.UP -> group != MenuGroup.SYSTEM && index > 0
            MenuAction.DOWN -> group != MenuGroup.SYSTEM && index < count - 1
            MenuAction.VISIBILITY -> section?.canHide == true
        }
    }
}

@Composable
private fun MenuVisibilityDialog(
    hiddenSections: Set<Section>,
    onVisibilityChange: (Section, Boolean) -> Unit,
    order: MenuOrder,
    onOrderChange: (MenuOrder) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onInteraction: () -> Unit,
) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val rows = remember(order) {
        val groups = order.groups()
        groups.flatMapIndexed { index, (group, sections) ->
            listOf(MenuRow(group, null, index, groups.size - 1)) +
                sections.mapIndexed { item, section -> MenuRow(group, section, item, sections.size) }
        }
    }
    val doneFocus = remember { FocusRequester() }
    val buttons = remember {
        Section.defaultMenuGroups.flatMap { (group, sections) -> listOf(group.storageId) + sections.map(Section::storageId) }
            .flatMap { id -> MenuAction.entries.map { id to it } }.associateWith { FocusRequester() }
    }
    val focusableRows = rows.filter { it.actions.isNotEmpty() }
    var target by remember { mutableStateOf<Pair<String, MenuAction>?>(null) }
    var focusedAction by remember { mutableStateOf<Pair<String, MenuAction>?>(null) }
    val focusedRow = rows.firstOrNull { it.id == focusedAction?.first }
    val explanation = if (focusedRow == null) "" else {
        val title = focusedRow.section?.let(::sectionTitle) ?: menuGroupTitle(focusedRow.group)
        stringResource(checkNotNull(focusedAction).second.label(focusedRow.section !in hiddenSections), stringResource(title))
    }

    fun focusRow(row: MenuRow, preferred: MenuAction): FocusRequester {
        val action = preferred.takeIf { it in row.actions } ?: row.actions.first()
        return buttons.getValue(row.id to action)
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onBack)
        Column(Modifier.width(d.dialogWidth).heightIn(max = d.dialogHeight)
            .backNavigationKeys(onBack, onInteraction)
            .background(c.background, RoundedCornerShape(d.cardCornerRadius))
            .padding(d.spacingLarge), verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
            Text(stringResource(R.string.menu_visibility_title), style = MaterialTheme.typography.headlineMedium, color = c.onBackground)
            Text(stringResource(R.string.menu_order_hint), color = c.onSurfaceVariant)
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                for (row in rows) key(row.id) {
                    val section = row.section
                    val isGroup = section == null
                    val label = stringResource(if (section == null) menuGroupTitle(row.group) else sectionTitle(section))
                    val visible = section !in hiddenSections
                    val rowIndex = focusableRows.indexOf(row)
                    Row(Modifier.fillMaxWidth().padding(start = if (isGroup) d.spacingSmall else d.spacingLarge)
                        .heightIn(min = d.headerButtonSize),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                        Column(Modifier.weight(1f)) {
                            Text(label, style = if (isGroup) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                                color = if (visible) c.onBackground else c.onSurfaceVariant,
                                modifier = if (isGroup) Modifier.semantics { heading() } else Modifier)
                            if (section == Section.SETTINGS) Text(stringResource(R.string.menu_visibility_settings_required),
                                color = c.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        for (action in MenuAction.entries) {
                            val toggle = action == MenuAction.VISIBILITY
                            if (toggle && section?.canHide != true || !toggle && row.group == MenuGroup.SYSTEM) {
                                Spacer(Modifier.size(d.headerButtonSize))
                            } else {
                                val actionLabel = stringResource(action.label(visible), label)
                                val position = row.actions.indexOf(action)
                                ThemeButton(actionLabel, onClick = {
                                    onInteraction()
                                    when (action) {
                                        MenuAction.UP, MenuAction.DOWN -> {
                                            target = row.id to action
                                            onOrderChange(order.move(row.id, if (action == MenuAction.UP) -1 else 1))
                                        }
                                        MenuAction.VISIBILITY -> if (section != null) onVisibilityChange(section, !visible)
                                    }
                                }, icon = when (action) {
                                    MenuAction.UP -> R.drawable.ic_priority_up
                                    MenuAction.DOWN -> R.drawable.ic_priority_down
                                    MenuAction.VISIBILITY -> if (visible) R.drawable.ic_menu_visible else R.drawable.ic_menu_hidden
                                }, enabled = position >= 0, actionEnabled = position >= 0,
                                    modifier = Modifier.focusRequester(buttons.getValue(row.id to action))
                                        .onFocusChanged { if (it.isFocused) focusedAction = row.id to action }
                                        .semantics {
                                            if (toggle) {
                                                role = Role.Switch
                                                toggleableState = ToggleableState(visible)
                                            }
                                        }
                                        .focusProperties {
                                            left = row.actions.getOrNull(position - 1)?.let { buttons.getValue(row.id to it) } ?: FocusRequester.Cancel
                                            right = row.actions.getOrNull(position + 1)?.let { buttons.getValue(row.id to it) } ?: FocusRequester.Cancel
                                            up = focusableRows.getOrNull(rowIndex - 1)?.let { focusRow(it, action) } ?: FocusRequester.Cancel
                                            down = focusableRows.getOrNull(rowIndex + 1)?.let { focusRow(it, action) } ?: doneFocus
                                        })
                            }
                        }
                    }
                }
            }
            Text(explanation, color = c.onSurfaceVariant, minLines = 1, maxLines = 1)
            ThemeButton(stringResource(R.string.menu_visibility_done), onClick = { onInteraction(); onDone() },
                modifier = Modifier.fillMaxWidth().focusRequester(doneFocus)
                    .onFocusChanged { if (it.isFocused) focusedAction = null }
                    .focusProperties { up = focusRow(focusableRows.last(), MenuAction.VISIBILITY); down = FocusRequester.Cancel })
        }
        LaunchedEffect(order, target) {
            val row = focusableRows.firstOrNull { it.id == target?.first } ?: focusableRows.first()
            repeat(2) { withFrameNanos { } }
            focusRow(row, target?.second ?: MenuAction.DOWN).requestFocus()
        }
    }
}
