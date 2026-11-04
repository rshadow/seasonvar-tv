package ru.seasonvar.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import kotlinx.coroutines.launch
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.TranslationPriorities
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.catalog.LoadFailure
import ru.seasonvar.tv.ui.navigation.backNavigationKeys
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

private enum class PriorityAction { UP, DOWN, REMOVE, ADD }
private data class PriorityKey(val value: String, val action: PriorityAction)
private data class PriorityFocus(val value: String? = null, val action: PriorityAction = PriorityAction.ADD, val revision: Int = 0)
private fun allowed(action: PriorityAction, index: Int, count: Int) = when (action) {
    PriorityAction.UP -> index > 0
    PriorityAction.DOWN -> index < count - 1
    else -> true
}
private fun firstAction(index: Int, count: Int) = when {
    index > 0 -> PriorityAction.UP
    count > 1 -> PriorityAction.DOWN
    else -> PriorityAction.REMOVE
}

@Composable
internal fun PreferredTranslationsSetting(
    model: TranslationSettingsViewModel?,
    focus: FocusRequester,
    menuFocus: FocusRequester,
    previousFocus: FocusRequester,
    nextFocus: FocusRequester,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onInteraction: () -> Unit,
) {
    val preferred = model?.preferred?.collectAsStateWithLifecycle()?.value ?: emptyList()
    val state = model?.state?.collectAsStateWithLifecycle()?.value ?: TranslationChoicesState()
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf(PriorityFocus()) }
    fun request(values: List<String>, value: String? = values.firstOrNull(), action: PriorityAction? = null) {
        val index = values.indexOf(value)
        target = if (index < 0) PriorityFocus(revision = target.revision + 1)
        else PriorityFocus(value, action?.takeIf { allowed(it, index, values.size) } ?: firstAction(index, values.size), target.revision + 1)
    }
    Row(Modifier.fillMaxWidth().focusRequester(focus)
        .focusProperties { left = menuFocus; up = previousFocus; down = nextFocus }
        .onFocusChanged { focused = it.isFocused }
        .border(d.focusBorderWidth, if (focused) c.primary else Color.Transparent, RoundedCornerShape(d.cardCornerRadius))
        .clickable(role = Role.Button) { onInteraction(); request(preferred); dialog = true }
        .padding(d.spacingMedium), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.preferred_translation_title), style = MaterialTheme.typography.titleLarge, color = c.onBackground)
            Spacer(Modifier.height(d.spacingSmall))
            Text(stringResource(R.string.preferred_translation_description), style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
        }
        Spacer(Modifier.width(d.spacingMedium))
        val summary = when (preferred.size) {
            0 -> stringResource(R.string.preferred_translation_standard)
            1 -> preferred.first()
            else -> stringResource(R.string.preferred_translations_summary, preferred.first(), preferred.size - 1)
        }
        Text(summary, style = MaterialTheme.typography.bodyLarge, color = c.primary,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = d.posterWidth))
    }
    if (dialog && !adding) {
        TranslationPrioritiesDialog(preferred, target,
            onMove = { value, offset ->
                val updated = model?.move(value, offset) ?: preferred
                request(updated, value, if (offset < 0) PriorityAction.UP else PriorityAction.DOWN)
            }, onRemove = { value ->
                val index = preferred.indexOf(value)
                val updated = model?.remove(value) ?: preferred
                request(updated, updated.getOrNull(index.coerceAtMost(updated.lastIndex)))
            }, onAdd = { adding = true; model?.openChoices() },
            onBack = { dialog = false; onBack() }, onClose = { dialog = false; onClose() }, onInteraction = onInteraction)
    }
    if (dialog && adding) {
        fun returnToPriorities() {
            model?.closeChoices(); adding = false
            target = PriorityFocus(revision = target.revision + 1)
        }
        AddTranslationDialog(preferred, state, onRetry = { model?.openChoices() }, onSelect = { value ->
            val updated = model?.add(value) ?: preferred
            model?.closeChoices(); adding = false; request(updated, updated.lastOrNull())
        }, onBack = ::returnToPriorities, onClose = ::returnToPriorities, onInteraction = onInteraction)
    }
    DisposableEffect(model) { onDispose { model?.closeChoices() } }
}

