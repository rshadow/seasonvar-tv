package ru.seasonvar.tv.ui.settings

import android.content.ActivityNotFoundException
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.account.SessionOperation
import ru.seasonvar.tv.data.settings.DeviceDiagnosticResults
import ru.seasonvar.tv.data.settings.DeviceDiagnostics
import ru.seasonvar.tv.data.settings.DiagnosticResult
import ru.seasonvar.tv.data.update.UpdateInstaller
import ru.seasonvar.tv.diagnostics.DiagnosticStep
import ru.seasonvar.tv.diagnostics.LocalDiagnosticFailure
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.showShortMessage
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@Composable
internal fun DeviceDiagnosticsSection(focus: SettingsFocus, onInteraction: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val d = SeasonvarTheme.dimensions
    val section = remember { BringIntoViewRequester() }
    var request by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<DeviceDiagnosticResults?>(null) }
    var checking by remember { mutableStateOf(true) }
    var refreshFocused by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { request++ }
    LaunchedEffect(request) {
        checking = true
        results = withContext(Dispatchers.IO) { DeviceDiagnostics.check(appContext) }
        checking = false
    }

    val needsPermission = results?.installPermission == DiagnosticResult.PERMISSION_REQUIRED
    val sessionFailures = results?.sessionFailures.orEmpty()
    val nextExpiration = sessionFailures.minOfOrNull { it.expiresAtMillis }
    LaunchedEffect(nextExpiration) {
        if (nextExpiration == null) return@LaunchedEffect
        delay((nextExpiration - System.currentTimeMillis()).coerceAtLeast(0))
        results = results?.let { current ->
            current.copy(sessionFailures = current.sessionFailures.filter { it.expiresAtMillis > nextExpiration })
        }
    }
    LaunchedEffect(refreshFocused, needsPermission, sessionFailures.size) {
        if (refreshFocused) section.bringIntoView()
    }

    Column(Modifier.fillMaxWidth().bringIntoViewRequester(section)) {
        SettingsRefreshHeader(R.string.device_diagnostics_title, R.string.device_diagnostics_refresh,
            { onInteraction(); request++ },
            Modifier.focusRequester(focus.diagnosticsRefresh).focusProperties {
                up = focus.proxy
                down = if (needsPermission) focus.installPermission else FocusRequester.Cancel
                left = focus.menu
            }.onFocusChanged { refreshFocused = it.isFocused })
        Spacer(Modifier.height(d.spacingSmall))
        DiagnosticResults(results, checking)
        if (needsPermission) {
            Spacer(Modifier.height(d.spacingSmall))
            val unavailable = stringResource(R.string.device_diagnostics_settings_unavailable)
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
private fun DiagnosticResults(results: DeviceDiagnosticResults?, checking: Boolean) {
    val context = LocalContext.current
    val completed = results.takeUnless { checking }
    DiagnosticLine(R.string.device_diagnostics_free_space, completed?.freeSpace,
        completed?.freeBytes?.let { Formatter.formatFileSize(context, it) })
    DiagnosticLine(R.string.device_diagnostics_storage, completed?.storage)
    DiagnosticLine(R.string.device_diagnostics_cache, completed?.cache)
    DiagnosticLine(R.string.device_diagnostics_encryption, completed?.encryption)
    DiagnosticLine(R.string.device_diagnostics_session, completed?.savedSession)
    DiagnosticLine(R.string.device_diagnostics_install, completed?.installPermission)
    val sessionFailures = results?.sessionFailures.orEmpty()
    if (sessionFailures.isEmpty()) {
        DiagnosticLine(R.string.device_diagnostics_last_session_failure, completed?.let { DiagnosticResult.NO_SESSION },
            if (completed != null) stringResource(R.string.device_diagnostics_no_failure) else null)
    } else {
        sessionFailures.forEach { record ->
            val label = when (record.operation) {
                SessionOperation.RESTORE -> R.string.device_diagnostics_last_restore_failure
                SessionOperation.SAVE -> R.string.device_diagnostics_last_save_failure
            }
            DiagnosticLine(label, DiagnosticResult.Failed(record.failure))
        }
    }
}

@Composable
private fun DiagnosticLine(label: Int, result: DiagnosticResult?, details: String? = null) {
    val d = SeasonvarTheme.dimensions
    val icon = when (result) {
        null -> R.drawable.ic_reload
        DiagnosticResult.OK, DiagnosticResult.NO_SESSION -> R.drawable.ic_check
        is DiagnosticResult.Failed, DiagnosticResult.PERMISSION_REQUIRED -> R.drawable.ic_connection_failed
    }
    val status = details ?: when (result) {
        null -> stringResource(R.string.connection_checking)
        DiagnosticResult.OK -> stringResource(R.string.connection_available)
        is DiagnosticResult.Failed -> diagnosticFailureLabel(result.failure)
        DiagnosticResult.NO_SESSION -> stringResource(R.string.device_diagnostics_no_session)
        DiagnosticResult.PERMISSION_REQUIRED -> stringResource(R.string.device_diagnostics_permission_required)
    }
    val tint = when (result) {
        DiagnosticResult.OK -> SeasonvarTheme.statusSuccess
        is DiagnosticResult.Failed -> SeasonvarTheme.statusFailure
        else -> SeasonvarTheme.statusPending
    }
    Row(Modifier.fillMaxWidth().heightIn(min = d.headerButtonSize),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
        Icon(painterResource(icon), contentDescription = null, Modifier.size(d.headerButtonIconSize), tint = tint)
        Text(stringResource(label), Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.bodyLarge)
        Text(status, Modifier.weight(1f), textAlign = TextAlign.End,
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun diagnosticFailureLabel(failure: LocalDiagnosticFailure): String {
    val label = when (failure.step) {
        DiagnosticStep.FREE_SPACE -> R.string.device_check_free_space
        DiagnosticStep.PREFERENCES_OPEN -> R.string.device_check_preferences_open
        DiagnosticStep.PREFERENCES_WRITE -> R.string.device_check_preferences_write
        DiagnosticStep.PREFERENCES_READ -> R.string.device_check_preferences_read
        DiagnosticStep.CACHE_CREATE -> R.string.device_check_cache_create
        DiagnosticStep.CACHE_WRITE -> R.string.device_check_cache_write
        DiagnosticStep.CACHE_READ -> R.string.device_check_cache_read
        DiagnosticStep.KEYSTORE_OPEN -> R.string.device_check_keystore_open
        DiagnosticStep.KEY_LOOKUP -> if (failure.reason == "MISSING") R.string.device_check_key_missing else R.string.device_check_key_lookup
        DiagnosticStep.KEY_CREATE -> R.string.device_check_key_create
        DiagnosticStep.KEY_CLEANUP -> R.string.device_check_key_cleanup
        DiagnosticStep.CRYPTO_ENCRYPT -> R.string.device_check_encrypt
        DiagnosticStep.CRYPTO_DECRYPT -> R.string.device_check_decrypt
        DiagnosticStep.CRYPTO_FORMAT -> R.string.device_check_ciphertext_format
        DiagnosticStep.SESSION_READ -> R.string.device_check_session_read
        DiagnosticStep.SESSION_DECODE -> R.string.device_check_session_decode
        DiagnosticStep.SESSION_ENCODE -> R.string.device_check_session_encode
        DiagnosticStep.SESSION_WRITE -> R.string.device_check_session_write
        DiagnosticStep.INSTALL_PERMISSION -> R.string.device_check_install_permission
    }
    return stringResource(R.string.device_diagnostics_failure_detail, stringResource(label), failure.code)
}
