package ru.seasonvar.tv.ui.account

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.SeriesEntry

@Composable
internal fun AccountEntryDetails(entry: SeriesEntry) {
    val mark = entry.accountMark
    val history = listOfNotNull(
        entry.historyEpisode?.let { stringResource(R.string.episode_number, it) },
        entry.historyVisitedAt?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    val text = listOfNotNull(
        mark?.progress?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.account_mark_progress, it) },
        mark?.status?.takeIf { it.isNotBlank() },
        history.takeIf { it.isNotBlank() }?.let { stringResource(R.string.history_visited_at, it) },
    ).joinToString(" · ")
    if (text.isNotBlank()) Text(text,
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
