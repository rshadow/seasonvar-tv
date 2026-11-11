package ru.seasonvar.tv.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.network.*
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@Composable
internal fun ConnectionChecks(
    state: ConnectionCheckState,
    refresh: () -> Unit,
    focus: FocusRequester,
    previous: FocusRequester,
    next: FocusRequester,
    menu: FocusRequester,
) {
    val d = SeasonvarTheme.dimensions
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.connection_title), Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        ThemeButton(stringResource(R.string.connection_refresh), refresh,
            Modifier.size(d.headerButtonSize).focusRequester(focus).focusProperties {
                up = previous; down = next; left = menu
            }, icon = R.drawable.ic_reload, contentPadding = PaddingValues(d.spacingSmall))
    }
    Spacer(Modifier.height(d.spacingSmall))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
        ConnectionTarget.entries.forEach { target ->
            val result = state.results.getValue(target)
            val description = stringResource(when (result.status) {
                ConnectionStatus.CHECKING -> R.string.connection_checking
                ConnectionStatus.AVAILABLE -> R.string.connection_available
                ConnectionStatus.FAILED -> when (result.failure) {
                    ConnectionFailure.TIMEOUT -> R.string.connection_timeout
                    ConnectionFailure.TLS -> R.string.connection_tls_failure
                    ConnectionFailure.HTTP -> R.string.connection_http_failure
                    ConnectionFailure.CONTENT -> R.string.connection_content_failure
                    ConnectionFailure.SOURCE -> R.string.connection_source_failure
                    else -> R.string.connection_network_failure
                }
            })
            Row(Modifier.weight(1f).height(d.headerButtonSize).semantics(mergeDescendants = true) {
                stateDescription = description
            }, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                Icon(painterResource(when (result.status) {
                    ConnectionStatus.AVAILABLE -> R.drawable.ic_check
                    ConnectionStatus.CHECKING -> R.drawable.ic_reload
                    ConnectionStatus.FAILED -> R.drawable.ic_connection_failed
                }), contentDescription = null, Modifier.size(d.headerButtonIconSize),
                    tint = colorResource(when (result.status) {
                        ConnectionStatus.AVAILABLE -> R.color.seasonvar_status_success
                        ConnectionStatus.CHECKING -> R.color.seasonvar_status_pending
                        ConnectionStatus.FAILED -> R.color.seasonvar_status_failure
                    }))
                Text(stringResource(target.label()), color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    val summary = when {
        state.checking -> stringResource(R.string.connection_checking)
        state.failed.isEmpty() -> stringResource(R.string.connection_all_available)
        else -> stringResource(R.string.connection_failed, state.failed.map { stringResource(it.label()) }.joinToString(", "))
    }
    Text(summary, Modifier.fillMaxWidth().height(d.headerButtonSize),
        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private fun ConnectionTarget.label() = when (this) {
    ConnectionTarget.SITE -> R.string.connection_site
    ConnectionTarget.POSTERS -> R.string.connection_posters
    ConnectionTarget.VIDEO -> R.string.connection_video
}
