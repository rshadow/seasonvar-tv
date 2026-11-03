package ru.seasonvar.tv.ui.account

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.SeriesEntry

@Composable
private fun progressValue(entry: SeriesEntry): String? {
    val seasons = entry.accountProgress?.seasons?.let {
        stringResource(R.string.account_progress_seasons, it.marked, it.total)
    }
    val episodes = entry.accountProgress?.episodes?.takeIf { it.seasonId == entry.id }?.counter?.let {
        stringResource(R.string.account_progress_episodes, it.marked, it.total)
    }
    val parts = listOfNotNull(seasons, episodes)
    if (entry.accountProgress != null) return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    return entry.accountMark?.progress?.trim()
        ?.replaceFirst(Regex("""^Отмечено\b\s*:?\s*""", RegexOption.IGNORE_CASE), "")
        ?.trim()?.takeIf { it.isNotBlank() }
}

@Composable
internal fun AccountProgressLabel(entry: SeriesEntry, modifier: Modifier = Modifier) {
    val progress = progressValue(entry) ?: return
    Text(stringResource(R.string.account_mark_progress, progress), modifier,
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun AccountEntryDetails(entry: SeriesEntry, includeProgress: Boolean = true) {
    val mark = entry.accountMark
    val history = if (entry.historyVisitedAt != null) listOfNotNull(
        entry.initialSelection.episodeLabel?.let { stringResource(R.string.episode_number, it) },
        entry.historyVisitedAt.takeIf { it.isNotBlank() },
    ).joinToString(" · ") else ""
    val progress = if (includeProgress) progressValue(entry) else null
    val text = listOfNotNull(
        progress?.let { stringResource(R.string.account_mark_progress, it) },
        mark?.status?.takeIf { it.isNotBlank() },
        history.takeIf { it.isNotBlank() }?.let { stringResource(R.string.history_visited_at, it) },
    ).joinToString(" · ")
    if (text.isNotBlank()) Text(text,
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
