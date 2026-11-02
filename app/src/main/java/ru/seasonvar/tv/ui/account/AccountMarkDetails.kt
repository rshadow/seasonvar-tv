package ru.seasonvar.tv.ui.account

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.AccountMark

@Composable
internal fun AccountMarkDetails(mark: AccountMark?) {
    if (mark == null) return
    val text = listOfNotNull(
        mark.progress.takeIf { it.isNotBlank() }?.let { stringResource(R.string.account_mark_progress, it) },
        mark.status.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    if (text.isNotBlank()) Text(text,
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
