package ru.seasonvar.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import ru.seasonvar.tv.data.settings.PacSettings
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.network.ProxyProtocol
import ru.seasonvar.tv.data.network.ProxyConfiguration
import ru.seasonvar.tv.data.network.canonicalHost
import ru.seasonvar.tv.ui.RemoteTextField
import ru.seasonvar.tv.ui.SettingsChoiceDialog
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.showShortMessage
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@Composable
internal fun ProxySetting(value: ProxyConfiguration, onSave: suspend (ProxyConfiguration) -> Boolean,
    focus: FocusRequester, previous: FocusRequester, menu: FocusRequester) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun close() { open = false; scope.launch { repeat(2) { withFrameNanos { } }; focus.requestFocus() } }
    Row(Modifier.fillMaxWidth().focusRequester(focus)
        .focusProperties { up = previous; left = menu; down = FocusRequester.Cancel }
        .onFocusChanged { focused = it.isFocused }
        .border(d.focusBorderWidth, if (focused) c.primary else Color.Transparent, RoundedCornerShape(d.cardCornerRadius))
        .clickable(role = Role.Button) { open = true }.padding(d.spacingMedium), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.proxy_title), style = MaterialTheme.typography.titleLarge, color = c.onBackground)
            Spacer(Modifier.height(d.spacingSmall))
            Text(stringResource(R.string.proxy_description), style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
        }
        Spacer(Modifier.width(d.spacingMedium))
        Text(stringResource(when (value) {
            ProxyConfiguration.Disabled -> R.string.proxy_off
            ProxyConfiguration.Unavailable -> R.string.proxy_unavailable
            ProxyConfiguration.AntiZapret -> R.string.proxy_antizapret
            is ProxyConfiguration.Pac -> R.string.proxy_pac
            is ProxyConfiguration.Server -> proxyLabel(value.protocol)
        }), style = MaterialTheme.typography.bodyLarge, color = c.primary)
    }
    if (open) ProxyDialog(value, onSave, ::close)
}

