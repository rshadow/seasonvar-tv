package ru.seasonvar.tv.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.*
import ru.seasonvar.tv.R
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.data.network.ProxyConfiguration
import ru.seasonvar.tv.data.network.pac.PacState
import ru.seasonvar.tv.data.settings.PacRefreshInterval
import ru.seasonvar.tv.data.settings.PacSettings
import ru.seasonvar.tv.ui.SettingsChoiceDialog
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.showShortMessage
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@Composable
internal fun PacOptions(app: SeasonvarApplication, proxy: ProxyConfiguration?, busy: Boolean,
    intervalFocus: FocusRequester, refreshFocus: FocusRequester, previous: FocusRequester,
    next: FocusRequester, nextRight: FocusRequester) {
    val manager = remember(proxy) { proxy?.let(app.pac::manager) }
    val state = manager?.state?.collectAsState()?.value ?: PacState()
    var seconds by remember(manager) { mutableIntStateOf(manager?.cooldownSeconds() ?: 0) }
    var choosing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(manager) {
        while (isActive) { seconds = manager?.cooldownSeconds() ?: 0; delay(1000) }
    }
    PacUpdateRow(state, if (proxy == ProxyConfiguration.AntiZapret) seconds else 0, !busy && manager != null,
        { choosing = true }, {
            if (manager != null && proxy != null) {
                if (proxy == ProxyConfiguration.AntiZapret) seconds = (PacSettings.REFRESH_COOLDOWN_MILLIS / 1000).toInt()
                scope.launch {
                    try { manager.refresh(app.networkSettings.configuration.value.copy(proxy = proxy)) }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { showShortMessage(app, R.string.pac_first_failed) }
                    finally { seconds = manager.cooldownSeconds(); app.pac.reschedule() }
                }
            }
        }, intervalFocus, refreshFocus, previous, next, nextRight)
    if (choosing) SettingsChoiceDialog(R.string.pac_interval_title, PacRefreshInterval.entries, state.interval, ::intervalLabel,
        { interval ->
            scope.launch {
                try { manager?.setInterval(interval); app.pac.reschedule() }
                catch (_: Exception) { showShortMessage(app, R.string.proxy_save_failed) }
            }
            choosing = false
            scope.launch { repeat(2) { withFrameNanos { } }; intervalFocus.requestFocus() }
        }, { choosing = false }, { choosing = false }, {})
}

@Composable
internal fun PacUpdateRow(state: PacState, seconds: Int, enabled: Boolean,
    onInterval: () -> Unit, onRefresh: () -> Unit, intervalFocus: FocusRequester, refreshFocus: FocusRequester,
    previous: FocusRequester, next: FocusRequester, nextRight: FocusRequester) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val loading = state.downloading || state.preparing
    val countdown = seconds > 0
    val context = LocalContext.current
    val date = if (state.fetchedAt == 0L) "" else java.util.Date(state.fetchedAt).let { instant ->
        android.text.format.DateFormat.getDateFormat(context).format(instant) + " " +
            android.text.format.DateFormat.getTimeFormat(context).format(instant)
    }
    val progress = when {
        state.downloading -> stringResource(R.string.pac_loading)
        state.preparing -> stringResource(R.string.pac_preparing)
        state.failure != null -> stringResource(R.string.pac_first_failed)
        else -> ""
    }
    val updated = if (date.isNotEmpty()) stringResource(R.string.pac_updated, date) else stringResource(R.string.pac_empty)
    val countdownDescription = stringResource(R.string.pac_wait, seconds)
    Row(Modifier.fillMaxWidth().height(d.headerButtonSize),
        horizontalArrangement = Arrangement.spacedBy(d.spacingMedium), verticalAlignment = Alignment.CenterVertically) {
        ThemeButton(stringResource(R.string.pac_interval, stringResource(intervalLabel(state.interval))), onInterval,
            Modifier.weight(1f).fillMaxHeight().focusRequester(intervalFocus)
                .focusProperties { up = previous; down = next; right = refreshFocus; left = FocusRequester.Cancel },
            actionEnabled = enabled, contentAlignment = Alignment.CenterStart)
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            Text(updated + if (progress.isNotEmpty()) "\n" + progress else "",
                style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
        ThemeButton(if (countdown) countdownDescription else stringResource(R.string.pac_refresh), onRefresh,
            Modifier.size(d.headerButtonSize).focusRequester(refreshFocus)
            .focusProperties { up = previous; down = nextRight; left = intervalFocus; right = FocusRequester.Cancel }
            .then(if (countdown) Modifier.semantics { contentDescription = countdownDescription } else Modifier),
            icon = R.drawable.ic_reload, iconText = if (countdown) seconds.toString() else null,
            actionEnabled = enabled && !loading && !countdown,
            loading = loading && !countdown, contentPadding = PaddingValues(d.spacingSmall))
    }
}

private fun intervalLabel(interval: PacRefreshInterval): Int = when (interval) {
    PacRefreshInterval.HOUR -> R.string.pac_hour
    PacRefreshInterval.SIX_HOURS -> R.string.pac_six_hours
    PacRefreshInterval.TWELVE_HOURS -> R.string.pac_twelve_hours
    PacRefreshInterval.DAY -> R.string.pac_day
    PacRefreshInterval.WEEK -> R.string.pac_week
}