@Composable
private fun TranslationPrioritiesDialog(
    values: List<String>, target: PriorityFocus,
    onMove: (String, Int) -> Unit, onRemove: (String) -> Unit, onAdd: () -> Unit,
    onBack: () -> Unit, onClose: () -> Unit, onInteraction: () -> Unit,
) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val addFocus = remember { FocusRequester() }
    val actions = listOf(PriorityAction.UP, PriorityAction.DOWN, PriorityAction.REMOVE)
    val buttons = remember(values) { values.flatMap { value -> actions.map { PriorityKey(value, it) } }
        .associateWith { FocusRequester() } }
    var explanation by remember { mutableStateOf<String?>(null) }
    fun neighbour(index: Int, action: PriorityAction): FocusRequester = when {
        index < 0 -> FocusRequester.Cancel
        index >= values.size -> addFocus
        else -> buttons.getValue(PriorityKey(values[index], action.takeIf { allowed(it, index, values.size) } ?: firstAction(index, values.size)))
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onBack)
        Column(Modifier.width(d.dialogWidth).heightIn(max = d.dialogHeight)
            .backNavigationKeys(onBack, onInteraction)
            .background(c.background, RoundedCornerShape(d.cardCornerRadius)).padding(d.spacingLarge),
            verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
            Text(stringResource(R.string.preferred_translation_title), style = MaterialTheme.typography.headlineMedium, color = c.onBackground)
            Text(stringResource(R.string.preferred_translations_order), color = c.onSurfaceVariant)
            if (values.isEmpty()) Text(stringResource(R.string.preferred_translations_empty), color = c.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), state = listState) {
                itemsIndexed(values, key = { _, value -> value }) { index, value ->
                    Row(Modifier.fillMaxWidth().padding(vertical = d.spacingSmall), verticalAlignment = Alignment.CenterVertically) {
                        Text((index + 1).toString(), color = c.onSurfaceVariant, style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.width(d.headerButtonSize))
                        Text(value, color = c.onBackground, style = MaterialTheme.typography.titleLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(d.spacingMedium))
                        Row(horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                            actions.forEach { action ->
                                val label = stringResource(when (action) {
                                    PriorityAction.UP -> R.string.preferred_translations_up
                                    PriorityAction.DOWN -> R.string.preferred_translations_down
                                    else -> R.string.preferred_translations_remove
                                }, value)
                                val enabled = allowed(action, index, values.size)
                                val available = actions.filter { allowed(it, index, values.size) }
                                val position = available.indexOf(action)
                                ThemeButton(label, onClick = {
                                    onInteraction()
                                    when (action) {
                                        PriorityAction.UP -> onMove(value, -1)
                                        PriorityAction.DOWN -> onMove(value, 1)
                                        else -> onRemove(value)
                                    }
                                }, icon = when (action) {
                                    PriorityAction.UP -> R.drawable.ic_priority_up
                                    PriorityAction.DOWN -> R.drawable.ic_priority_down
                                    else -> R.drawable.ic_priority_remove
                                }, enabled = enabled,
                                    modifier = Modifier.focusRequester(buttons.getValue(PriorityKey(value, action)))
                                        .onFocusChanged { if (it.isFocused) explanation = label }
                                        .focusProperties {
                                            left = available.getOrNull(position - 1)?.let { buttons.getValue(PriorityKey(value, it)) } ?: FocusRequester.Cancel
                                            right = available.getOrNull(position + 1)?.let { buttons.getValue(PriorityKey(value, it)) } ?: FocusRequester.Cancel
                                            up = neighbour(index - 1, action)
                                            down = neighbour(index + 1, action)
                                        })
                            }
                        }
                    }
                }
            }
            Text(explanation.orEmpty(), color = c.onSurfaceVariant, maxLines = 1, minLines = 1, overflow = TextOverflow.Ellipsis)
            val addLabel = stringResource(R.string.preferred_translations_add)
            val addHint = stringResource(R.string.preferred_translations_add_hint)
            ThemeButton(addLabel, onClick = { onInteraction(); onAdd() }, modifier = Modifier.focusRequester(addFocus)
                .onFocusChanged { if (it.isFocused) explanation = addHint }
                .focusProperties {
                    left = FocusRequester.Cancel; right = FocusRequester.Cancel; down = FocusRequester.Cancel
                    up = if (values.isEmpty()) FocusRequester.Cancel else neighbour(values.lastIndex, PriorityAction.REMOVE)
                })
            Text(stringResource(R.string.preferred_translations_fallback), color = c.onSurfaceVariant)
        }
        LaunchedEffect(target, values) {
            val index = values.indexOf(target.value)
            if (index >= 0 && target.action != PriorityAction.ADD) {
                listState.scrollToItem(index)
                repeat(2) { withFrameNanos { } }
                neighbour(index, target.action).requestFocus()
            } else {
                repeat(2) { withFrameNanos { } }
                addFocus.requestFocus()
            }
        }
    }
}