@Composable
private fun ProxyDialog(value: ProxyConfiguration, onSave: suspend (ProxyConfiguration) -> Boolean, onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as ru.seasonvar.tv.SeasonvarApplication
    val server = value as? ProxyConfiguration.Server ?: remember { app.networkSettings.savedManualProxy() }
    var choice by remember { mutableStateOf(when (value) {
        ProxyConfiguration.Disabled -> ProxyChoice.OFF
        ProxyConfiguration.AntiZapret -> ProxyChoice.ANTIZAPRET
        is ProxyConfiguration.Pac -> ProxyChoice.PAC
        else -> if ((value as? ProxyConfiguration.Server)?.protocol == ProxyProtocol.SOCKS5) ProxyChoice.SOCKS else ProxyChoice.HTTP
    }) }
    val enabled = choice == ProxyChoice.HTTP || choice == ProxyChoice.SOCKS
    val pacMode = choice == ProxyChoice.PAC || choice == ProxyChoice.ANTIZAPRET
    var pacUrl by remember { mutableStateOf((value as? ProxyConfiguration.Pac)?.url ?: app.networkSettings.savedPacUrl()) }
    var host by remember { mutableStateOf(server?.host.orEmpty()) }
    var port by remember { mutableStateOf(server?.port?.toString().orEmpty()) }
    var username by remember { mutableStateOf(server?.username.orEmpty()) }
    var password by remember { mutableStateOf(server?.password.orEmpty()) }
    var choosing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { List(10) { FocusRequester() } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    fun close() { if (!busy) onClose() }
    fun save() {
        if (busy) return
        val config = try {
            when (choice) {
                ProxyChoice.OFF -> ProxyConfiguration.Disabled
                ProxyChoice.ANTIZAPRET -> ProxyConfiguration.AntiZapret
                ProxyChoice.PAC -> ProxyConfiguration.Pac(pacUrl.trim())
                else -> ProxyConfiguration.Server(canonicalHost(host), port.toInt(), username, password,
                    if (choice == ProxyChoice.SOCKS) ProxyProtocol.SOCKS5 else ProxyProtocol.HTTP_CONNECT)
            }
        } catch (_: IllegalArgumentException) { showShortMessage(context, if (pacMode) R.string.pac_invalid_url else R.string.proxy_invalid); return }
        busy = true
        scope.launch {
            val success = try { onSave(config) } catch (_: Exception) { false }
            busy = false
            if (success) onClose() else showShortMessage(context,
                if (pacMode) R.string.pac_enable_failed else R.string.proxy_save_failed)
        }
    }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = ::close)
        Column(Modifier.width(d.dialogWidth).heightIn(max = d.dialogHeight)
            .background(c.background, RoundedCornerShape(d.cardCornerRadius)).padding(d.spacingLarge),
            verticalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
            Text(stringResource(R.string.proxy_title), style = MaterialTheme.typography.headlineMedium, color = c.onBackground)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
                ThemeButton(stringResource(R.string.proxy_type, stringResource(choice.label)),
                    { choosing = true }, Modifier.fillMaxWidth().height(d.headerButtonSize).focusRequester(focus[0])
                        .focusProperties { up = FocusRequester.Cancel; down = if (pacMode) { if (choice == ProxyChoice.PAC) focus[7] else focus[8] } else if (enabled) focus[1] else focus[5] },
                    enabled = !busy, contentAlignment = Alignment.CenterStart)
                if (pacMode) {
                    RemoteTextField(if (choice == ProxyChoice.ANTIZAPRET) PacSettings.ANTIZAPRET_URL else pacUrl,
                        { pacUrl = it }, R.string.pac_url, false,
                        focus[7], focus[8], focus[6], busy || choice == ProxyChoice.ANTIZAPRET,
                        previous = focus[0], keyboardType = KeyboardType.Uri,
                        onNext = { focus[8].requestFocus() })
                    PacOptions(app, if (choice == ProxyChoice.ANTIZAPRET) ProxyConfiguration.AntiZapret
                        else runCatching { ProxyConfiguration.Pac(pacUrl.trim()) }.getOrNull(), busy,
                        focus[8], focus[9], if (choice == ProxyChoice.PAC) focus[7] else focus[0], focus[5], focus[6])
                } else if (enabled) {
                    RemoteTextField(host, { host = it }, R.string.proxy_host, false, focus[1], focus[2], focus[6], busy || !enabled,
                        previous = focus[0], keyboardType = KeyboardType.Uri, onNext = { focus[2].requestFocus() })
                    RemoteTextField(port, { port = it }, R.string.proxy_port, false, focus[2], focus[3], focus[6], busy || !enabled,
                        previous = focus[1], keyboardType = KeyboardType.Number, onNext = { focus[3].requestFocus() })
                    RemoteTextField(username, { username = it }, R.string.proxy_username, false, focus[3], focus[4], focus[6], busy || !enabled,
                        previous = focus[2], keyboardType = KeyboardType.Text, onNext = { focus[4].requestFocus() })
                    RemoteTextField(password, { password = it }, R.string.proxy_password, true, focus[4], focus[5], focus[6], busy || !enabled,
                        previous = focus[3], onNext = { focus[5].requestFocus() })
                    Text(stringResource(R.string.proxy_auth_hint), style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
                ThemeButton(stringResource(R.string.proxy_save), ::save, Modifier.weight(1f).height(d.headerButtonSize).focusRequester(focus[5])
                    .focusProperties { up = if (pacMode) focus[8] else if (enabled) focus[4] else focus[0]; right = focus[6] }, loading = busy)
                ThemeButton(stringResource(R.string.proxy_cancel), ::close, Modifier.weight(1f).height(d.headerButtonSize).focusRequester(focus[6])
                    .focusProperties { up = if (pacMode) focus[9] else if (enabled) focus[4] else focus[0]; left = focus[5] }, enabled = !busy)
            }
        }
    }
    if (choosing) SettingsChoiceDialog(R.string.proxy_type_title, ProxyChoice.entries, choice,
        { it.label },
        { choice = it; choosing = false; scope.launch { repeat(2) { withFrameNanos { } }; focus[0].requestFocus() } },
        { choosing = false }, { choosing = false }, {})
    LaunchedEffect(Unit) { repeat(2) { withFrameNanos { } }; focus[0].requestFocus() }
    DisposableEffect(Unit) { onDispose { username = ""; password = "" } }
}

private enum class ProxyChoice(val label: Int) {
    OFF(R.string.proxy_off), HTTP(R.string.proxy_http), SOCKS(R.string.proxy_socks5),
    PAC(R.string.proxy_pac), ANTIZAPRET(R.string.proxy_antizapret)
}

private fun proxyLabel(protocol: ProxyProtocol): Int = if (protocol == ProxyProtocol.SOCKS5) R.string.proxy_socks5 else R.string.proxy_http
