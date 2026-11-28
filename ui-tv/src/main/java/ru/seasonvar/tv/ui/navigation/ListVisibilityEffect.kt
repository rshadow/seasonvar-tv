package ru.seasonvar.tv.ui.navigation

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun <T> ListVisibilityEffect(screen: T?, onVisible: ((T?) -> Unit)?) {
    if (onVisible == null) return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val callback by rememberUpdatedState(onVisible)
    DisposableEffect(lifecycle, screen) {
        fun report() { callback(screen.takeIf { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }) }
        val observer = LifecycleEventObserver { _, _ -> report() }
        lifecycle.addObserver(observer)
        report()
        onDispose { lifecycle.removeObserver(observer); callback(null) }
    }
}
