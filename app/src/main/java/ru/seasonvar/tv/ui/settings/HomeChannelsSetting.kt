package ru.seasonvar.tv.ui.settings

import android.app.Activity
import android.content.Intent
import android.media.tv.TvContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Checkbox
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.seasonvar.tv.R
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.data.home.HomeChannel
import ru.seasonvar.tv.data.home.homeChannelTitle
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.ShortMessageEffect
import ru.seasonvar.tv.ui.showShortMessage
import ru.seasonvar.tv.ui.navigation.backNavigationKeys
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@Composable
internal fun HomeChannelsSetting(focus: FocusRequester, menuFocus: FocusRequester, previousFocus: FocusRequester,
    nextFocus: FocusRequester, onBack: () -> Unit, onClose: () -> Unit, onInteraction: () -> Unit) {
    val app = LocalContext.current.applicationContext as SeasonvarApplication
    val home = app.homeChannels
    val enabled by home.store.enabled.collectAsStateWithLifecycle()
    val account by app.account.session.collectAsStateWithLifecycle()
    val refreshing by home.refreshing.collectAsStateWithLifecycle()
    val refreshFailure by home.failure.collectAsStateWithLifecycle()
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf<HomeChannel?>(null) }
    var busy by remember { mutableStateOf(false) }
    ShortMessageEffect(if (open && refreshFailure) stringResource(R.string.home_refresh_error) else null)
    var pendingFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val scope = rememberCoroutineScope()
    val approvals = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pending?.let { if (result.resultCode != Activity.RESULT_OK) home.disable(it) }
        pending = null
        scope.launch { repeat(2) { withFrameNanos { } }; if (open) pendingFocus?.requestFocus() }
    }
    val supported = remember { home.publisher.supported }
    val launcherSettings = remember { home.usesLauncherSettings }
    Row(Modifier.fillMaxWidth().focusRequester(focus)
        .focusProperties { left = menuFocus; up = previousFocus; down = nextFocus }
        .onFocusChanged { focused = it.isFocused }
        .border(d.focusBorderWidth, if (focused) c.primary else Color.Transparent, RoundedCornerShape(d.cardCornerRadius))
        .clickable(role = Role.Button) { onInteraction(); open = true }
        .padding(d.spacingMedium), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.home_settings_title), style = MaterialTheme.typography.titleLarge, color = c.onBackground)
            Spacer(Modifier.height(d.spacingSmall))
            Text(stringResource(R.string.home_settings_description), style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
        }
        Spacer(Modifier.width(d.spacingMedium))
        Text(if (launcherSettings) stringResource(R.string.home_settings_external_value)
            else stringResource(R.string.home_settings_count, enabled.size), style = MaterialTheme.typography.bodyLarge, color = c.primary)
    }
    if (open) {
        val choices = remember { HomeChannel.entries.associateWith { FocusRequester() } }
        val done = remember { FocusRequester() }
        val available = HomeChannel.entries.filter { supported && !launcherSettings && (!it.personal || account.user != null || it in enabled) }
        fun close() { open = false; scope.launch { repeat(2) { withFrameNanos { } }; focus.requestFocus() } }
        Dialog(onDismissRequest = { open = false; onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            BackHandler { open = false; onBack() }
            Column(Modifier.width(d.dialogWidth).heightIn(max = d.dialogHeight)
                .backNavigationKeys({ open = false; onBack() }, onInteraction)
                .background(c.background, RoundedCornerShape(d.cardCornerRadius)).padding(d.spacingLarge),
                verticalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
                Text(stringResource(R.string.home_settings_title), style = MaterialTheme.typography.headlineMedium, color = c.onBackground)
                if (!launcherSettings) Text(stringResource(when {
                    !supported -> R.string.home_unsupported
                    refreshing -> R.string.home_refreshing
                    else -> R.string.home_settings_hint
                }), style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                HomeChannel.entries.forEach { channel ->
                    var rowFocused by remember { mutableStateOf(false) }
                    val index = available.indexOf(channel)
                    Row(Modifier.fillMaxWidth().focusRequester(choices.getValue(channel))
                        .focusProperties {
                            up = available.getOrNull(index - 1)?.let(choices::getValue) ?: FocusRequester.Cancel
                            down = available.getOrNull(index + 1)?.let(choices::getValue) ?: done
                        }.onFocusChanged { rowFocused = it.isFocused }
                        .border(d.focusBorderWidth, if (rowFocused) c.primary else Color.Transparent, RoundedCornerShape(d.cardCornerRadius))
                        .toggleable(!launcherSettings && channel in enabled, enabled = channel in available && !busy && pending == null, role = Role.Checkbox) { checked ->
                            onInteraction()
                            if (!checked) home.disable(channel) else scope.launch {
                                pendingFocus = choices.getValue(channel)
                                busy = true
                                try {
                                    val id = home.enable(channel)
                                    if (!withContext(Dispatchers.IO) { home.publisher.browsable(id) }) {
                                        pending = channel
                                        approvals.launch(Intent(TvContract.ACTION_REQUEST_CHANNEL_BROWSABLE).putExtra(TvContract.EXTRA_CHANNEL_ID, id))
                                    }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) {
                                    home.disable(channel); pending = null
                                    showShortMessage(app, R.string.home_refresh_error)
                                }
                                finally {
                                    busy = false
                                    if (pending == null && open) {
                                        repeat(2) { withFrameNanos { } }
                                        if (open) choices.getValue(channel).requestFocus()
                                    }
                                }
                            }
                        }.padding(horizontal = d.spacingMedium, vertical = if (launcherSettings) d.spacingSmall else d.spacingMedium),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(homeChannelTitle(channel)), style = MaterialTheme.typography.titleLarge,
                                color = if (channel in available) c.onBackground else c.onSurfaceVariant)
                            if (channel.personal && account.user == null) Text(stringResource(R.string.home_login_required),
                                style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                        }
                        Checkbox(!launcherSettings && channel in enabled, onCheckedChange = null, enabled = channel in available)
                    }
                }
                }
                if (launcherSettings) Text(stringResource(R.string.home_settings_external_help),
                    style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                ThemeButton(stringResource(R.string.menu_visibility_done), { onInteraction(); close() },
                    Modifier.fillMaxWidth().focusRequester(done).focusProperties {
                        up = available.lastOrNull()?.let(choices::getValue) ?: FocusRequester.Cancel; down = FocusRequester.Cancel
                    })
            }
            LaunchedEffect(Unit) { (available.firstOrNull()?.let(choices::getValue) ?: done).requestFocus() }
        }
    }
}
