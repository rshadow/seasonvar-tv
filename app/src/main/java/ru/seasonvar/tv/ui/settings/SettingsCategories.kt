package ru.seasonvar.tv.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
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
import ru.seasonvar.tv.ui.player.playbackSpeedLabel
import ru.seasonvar.tv.ui.player.PlayerClockPosition
import ru.seasonvar.tv.ui.sectionTitle
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@Composable
internal fun ApplicationSettingsSection(startPage: Section, onStartPageChange: (Section) -> Unit,
    hiddenSections: Set<Section>, onSectionVisibilityChange: (Section, Boolean) -> Unit, signedIn: Boolean,
    translationSettingsModel: TranslationSettingsViewModel?, showActors: Boolean, onShowActorsChange: (Boolean) -> Unit,
    interfaceTheme: InterfaceTheme, onInterfaceThemeChange: (InterfaceTheme) -> Unit,
    fontSize: InterfaceFontSize, onFontSizeChange: (InterfaceFontSize) -> Unit,
    focus: SettingsFocus, onBack: () -> Unit, onCloseDialog: () -> Unit, onInteraction: () -> Unit) {
    val d = SeasonvarTheme.dimensions
    SettingsGroupTitle(R.string.application_settings_title)
    SettingsChoice(R.string.start_page_title, R.string.start_page_description, R.string.start_page_dialog_title,
        Section.startPages(signedIn, hiddenSections), startPage.availableAtStart(signedIn, hiddenSections), ::sectionTitle, onStartPageChange,
        focus.start, focus.menu, FocusRequester.Cancel, focus.visibility, onBack, onCloseDialog, onInteraction)
    Spacer(Modifier.height(d.spacingMedium))
    MenuVisibilitySetting(hiddenSections, onSectionVisibilityChange,
        focus.visibility, focus.menu, focus.start, focus.home, onBack, onCloseDialog, onInteraction)
    Spacer(Modifier.height(d.spacingMedium))
    HomeChannelsSetting(focus.home, focus.menu, focus.visibility, focus.translations, onBack, onCloseDialog, onInteraction)
    Spacer(Modifier.height(d.spacingMedium))
    PreferredTranslationsSetting(translationSettingsModel, focus.translations,
        focus.menu, focus.home, focus.actors, onBack, onCloseDialog, onInteraction)
    Spacer(Modifier.height(d.spacingMedium))
    SettingsToggle(R.string.show_actors_title, R.string.show_actors_description, showActors,
        { onInteraction(); onShowActorsChange(it) }, focus.actors, focus.menu, focus.translations, focus.appearance)
    Spacer(Modifier.height(d.spacingMedium))
    InterfaceThemeSetting(interfaceTheme, onInterfaceThemeChange,
        focus.appearance, focus.menu, focus.actors, focus.fontSize, onBack, onCloseDialog, onInteraction)
    Spacer(Modifier.height(d.spacingMedium))
    SettingsChoice(R.string.font_size_title, R.string.font_size_description,
        options = InterfaceFontSize.entries, selected = fontSize, label = InterfaceFontSize::label,
        onChange = onFontSizeChange, focus = focus.fontSize, menu = focus.menu,
        previous = focus.appearance, next = focus.autoNext, onBack = onBack, onClose = onCloseDialog, onInteraction = onInteraction)
}

@Composable
internal fun PlayerSettingsSection(autoNextEnabled: Boolean, onAutoNextChange: (Boolean) -> Unit,
    autoNextSeasonEnabled: Boolean, onAutoNextSeasonChange: (Boolean) -> Unit,
    playerClockPosition: PlayerClockPosition, onPlayerClockPositionChange: (PlayerClockPosition) -> Unit,
    playbackSpeed: PlaybackSpeed, onPlaybackSpeedChange: (PlaybackSpeed) -> Unit,
    showPlayerControls: Boolean, onShowPlayerControlsChange: (Boolean) -> Unit,
    focus: SettingsFocus, onBack: () -> Unit, onCloseDialog: () -> Unit, onInteraction: () -> Unit) {
    val d = SeasonvarTheme.dimensions
    SettingsGroupTitle(R.string.player_settings_title)
    SettingsToggle(R.string.auto_next_setting_title, R.string.auto_next_setting_description, autoNextEnabled,
        { onInteraction(); onAutoNextChange(it) }, focus.autoNext, focus.menu, focus.fontSize, focus.autoSeason)
    Spacer(Modifier.height(d.spacingMedium))
    SettingsToggle(R.string.auto_next_season_setting_title, R.string.auto_next_season_setting_description, autoNextSeasonEnabled,
        { onInteraction(); onAutoNextSeasonChange(it) }, focus.autoSeason, focus.menu, focus.autoNext, focus.clock)
    Spacer(Modifier.height(d.spacingMedium))
    SettingsChoice(R.string.player_clock_title, R.string.player_clock_description,
        options = PlayerClockPosition.entries, selected = playerClockPosition, label = ::playerClockLabel, onChange = onPlayerClockPositionChange,
        focus = focus.clock, menu = focus.menu, previous = focus.autoSeason, next = focus.speed,
        onBack = onBack, onClose = onCloseDialog, onInteraction = onInteraction)
    Spacer(Modifier.height(d.spacingMedium))
    SettingsChoice(R.string.playback_speed_default_title, R.string.playback_speed_default_description,
        options = PlaybackSpeed.entries, selected = playbackSpeed, label = ::playbackSpeedLabel, onChange = onPlaybackSpeedChange,
        focus = focus.speed, menu = focus.menu, previous = focus.clock, next = focus.showPlayerControls,
        onBack = onBack, onClose = onCloseDialog, onInteraction = onInteraction)
    Spacer(Modifier.height(d.spacingMedium))
    SettingsToggle(R.string.show_player_controls_title, R.string.show_player_controls_description, showPlayerControls,
        { onInteraction(); onShowPlayerControlsChange(it) }, focus.showPlayerControls, focus.menu, focus.speed, focus.prefetch)
}

