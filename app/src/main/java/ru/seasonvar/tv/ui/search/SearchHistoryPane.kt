package ru.seasonvar.tv.ui.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ru.seasonvar.tv.R
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@Composable
internal fun SearchHistoryPane(queries: List<String>, clearing: Boolean, firstFocus: FocusRequester,
    inputFocus: FocusRequester, menuFocus: FocusRequester, focusOnShow: Boolean, onRecall: (String) -> Unit, onClear: () -> Unit) {
    val d = SeasonvarTheme.dimensions
    val clear = remember { FocusRequester() }
    var lastFocused by remember { mutableStateOf(firstFocus) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.search_history_title), color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleLarge)
            if (queries.isNotEmpty()) ThemeButton(stringResource(R.string.search_history_clear), onClear,
                Modifier.width(IntrinsicSize.Max).height(d.headerButtonSize).focusRequester(clear).focusProperties { left = lastFocused; down = lastFocused; up = inputFocus; right = FocusRequester.Cancel },
                active = false, enabled = !clearing, contentAlignment = Alignment.CenterStart)
        }
        if (queries.isEmpty()) Text(stringResource(R.string.search_history_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        else LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
            itemsIndexed(queries, key = { _, query -> query }) { index, query ->
                val focus = if (index == 0) firstFocus else remember(query) { FocusRequester() }
                ThemeButton(query, { onRecall(query) }, Modifier.fillMaxWidth().height(d.navigationButtonHeight)
                    .focusRequester(focus).onFocusChanged { if (it.isFocused) lastFocused = focus }
                    .focusProperties {
                        left = menuFocus; right = clear
                        up = if (index == 0) inputFocus else FocusRequester.Default
                        down = if (index == queries.lastIndex) FocusRequester.Cancel else FocusRequester.Default
                    }, contentAlignment = Alignment.CenterStart)
            }
        }
    }
    LaunchedEffect(queries.isEmpty(), focusOnShow) {
        repeat(2) { withFrameNanos { } }
        if (queries.isEmpty()) inputFocus.requestFocus() else if (focusOnShow) firstFocus.requestFocus()
    }
}
