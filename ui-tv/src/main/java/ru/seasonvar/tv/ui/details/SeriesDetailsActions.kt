package ru.seasonvar.tv.ui.details

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.playback.PlaybackRequest
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.reviews.ReviewsActivity
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@Composable
internal fun SeriesDetailsActions(state: DetailsUiState, entry: SeriesEntry, model: SeriesDetailsViewModel,
    navigation: DetailsNavigation, menuFocus: FocusRequester, preparing: Boolean,
    nextFocus: FocusRequester,
    onActions: ((SeriesEntry) -> Unit)?, onPlay: (PlaybackRequest) -> Unit, onInteraction: () -> Unit,
    actionScrolling: (FocusRequester) -> Modifier) = with(navigation) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val details = state.details
    val context = LocalContext.current
    val canOpenReviews = !preparing && details != null
    val canOpenActions = !preparing && onActions != null
    fun show(which: DetailsDialog, focus: FocusRequester) = navigation.show(which, focus, onInteraction)
    Row(Modifier.fillMaxWidth().bringIntoViewRequester(actions),
        horizontalArrangement = Arrangement.spacedBy(d.spacingMedium), verticalAlignment = Alignment.Top) {
        FlowRow(Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(d.spacingSmall),
            verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
            ThemeButton(stringResource(when {
                state.failure != null -> R.string.retry
                state.selectionFailure != null -> R.string.details_choose_episode
                !preparing && !state.canPlay -> R.string.details_reload
                state.resume != null -> R.string.details_continue
                else -> R.string.details_watch
            }), onClick = {
                onInteraction()
                when {
                    state.failure != null -> model.retry()
                    state.selectionFailure != null -> show(DetailsDialog.EPISODES, play)
                    !state.canPlay -> model.retry()
                    else -> model.playbackRequest()?.let(onPlay)
                }
            }, loading = preparing,
                modifier = Modifier.height(d.headerButtonSize).then(actionScrolling(play)).focusRequester(play).focusProperties {
                    left = menuFocus; up = FocusRequester.Cancel
                    right = when {
                        (details?.seasons?.size ?: 0) > 1 -> season
                        (details?.translations?.size ?: 0) > 1 -> translation
                        canOpenActions -> actionsMenu
                        canOpenReviews -> reviews
                        else -> FocusRequester.Cancel
                    }
                    down = nextFocus
                })
            if (details != null) {
                val currentSeason = details.seasons.firstOrNull { it.id == entry.id }
                val seasonLabel = if (currentSeason?.number != null) currentSeason.label
                    else entry.season?.let { stringResource(R.string.season_number, it) }
                        ?: stringResource(R.string.details_season)
                if (details.seasons.size > 1) ThemeButton(seasonLabel + " ▾",
                    { show(DetailsDialog.SEASONS, season) }, Modifier.height(d.headerButtonSize).then(actionScrolling(season))
                        .focusRequester(season).focusProperties {
                            left = play; up = FocusRequester.Cancel
                            right = if (details.translations.size > 1) translation else if (canOpenActions) actionsMenu else if (canOpenReviews) reviews else FocusRequester.Cancel
                            down = nextFocus
                        })
                else if (currentSeason?.number != null || entry.season != null) Text(seasonLabel, Modifier.heightIn(min = d.headerButtonSize).wrapContentHeight(),
                    color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                if (details.translations.isNotEmpty()) {
                    val label = state.translation?.let { stringResource(R.string.details_translation, it.label) }
                        ?: stringResource(R.string.details_choose_translation)
                    if (details.translations.size > 1 || state.translation == null) ThemeButton(label + " ▾",
                        { show(DetailsDialog.TRANSLATIONS, translation) }, Modifier.height(d.headerButtonSize).then(actionScrolling(translation))
                            .focusRequester(translation).focusProperties {
                                up = FocusRequester.Cancel
                                left = if (details.seasons.size > 1) season else play
                                right = if (canOpenActions) actionsMenu else if (canOpenReviews) reviews else FocusRequester.Cancel
                                down = nextFocus
                            })
                    else Text(label, Modifier.heightIn(min = d.headerButtonSize).wrapContentHeight(),
                        color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (canOpenActions) ThemeButton(stringResource(R.string.series_actions_menu), {
                onInteraction(); restoreActions = true
                onActions(entry.copy(initialSelection = ru.seasonvar.tv.data.EntrySelection(
                    episodeId = state.episode?.id, episodeLabel = state.episode?.label,
                    translationId = state.translationId, translationLabel = state.translation?.label)))
            }, Modifier.then(actionScrolling(actionsMenu)).focusRequester(actionsMenu).focusProperties {
                up = FocusRequester.Cancel; right = if (canOpenReviews) reviews else FocusRequester.Cancel
                left = when { (details?.translations?.size ?: 0) > 1 -> translation; (details?.seasons?.size ?: 0) > 1 -> season; else -> play }
                down = nextFocus
            }, icon = R.drawable.ic_more_vert)
        }
        ThemeButton(stringResource(R.string.reviews_button, details?.reviewCount?.let { if (it > 999) "999+" else it.toString() } ?: "—"),
            { onInteraction(); ReviewsActivity.open(context, entry) },
            Modifier.width(d.reviewsButtonWidth).height(d.headerButtonSize).then(actionScrolling(reviews))
                .focusRequester(reviews).focusProperties {
                    up = FocusRequester.Cancel; right = FocusRequester.Cancel
                    left = when {
                        canOpenActions -> actionsMenu
                        (details?.translations?.size ?: 0) > 1 -> translation
                        (details?.seasons?.size ?: 0) > 1 -> season
                        else -> play
                    }
                    down = nextFocus
                }, enabled = canOpenReviews)
    }
}
