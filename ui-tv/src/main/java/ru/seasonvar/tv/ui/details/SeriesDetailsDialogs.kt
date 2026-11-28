package ru.seasonvar.tv.ui.details

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.RadioButton
import androidx.tv.material3.Text
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.playback.PlaybackRequest
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.navigation.backNavigationKeys
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

internal enum class DetailsDialog { SEASONS, TRANSLATIONS, EPISODES }

@Composable
internal fun SeriesDetailsDialogs(state: DetailsUiState, entry: SeriesEntry, model: SeriesDetailsViewModel,
    navigation: DetailsNavigation, onInteraction: () -> Unit, onPlay: (PlaybackRequest) -> Unit) = with(navigation) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val details = state.details
    fun dismiss() = navigation.dismiss(onInteraction)
    val currentDialog = dialog
    if (currentDialog != null && details != null) {
        Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            BackHandler { dismiss() }
            Column(Modifier.width(d.dialogWidth).height(d.dialogHeight)
                .backNavigationKeys(::dismiss, onInteraction)
                .background(c.background, RoundedCornerShape(d.cardCornerRadius)).padding(d.spacingLarge),
                verticalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
                Text(stringResource(when (currentDialog) {
                    DetailsDialog.SEASONS -> R.string.details_season
                    DetailsDialog.TRANSLATIONS -> R.string.details_translation_title
                    DetailsDialog.EPISODES -> R.string.details_all_episodes
                }), style = MaterialTheme.typography.headlineSmall, color = c.onBackground)
                when (currentDialog) {
                    DetailsDialog.SEASONS -> ChoiceList(details.seasons.map { it.id to it.label }, entry.id) {
                        restoreFocus = play
                        revealActionsAfterLoad = it != entry.id
                        dismiss()
                        model.selectSeason(it)
                    }
                    DetailsDialog.TRANSLATIONS -> ChoiceList(details.translations.map { it.id to it.label }, state.translationId) {
                        dismiss(); model.selectTranslation(it)
                    }
                    DetailsDialog.EPISODES -> {
                        val focus = remember { FocusRequester() }
                        val episodes = state.episodes.orEmpty()
                        val focusId = state.episodeFocusId
                        val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState(
                            initialFirstVisibleItemIndex = episodes.indexOfFirst { it.id == focusId }.coerceAtLeast(0))
                        if (episodes.isEmpty()) Text(stringResource(if (state.episodes == null)
                            R.string.details_episodes_unavailable else R.string.details_episodes_empty),
                            color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        LazyVerticalGrid(GridCells.Adaptive(d.episodeWidth), state = grid,
                            horizontalArrangement = Arrangement.spacedBy(d.spacingSmall),
                            verticalArrangement = Arrangement.spacedBy(d.spacingSmall), contentPadding = PaddingValues(d.focusBorderWidth)) {
                            items(episodes, key = { it.id }) { episode ->
                                ThemeButton(episode.label, onClick = { onInteraction(); model.selectEpisode(episode.id); model.playbackRequest(episode.id)?.let(onPlay) },
                                    Modifier.height(d.headerButtonSize).then(if (episode.id == focusId) Modifier.focusRequester(focus) else Modifier),
                                    active = episode.id == state.episodeId)
                            }
                        }
                        LaunchedEffect(focusId) {
                            if (focusId != null) { repeat(2) { withFrameNanos { } }; focus.requestFocus() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceList(options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(
        initialFirstVisibleItemIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0))
    LazyColumn(Modifier.fillMaxWidth().selectableGroup(), state = listState,
        verticalArrangement = Arrangement.spacedBy(d.spacingSmall), contentPadding = PaddingValues(d.focusBorderWidth)) {
        itemsIndexed(options, key = { _, item -> item.first }) { index, item ->
            var focused by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().heightIn(min = d.navigationButtonHeight)
                .then(if (item.first == selected) Modifier.focusRequester(focus) else Modifier)
                .focusProperties {
                    if (index == 0) up = FocusRequester.Cancel
                    if (index == options.lastIndex) down = FocusRequester.Cancel
                }
                .onFocusChanged { focused = it.isFocused }
                .border(d.focusBorderWidth, if (focused) c.primary else Color.Transparent, RoundedCornerShape(d.buttonCornerRadius))
                .selectable(item.first == selected, role = Role.RadioButton, onClick = { onSelect(item.first) })
                .padding(d.spacingSmall), verticalAlignment = Alignment.CenterVertically) {
                Text(item.second, Modifier.weight(1f), color = SeasonvarTheme.textColor({ focused }, c.onBackground), style = MaterialTheme.typography.bodyLarge, fontWeight = SeasonvarTheme.textWeight { focused })
                RadioButton(item.first == selected, onClick = null)
            }
        }
    }
    LaunchedEffect(Unit) { repeat(2) { withFrameNanos { } }; focus.requestFocus() }
}
