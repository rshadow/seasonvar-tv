package ru.seasonvar.tv.ui.navigation

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

internal fun Modifier.backNavigationKeys(
    onBack: () -> Unit,
    onInteraction: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    if (event.key == Key.Escape || event.key == Key.Back) {
        traceBackKey(event.nativeKeyEvent, "compose")
        if (event.type == KeyEventType.KeyUp && !event.nativeKeyEvent.isCanceled) onBack()
        true
    } else {
        if (event.type == KeyEventType.KeyDown) onInteraction()
        false
    }
}

internal fun traceBackKey(event: android.view.KeyEvent, owner: String) {
    if (ru.seasonvar.tv.BuildConfig.DEBUG &&
        event.keyCode in setOf(android.view.KeyEvent.KEYCODE_ESCAPE, android.view.KeyEvent.KEYCODE_BACK)
    ) {
        android.util.Log.d("SeasonvarInput",
            owner + " key=" + event.keyCode + " action=" + event.action +
                " repeat=" + event.repeatCount + " canceled=" + event.isCanceled)
    }
}
