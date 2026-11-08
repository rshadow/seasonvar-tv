package ru.seasonvar.tv.ui.filters

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Checkbox
import androidx.tv.material3.RadioButton
import androidx.tv.material3.Text
import ru.seasonvar.tv.R
import ru.seasonvar.tv.ui.ShortMessageEffect
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.navigation.backNavigationKeys
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@Composable
fun ListFilterPanel(title: String, state: ListFiltersState, controller: ListFiltersController) {
    if (!state.open) return
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    var choosing by remember { mutableStateOf<String?>(null) }
    var returnField by remember { mutableStateOf<String?>(null) }
    val selectedField = state.fields.find { it.id == choosing }
    val backFocus = remember { FocusRequester() }
    val applyFocus = remember { FocusRequester() }
    val resetFocus = remember { FocusRequester() }
    val fieldsScroll = rememberScrollState()
    val fieldFocus = remember(state.fields.map { it.id }) { state.fields.associate { it.id to FocusRequester() } }
    fun back() { if (choosing != null) { returnField = choosing; choosing = null } else controller.dismiss() }
    ShortMessageEffect(if (state.applyFailed) stringResource(R.string.filters_apply_error) else null)
    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = ::back)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(Modifier.width(d.filterPanelWidth).fillMaxHeight().focusGroup()
                .testTag("list_filter_panel").backNavigationKeys(::back, {})
                .background(c.background, RoundedCornerShape(d.cardCornerRadius)).padding(d.spacingLarge),
                verticalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
                Row(Modifier.fillMaxWidth().heightIn(min = d.headerButtonSize), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                    Text(if (selectedField == null) stringResource(R.string.filters_title, title) else stringResource(selectedField.title),
                        Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, color = c.onBackground)
                    ThemeButton(stringResource(R.string.filters_back), ::back, Modifier.focusRequester(backFocus).height(d.headerButtonSize))
                }
                Text(stringResource(when {
                    state.failure != null -> R.string.filters_load_error
                    state.loading -> R.string.filters_loading
                    else -> R.string.filters_hint
                }), Modifier.fillMaxWidth().height(d.navigationButtonHeight), maxLines = 2,
                    style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                if (selectedField == null) {
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(fieldsScroll).focusGroup(),
                        verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                        state.fields.forEachIndexed { index, field ->
                            val toggle = field.kind == ListFilterKind.TOGGLE
                            val checked = (state.draft[field.id] ?: field.defaultValue).toBoolean()
                            val value = field.options.find { it.value == state.draft[field.id] }?.label
                                ?: stringResource(if (state.loading) R.string.filters_loading else R.string.details_progress_unknown)
                            FilterRow(stringResource(field.title), value, Modifier.testTag("filter_field_" + field.id)
                                .focusRequester(fieldFocus.getValue(field.id)).focusProperties {
                                    up = state.fields.getOrNull(index - 1)?.let { fieldFocus.getValue(it.id) } ?: backFocus
                                    down = state.fields.getOrNull(index + 1)?.let { fieldFocus.getValue(it.id) } ?: applyFocus
                                }, checkboxChecked = if (toggle) checked else null) {
                                if (!state.loading && !state.applying && state.failure == null) {
                                    if (toggle) controller.choose(field.id, (!checked).toString())
                                    else if (field.options.isNotEmpty()) choosing = field.id
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                        ThemeButton(stringResource(when {
                            state.failure != null -> R.string.retry
                            state.applying -> R.string.filters_applying
                            else -> R.string.filters_apply
                        }), if (state.failure != null) controller::open else controller::apply,
                            Modifier.weight(1f).height(d.navigationButtonHeight).focusRequester(applyFocus)
                                .focusProperties { left = FocusRequester.Cancel; right = resetFocus; down = FocusRequester.Cancel },
                            actionEnabled = !state.loading && !state.applying, contentAlignment = Alignment.Center)
                        ThemeButton(stringResource(R.string.filters_reset), controller::reset,
                            Modifier.weight(1f).height(d.navigationButtonHeight).focusRequester(resetFocus)
                                .focusProperties { left = applyFocus; right = FocusRequester.Cancel; down = FocusRequester.Cancel },
                            actionEnabled = !state.loading && !state.applying && state.failure == null, contentAlignment = Alignment.Center)
                    }
                    LaunchedEffect(choosing) {
                        repeat(2) { withFrameNanos { } }
                        (fieldFocus[returnField] ?: fieldFocus.values.firstOrNull() ?: backFocus).requestFocus()
                    }
                } else key(selectedField.id) {
                    val selectedIndex = selectedField.options.indexOfFirst { it.value == state.draft[selectedField.id] }.coerceAtLeast(0)
                    val list = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
                    val optionFocus = remember(selectedField.options) { selectedField.options.associate { it.value to FocusRequester() } }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                        itemsIndexed(selectedField.options, key = { _, option -> option.value }) { index, option ->
                            FilterRow(option.label, null, Modifier.testTag("filter_option_" + option.value)
                                .focusRequester(optionFocus.getValue(option.value)).focusProperties {
                                    if (index == 0) up = backFocus
                                    if (index == selectedField.options.lastIndex) down = FocusRequester.Cancel
                                }, selected = option.value == state.draft[selectedField.id]) {
                                controller.choose(selectedField.id, option.value)
                                returnField = selectedField.id; choosing = null
                            }
                        }
                    }
                    LaunchedEffect(selectedField.id) {
                        repeat(2) { withFrameNanos { } }
                        selectedField.options.getOrNull(selectedIndex)?.let { optionFocus.getValue(it.value).requestFocus() }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(title: String, value: String?, modifier: Modifier, selected: Boolean? = null,
    checkboxChecked: Boolean? = null, onClick: () -> Unit) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().heightIn(min = d.navigationButtonHeight)
        .focusProperties { left = FocusRequester.Cancel; right = FocusRequester.Cancel }
        .onFocusChanged { focused = it.isFocused }
        .border(d.focusBorderWidth, if (focused) c.primary else Color.Transparent, RoundedCornerShape(d.cardCornerRadius))
        .then(if (checkboxChecked != null) Modifier.toggleable(checkboxChecked, role = Role.Checkbox) { onClick() }
            else Modifier.selectable(selected == true, role = if (selected == null) Role.Button else Role.RadioButton, onClick = onClick))
        .padding(d.spacingMedium), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = if (selected == null) 1 else 2,
            overflow = TextOverflow.Ellipsis, color = c.onBackground)
        if (checkboxChecked != null) Checkbox(checkboxChecked, onCheckedChange = null)
        else if (selected != null) RadioButton(selected, onClick = null)
        else Text(value.orEmpty(), Modifier.widthIn(max = d.filterPanelWidth / 2), style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.onSurfaceVariant)
    }
}
