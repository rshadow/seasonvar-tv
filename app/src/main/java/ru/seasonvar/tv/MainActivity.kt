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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme

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

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFC2EC7B),
            background = Color(0xFF11151C),
            onBackground = Color(0xFFF2F4F7),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 64.dp, vertical = 48.dp),
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
    Spacer(Modifier.height(40.dp))
    Text(
        text = stringResource(R.string.menu_title),
        style = MaterialTheme.typography.titleMedium,
        color = Color(0xFFA5ADBA),
    )
    Spacer(Modifier.height(20.dp))
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
    Spacer(Modifier.height(24.dp))
    Text(
        text = stringResource(R.string.app_name),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
        style = MaterialTheme.typography.bodyLarge,
        color = Color(0xFFA5ADBA),
    )
    Spacer(Modifier.height(20.dp))
    Text(
        text = stringResource(R.string.about_description),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.about_source),
        style = MaterialTheme.typography.bodyMedium,
        color = Color(0xFFA5ADBA),
    )
    Spacer(Modifier.height(32.dp))
    InitialFocusButton(text = stringResource(R.string.back), onClick = onBack)
}

@Composable
private fun InitialFocusButton(text: String, onClick: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
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
            .width(320.dp)
            .height(64.dp),
        colors = ButtonDefaults.colors(
            containerColor = if (focused) Color(0xFFC2EC7B) else Color(0xFF262E3B),
            contentColor = if (focused) Color(0xFF11151C) else Color(0xFFF2F4F7),
            focusedContainerColor = Color(0xFFC2EC7B),
            focusedContentColor = Color(0xFF11151C),
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
