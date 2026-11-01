package ru.seasonvar.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.seasonvar.tv.ui.SeasonvarApp
import ru.seasonvar.tv.ui.navigation.traceBackKey
import ru.seasonvar.tv.ui.today.TodayViewModel

class MainActivity : ComponentActivity() {
    private lateinit var model: TodayViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val container = application as SeasonvarApplication
        val repository = container.repository
        model = ViewModelProvider(this, TodayViewModel.Factory(repository))[TodayViewModel::class.java]
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val description by model.description.collectAsStateWithLifecycle()
            val network by container.networkSettings.configuration.collectAsStateWithLifecycle()
            SeasonvarApp(
                state, description, model::refresh, model::select,
                bypassEnabled = network.bypassEnabled,
                onBypassChange = { enabled ->
                    container.networkSettings.setBypassEnabled(enabled)
                    model.networkChanged()
                },
                onExit = ::finishAndRemoveTask,
                dnsProvider = network.dnsProvider,
                onDnsChange = { provider ->
                    if (provider != network.dnsProvider) {
                        container.networkSettings.setDnsProvider(provider)
                        model.networkChanged()
                    }
                },
            )
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        traceBackKey(event, "activity")
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        traceBackKey(event, "activity")
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (!event.isCanceled) onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        model.refreshIfDateChanged()
    }
}