@Composable
private fun AddTranslationDialog(
    selected: List<String>,
    state: TranslationChoicesState,
    onRetry: () -> Unit,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onInteraction: () -> Unit,
) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val options = state.options.filter { value -> selected.none { TranslationPriorities.name(it) == TranslationPriorities.name(value) } }
    val rowFocus = remember(options) { options.associateWith { FocusRequester() } }
    val filtered = options.filter { TranslationPriorities.name(it).contains(TranslationPriorities.name(query)) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    fun firstResult() {
        keyboard?.hide()
        val value = filtered.firstOrNull()
        if (value != null) scope.launch {
            listState.scrollToItem(0); withFrameNanos { }
            rowFocus.getValue(value).requestFocus()
        } else if (state.failure != null) retryFocus.requestFocus()
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onBack)
        Column(Modifier.width(d.dialogWidth).heightIn(max = d.dialogHeight)
            .backNavigationKeys(onBack, onInteraction)
            .background(c.background, RoundedCornerShape(d.cardCornerRadius)).padding(d.spacingLarge),
            verticalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
            Text(stringResource(R.string.preferred_translations_add_title), style = MaterialTheme.typography.headlineMedium, color = c.onBackground)
            val searchLabel = stringResource(R.string.preferred_translation_search)
            BasicTextField(value = query, onValueChange = { query = it; onInteraction() },
                singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onBackground),
                cursorBrush = SolidColor(c.primary), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { firstResult() }),
                modifier = Modifier.fillMaxWidth().heightIn(min = d.navigationButtonHeight).focusRequester(searchFocus)
                    .onFocusChanged { searchFocused = it.isFocused }
                    .onPreviewKeyEvent {
                        when {
                            it.key == Key.DirectionCenter -> {
                                if (it.type == KeyEventType.KeyDown) keyboard?.show()
                                true
                            }
                            it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown -> { firstResult(); true }
                            else -> false
                        }
                    }.semantics { contentDescription = searchLabel },
                decorationBox = { inner -> Box(Modifier.fillMaxWidth().heightIn(min = d.navigationButtonHeight)
                    .border(d.focusBorderWidth, if (searchFocused) c.primary else c.onSurfaceVariant, RoundedCornerShape(d.buttonCornerRadius))
                    .padding(d.spacingSmall), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(searchLabel, color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    inner()
                } })
            if (state.loading) Text(stringResource(R.string.preferred_translation_loading), color = c.onSurfaceVariant)
            state.failure?.let { failure ->
                Text(stringResource(when (failure) {
                    LoadFailure.NETWORK -> R.string.preferred_translation_network_error
                    LoadFailure.HTTP -> R.string.preferred_translation_http_error
                    else -> R.string.preferred_translation_markup_error
                }), color = c.onSurfaceVariant)
                ThemeButton(stringResource(R.string.preferred_translation_retry), onClick = { onInteraction(); searchFocus.requestFocus(); onRetry() },
                    modifier = Modifier.focusRequester(retryFocus).focusProperties {
                        up = searchFocus; left = FocusRequester.Cancel; right = FocusRequester.Cancel
                        down = FocusRequester.Cancel
                    })
            }
            if (!state.loading && state.failure == null && filtered.isEmpty()) Text(stringResource(
                if (query.isBlank() && options.isEmpty()) R.string.preferred_translations_all_added else R.string.preferred_translation_empty),
                color = c.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), state = listState,
                verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                itemsIndexed(filtered, key = { _, value -> value }) { index, value ->
                    var focused by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().heightIn(min = d.navigationButtonHeight)
                        .focusRequester(rowFocus.getValue(value))
                        .focusProperties {
                            left = FocusRequester.Cancel; right = FocusRequester.Cancel
                            if (index == 0) up = if (state.failure == null) searchFocus else retryFocus
                            if (index == filtered.lastIndex) down = FocusRequester.Cancel
                        }.onFocusChanged { focused = it.isFocused }
                        .border(d.focusBorderWidth, if (focused) c.primary else Color.Transparent, RoundedCornerShape(d.cardCornerRadius))
                        .clickable(role = Role.Button, onClick = { onInteraction(); onSelect(value) })
                        .padding(d.spacingMedium), verticalAlignment = Alignment.CenterVertically) {
                        Text(value, style = MaterialTheme.typography.titleLarge, color = c.onBackground,
                            modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        LaunchedEffect(Unit) { repeat(2) { withFrameNanos { } }; searchFocus.requestFocus() }
        LaunchedEffect(query) { listState.scrollToItem(0) }
    }
}
