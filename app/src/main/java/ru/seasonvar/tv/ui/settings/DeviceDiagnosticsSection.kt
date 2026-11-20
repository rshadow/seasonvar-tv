package ru.seasonvar.tv.ui.settings

import android.content.ActivityNotFoundException
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.settings.DeviceDiagnosticResults
import ru.seasonvar.tv.data.settings.DeviceDiagnostics
import ru.seasonvar.tv.data.settings.DiagnosticResult
import ru.seasonvar.tv.data.update.UpdateInstaller
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.showShortMessage
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@Composable
internal fun DeviceDiagnosticsSection(focus: SettingsFocus, onInteraction: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val d = SeasonvarTheme.dimensions
    var request by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<DeviceDiagnosticResults?>(null) }
    var checking by remember { mutableStateOf(true) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { request++ }
    LaunchedEffect(request) {
        checking = true
        results = withContext(Dispatchers.IO) { DeviceDiagnostics.check(appContext) }
        checking = false
    }

    val needsPermission = results?.installPermission == DiagnosticResult.PERMISSION_REQUIRED
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.device_diagnostics_title), Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        ThemeButton(stringResource(R.string.device_diagnostics_refresh), { onInteraction(); request++ },
            Modifier.size(d.headerButtonSize).focusRequester(focus.diagnosticsRefresh).focusProperties {
                up = focus.proxy
                down = if (needsPermission) focus.installPermission else FocusRequester.Cancel
                left = focus.menu
            }, icon = R.drawable.ic_reload, contentPadding = PaddingValues(d.spacingSmall))
    }
    Spacer(Modifier.height(d.spacingSmall))
    val completed = results.takeUnless { checking }
    val freeSpace = completed?.freeBytes?.let {
        stringResource(R.string.device_diagnostics_free, Formatter.formatFileSize(context, it))
    }
    DiagnosticLine(R.string.device_diagnostics_storage, completed?.storage, freeSpace)
    DiagnosticLine(R.string.device_diagnostics_cache, completed?.cache)
    DiagnosticLine(R.string.device_diagnostics_encryption, completed?.encryption)
    DiagnosticLine(R.string.device_diagnostics_session, completed?.savedSession)
    DiagnosticLine(R.string.device_diagnostics_install, completed?.installPermission)
    Spacer(Modifier.height(d.spacingSmall))
    val unavailable = stringResource(R.string.device_diagnostics_settings_unavailable)
    Box(Modifier.heightIn(min = d.headerButtonSize), contentAlignment = Alignment.CenterStart) {
        if (needsPermission) {
            ThemeButton(stringResource(R.string.device_diagnostics_open_install_settings), {
                onInteraction()
                try {
                    permissionLauncher.launch(UpdateInstaller(appContext).permissionIntent())
                    focus.diagnosticsRefresh.requestFocus()
                } catch (_: ActivityNotFoundException) {
                    showShortMessage(context, unavailable)
                } catch (_: SecurityException) {
                    showShortMessage(context, unavailable)
                }
            }, Modifier.heightIn(min = d.headerButtonSize).focusRequester(focus.installPermission).focusProperties {
                up = focus.diagnosticsRefresh
                down = FocusRequester.Cancel
                left = focus.menu
            })
        }
    }
}

@Composable
private fun DiagnosticLine(label: Int, result: DiagnosticResult?, details: String? = null) {
    val d = SeasonvarTheme.dimensions
    val (icon, status) = when (result) {
        null -> R.drawable.ic_reload to R.string.connection_checking
        DiagnosticResult.OK -> R.drawable.ic_check to R.string.connection_available
        DiagnosticResult.FAILED -> R.drawable.ic_connection_failed to R.string.device_diagnostics_failed
        DiagnosticResult.NO_SESSION -> R.drawable.ic_check to R.string.device_diagnostics_no_session
        DiagnosticResult.PERMISSION_REQUIRED -> R.drawable.ic_connection_failed to R.string.device_diagnostics_permission_required
    }
    val tint = when (result) {
        DiagnosticResult.OK -> SeasonvarTheme.statusSuccess
        DiagnosticResult.FAILED -> SeasonvarTheme.statusFailure
        else -> SeasonvarTheme.statusPending
    }
    Row(Modifier.fillMaxWidth().heightIn(min = d.headerButtonSize),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
        Icon(painterResource(icon), contentDescription = null, Modifier.size(d.headerButtonIconSize), tint = tint)
        Text(stringResource(label), Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.bodyLarge)
        Text(if (result == DiagnosticResult.OK && details != null) details else stringResource(status),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    }
}
