package ru.seasonvar.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { SeasonvarApp() }
    }
}

@Composable
private fun SeasonvarApp() {
    var aboutVisible by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = aboutVisible) { aboutVisible = false }

    SeasonvarTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(
                    horizontal = SeasonvarTheme.dimensions.screenPaddingHorizontal,
                    vertical = SeasonvarTheme.dimensions.screenPaddingVertical,
                ),
            verticalArrangement = Arrangement.Center,
        ) {
            if (aboutVisible) {
                AboutScreen(onBack = { aboutVisible = false })
            } else {
                MenuScreen(onAbout = { aboutVisible = true })
            }
        }
    }
}

@Composable
private fun MenuScreen(onAbout: () -> Unit) {
    Text(
        text = stringResource(R.string.app_name),
        style = MaterialTheme.typography.displayMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(SeasonvarTheme.dimensions.spacingSection))
    Text(
        text = stringResource(R.string.menu_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(SeasonvarTheme.dimensions.spacingMedium))
    InitialFocusButton(
        text = stringResource(R.string.about_title),
        onClick = onAbout,
    )
}

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    Text(
        text = stringResource(R.string.about_title),
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(SeasonvarTheme.dimensions.spacingLarge))
    Text(
        text = stringResource(R.string.app_name),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(SeasonvarTheme.dimensions.spacingSmall))
    Text(
        text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(SeasonvarTheme.dimensions.spacingMedium))
    Text(
        text = stringResource(R.string.about_description),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(SeasonvarTheme.dimensions.spacingSmall))
    Text(
        text = stringResource(R.string.about_source),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(SeasonvarTheme.dimensions.spacingExtraLarge))
    InitialFocusButton(text = stringResource(R.string.back), onClick = onBack)
}

@Composable
private fun InitialFocusButton(text: String, onClick: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Button(
        onClick = onClick,
        modifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .pointerInput(onClick) {
                detectTapGestures {
                    focusRequester.requestFocus()
                    onClick()
                }
            }
            .width(SeasonvarTheme.dimensions.buttonWidth)
            .height(SeasonvarTheme.dimensions.buttonHeight),
        colors = ButtonDefaults.colors(
            containerColor = if (focused) colors.primary else colors.surface,
            contentColor = if (focused) colors.onPrimary else colors.onSurface,
            focusedContainerColor = colors.primary,
            focusedContentColor = colors.onPrimary,
        ),
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium)
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Preview(device = "spec:width=960dp,height=540dp,dpi=320", showBackground = true)
@Composable
private fun MenuPreview() {
    SeasonvarApp()
}
