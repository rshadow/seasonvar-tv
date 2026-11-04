package ru.seasonvar.tv.ui.about

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.Icon
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.MutableStateFlow
import ru.seasonvar.tv.BuildConfig
import ru.seasonvar.tv.R
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import ru.seasonvar.tv.ui.update.*

@Composable
internal fun AboutPane(
    contentFocus: FocusRequester,
    menuFocus: FocusRequester,
    onInteraction: () -> Unit,
    model: UpdateViewModel?,
) {
    val emptyState = remember { MutableStateFlow(UpdateUiState()) }
    val update by (model?.state ?: emptyState).collectAsStateWithLifecycle()
    LaunchedEffect(model) { model?.enter() }
    if (model != null) UpdateInstallationEffect(model, update)
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val githubUrl = stringResource(R.string.about_github_url)
    var linkUnavailable by remember { mutableStateOf(false) }
    val updateFocus = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.about_title), style = MaterialTheme.typography.headlineLarge, color = c.onBackground)
        Spacer(Modifier.height(d.spacingLarge))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = c.onBackground)
        Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
        Spacer(Modifier.height(d.spacingMedium))
        Text(stringResource(R.string.about_description), color = c.onBackground)
        Text(stringResource(R.string.about_source), color = c.onSurfaceVariant)
        Spacer(Modifier.height(d.spacingLarge))
        ThemeButton(stringResource(R.string.about_github), onClick = {
            onInteraction()
            linkUnavailable = false
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)).addCategory(Intent.CATEGORY_BROWSABLE))
            } catch (_: ActivityNotFoundException) { linkUnavailable = true }
            catch (_: SecurityException) { linkUnavailable = true }
        }, modifier = Modifier.focusRequester(contentFocus).focusProperties {
            left = menuFocus
            right = FocusRequester.Cancel
            up = FocusRequester.Cancel
            down = if (update.available && update.release?.asset != null) updateFocus else FocusRequester.Cancel
        })
        Spacer(Modifier.height(d.spacingSmall))
        Text(githubUrl, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
        if (linkUnavailable) Text(stringResource(R.string.about_github_unavailable), color = c.onSurfaceVariant)
        Spacer(Modifier.height(d.spacingLarge))
        if (update.checking) {
            UpdateSpinner()
            Spacer(Modifier.height(d.spacingSmall))
            Text(stringResource(R.string.update_checking), color = c.onSurfaceVariant)
        } else if (update.checked) {
            update.release?.let { Text(stringResource(R.string.update_latest, it.version), color = c.onBackground) }
            if (update.failure == null) Text(stringResource(when {
                update.available -> R.string.update_available
                update.release == null -> R.string.update_none
                else -> R.string.update_current
            }), color = c.onSurfaceVariant)
            if (update.available && update.release?.asset == null) {
                Text(stringResource(R.string.update_asset_missing), color = c.onSurfaceVariant)
            }
        }
        if (update.available && update.release?.asset != null) {
            Spacer(Modifier.height(d.spacingMedium))
            val label = if (update.downloading) stringResource(R.string.update_downloading, update.percent)
                else stringResource(if (update.ready != null) R.string.update_install else R.string.update_download)
            if (update.downloading) UpdateSpinner()
            ThemeButton(label, onClick = { onInteraction(); model?.install() },
                loading = update.downloading || update.installing,
                loadingLabel = label,
                modifier = Modifier.focusRequester(updateFocus).focusProperties {
                    left = menuFocus
                    right = FocusRequester.Cancel
                    up = contentFocus
                    down = FocusRequester.Cancel
                })
        }
        update.failure?.let {
            Spacer(Modifier.height(d.spacingSmall))
            Text(stringResource(failureText(it)), color = c.onSurfaceVariant)
        }
    }
}

private fun failureText(failure: UpdateFailure) = when (failure) {
    UpdateFailure.NETWORK -> R.string.update_network_error
    UpdateFailure.HTTP -> R.string.update_http_error
    UpdateFailure.FORMAT -> R.string.update_format_error
    UpdateFailure.INTEGRITY -> R.string.update_integrity_error
    UpdateFailure.SIGNATURE -> R.string.update_signature_error
    UpdateFailure.PACKAGE -> R.string.update_package_error
    UpdateFailure.PERMISSION -> R.string.update_permission_error
    UpdateFailure.INSTALLER -> R.string.update_installer_error
}

@Composable
private fun UpdateSpinner() {
    val transition = rememberInfiniteTransition(label = "update")
    val angle by transition.animateFloat(0f, 360f,
        infiniteRepeatable(tween(SeasonvarTheme.refreshRotationDurationMillis, easing = LinearEasing)),
        label = "updateRotation")
    Icon(painterResource(R.drawable.ic_reload), contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(SeasonvarTheme.dimensions.headerButtonIconSize)
            .graphicsLayer { rotationZ = angle }
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate })
}

@Composable
private fun UpdateInstallationEffect(model: UpdateViewModel, state: UpdateUiState) {
    val context = LocalContext.current
    val installer = (context.applicationContext as ru.seasonvar.tv.SeasonvarApplication).updateInstaller
    val installLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.installationReturned()
    }
    val launchInstaller = {
        try {
            state.ready?.let { installLauncher.launch(installer.installIntent(it)) }
        } catch (_: ActivityNotFoundException) { model.installationFailed(UpdateFailure.INSTALLER) }
        catch (_: SecurityException) { model.installationFailed(UpdateFailure.INSTALLER) }
        catch (_: ru.seasonvar.tv.data.update.UpdatePackageException) { model.installationFailed(UpdateFailure.PACKAGE) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (installer.permissionGranted()) launchInstaller()
        else model.installationFailed(UpdateFailure.PERMISSION)
    }
    LaunchedEffect(state.installRequested) {
        if (state.installRequested) {
            model.installRequestHandled()
            try {
                if (installer.permissionGranted()) launchInstaller()
                else permissionLauncher.launch(installer.permissionIntent())
            } catch (_: ActivityNotFoundException) { model.installationFailed(UpdateFailure.INSTALLER) }
            catch (_: SecurityException) { model.installationFailed(UpdateFailure.INSTALLER) }
        }
    }
}
