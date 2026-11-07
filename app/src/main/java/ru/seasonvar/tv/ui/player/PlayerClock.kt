package ru.seasonvar.tv.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ru.seasonvar.tv.R
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import java.time.ZoneId

@Composable
internal fun PlayerClock(position: PlayerClockPosition, modifier: Modifier = Modifier) {
    if (position == PlayerClockPosition.OFF) return
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var time by remember { mutableStateOf(formatPlayerClock(System.currentTimeMillis(), ZoneId.systemDefault())) }
    DisposableEffect(context, owner) {
        fun refresh() { time = formatPlayerClock(System.currentTimeMillis(), ZoneId.systemDefault()) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { refresh() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        var registered = false
        fun unregister() {
            if (registered) { context.unregisterReceiver(receiver); registered = false }
        }
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                refresh()
                ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                registered = true
            }
            override fun onStop(owner: LifecycleOwner) { unregister() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); unregister() }
    }
    val d = SeasonvarTheme.dimensions
    val description = stringResource(R.string.player_clock_accessibility, time)
    Text(time, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground, maxLines = 1,
            modifier = modifier.background(colorResource(R.color.seasonvar_player_clock_background), RoundedCornerShape(d.buttonCornerRadius))
                .padding(horizontal = d.spacingSmall, vertical = d.playerClockPaddingVertical)
                .semantics { contentDescription = description })
}
