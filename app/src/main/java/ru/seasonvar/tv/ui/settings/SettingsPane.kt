package ru.seasonvar.tv.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.network.DnsProvider
import ru.seasonvar.tv.data.network.ProxyConfiguration
import ru.seasonvar.tv.ui.navigation.Section
import ru.seasonvar.tv.data.playback.PlaybackSpeed
import ru.seasonvar.tv.ui.player.PlayerClockPosition
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@Composable
internal fun SettingsPane(
    connectionCheck: ConnectionCheckState,
    onConnectionRefresh: () -> Unit,
    onConnectionVisible: (Boolean) -> Unit,
    imagePrefetchEnabled: Boolean,
    onImagePrefetchChange: (Boolean) -> Unit,
    listPrefetchEnabled: Boolean,
    onListPrefetchChange: (Boolean) -> Unit,
    showActors: Boolean,
    onShowActorsChange: (Boolean) -> Unit,
    interfaceTheme: InterfaceTheme,
    onInterfaceThemeChange: (InterfaceTheme) -> Unit,
    autoNextSeasonEnabled: Boolean,
    onAutoNextSeasonChange: (Boolean) -> Unit,
    autoNextEnabled: Boolean,
    onAutoNextChange: (Boolean) -> Unit,
    playbackSpeed: PlaybackSpeed,
    onPlaybackSpeedChange: (PlaybackSpeed) -> Unit,
    showPlayerControls: Boolean,
    onShowPlayerControlsChange: (Boolean) -> Unit,
    playerClockPosition: PlayerClockPosition,
    onPlayerClockPositionChange: (PlayerClockPosition) -> Unit,
    translationSettingsModel: TranslationSettingsViewModel?,
    bypassEnabled: Boolean,
    settingsContentFocus: FocusRequester,
    menuFocus: FocusRequester,
    onBypassChange: (Boolean) -> Unit,
    dnsProvider: DnsProvider,
    onDnsChange: (DnsProvider) -> Unit,
    proxy: ProxyConfiguration,
    onProxyChange: suspend (ProxyConfiguration) -> Boolean,
    startPage: Section,
    onStartPageChange: (Section) -> Unit,
    hiddenSections: Set<Section>,
    onSectionVisibilityChange: (Section, Boolean) -> Unit,
    signedIn: Boolean,
    onBack: () -> Unit,
    onCloseDialog: () -> Unit,
    onInteraction: () -> Unit,
) {
    val d = SeasonvarTheme.dimensions
    val focus = remember(settingsContentFocus, menuFocus) { SettingsFocus(settingsContentFocus, menuFocus) }
    val visibleCallback by rememberUpdatedState(onConnectionVisible)
    DisposableEffect(Unit) {
        visibleCallback(true)
        onDispose { visibleCallback(false) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(d.spacingLarge))
        ApplicationSettingsSection(startPage, onStartPageChange, hiddenSections, onSectionVisibilityChange, signedIn,
            translationSettingsModel, showActors, onShowActorsChange, interfaceTheme, onInterfaceThemeChange,
            focus, onBack, onCloseDialog, onInteraction)
        Spacer(Modifier.height(d.spacingLarge))
        PlayerSettingsSection(autoNextEnabled, onAutoNextChange, autoNextSeasonEnabled, onAutoNextSeasonChange,
            playerClockPosition, onPlayerClockPositionChange, playbackSpeed, onPlaybackSpeedChange,
            showPlayerControls, onShowPlayerControlsChange, focus, onBack, onCloseDialog, onInteraction)
        Spacer(Modifier.height(d.spacingLarge))
        NetworkSettingsSection(imagePrefetchEnabled, onImagePrefetchChange, listPrefetchEnabled, onListPrefetchChange,
            focus, onInteraction)
        Spacer(Modifier.height(d.spacingLarge))
        ConnectionSettingsSection(connectionCheck, onConnectionRefresh, bypassEnabled, onBypassChange,
            dnsProvider, onDnsChange, proxy, onProxyChange, focus, onBack, onCloseDialog, onInteraction)
    }
}

/** Фокус переживает изменения значений и открытие диалогов на общей прокручиваемой странице. */
@Stable
internal class SettingsFocus(val start: FocusRequester, val menu: FocusRequester) {
    val visibility = FocusRequester()
    val home = FocusRequester()
    val translations = FocusRequester()
    val actors = FocusRequester()
    val appearance = FocusRequester()
    val autoNext = FocusRequester()
    val autoSeason = FocusRequester()
    val clock = FocusRequester()
    val speed = FocusRequester()
    val showPlayerControls = FocusRequester()
    val prefetch = FocusRequester()
    val listPrefetch = FocusRequester()
    val connectionRefresh = FocusRequester()
    val bypass = FocusRequester()
    val dns = FocusRequester()
    val proxy = FocusRequester()
}