@Composable
internal fun NetworkSettingsSection(imagePrefetchEnabled: Boolean, onImagePrefetchChange: (Boolean) -> Unit,
    listPrefetchEnabled: Boolean, onListPrefetchChange: (Boolean) -> Unit,
    focus: SettingsFocus, onInteraction: () -> Unit) {
    SettingsGroupTitle(R.string.network_title)
    SettingsToggle(R.string.image_prefetch_title, R.string.image_prefetch_description, imagePrefetchEnabled,
        { onInteraction(); onImagePrefetchChange(it) }, focus.prefetch, focus.menu, focus.showPlayerControls, focus.listPrefetch)
    Spacer(Modifier.height(SeasonvarTheme.dimensions.spacingMedium))
    SettingsToggle(R.string.list_prefetch_title, R.string.list_prefetch_description, listPrefetchEnabled,
        { onInteraction(); onListPrefetchChange(it) }, focus.listPrefetch, focus.menu, focus.prefetch, focus.connectionRefresh)
}

@Composable
internal fun ConnectionSettingsSection(connectionCheck: ConnectionCheckState, onConnectionRefresh: () -> Unit,
    bypassEnabled: Boolean, onBypassChange: (Boolean) -> Unit, dnsProvider: DnsProvider, onDnsChange: (DnsProvider) -> Unit,
    proxy: ProxyConfiguration, onProxyChange: suspend (ProxyConfiguration) -> Boolean,
    focus: SettingsFocus, onBack: () -> Unit, onCloseDialog: () -> Unit, onInteraction: () -> Unit) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    ConnectionChecks(connectionCheck, { onInteraction(); onConnectionRefresh() },
        focus.connectionRefresh, focus.listPrefetch, focus.bypass, focus.menu)
    Spacer(Modifier.height(d.spacingMedium))
    SettingsToggle(R.string.bypass_title, R.string.bypass_description, bypassEnabled,
        onBypassChange, focus.bypass, focus.menu, focus.connectionRefresh, focus.dns)
    Spacer(Modifier.height(d.spacingLarge))
    SettingsChoice(R.string.dns_setting_title, R.string.dns_description, R.string.dns_title,
        DnsProvider.entries, dnsProvider, ::dnsLabel, onDnsChange, focus.dns, focus.menu, focus.bypass, focus.proxy,
        onBack, onCloseDialog, onInteraction)
    Spacer(Modifier.height(d.spacingLarge))
    ProxySetting(proxy, onProxyChange, focus.proxy, focus.dns, focus.menu)
    Spacer(Modifier.height(d.spacingLarge))
    Text(stringResource(R.string.network_applies), color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(d.spacingSmall))
    Text(stringResource(R.string.network_external_tools_hint), color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun SettingsGroupTitle(title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
    Spacer(Modifier.height(SeasonvarTheme.dimensions.spacingMedium))
}

private fun dnsLabel(provider: DnsProvider): Int = when (provider) {
    DnsProvider.SYSTEM -> R.string.dns_system
    DnsProvider.QUAD9_DOH -> R.string.dns_quad9
    DnsProvider.CLOUDFLARE_DOH -> R.string.dns_cloudflare
}

private fun playerClockLabel(position: PlayerClockPosition): Int = when (position) {
    PlayerClockPosition.OFF -> R.string.player_clock_off
    PlayerClockPosition.LEFT -> R.string.player_clock_left
    PlayerClockPosition.CENTER -> R.string.player_clock_center
    PlayerClockPosition.RIGHT -> R.string.player_clock_right
}

