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
import ru.seasonvar.tv.data.PublicFeed
import ru.seasonvar.tv.ui.SeasonvarApp
import ru.seasonvar.tv.ui.navigation.traceBackKey
import ru.seasonvar.tv.ui.catalog.CatalogViewModel

class MainActivity : ComponentActivity() {
    private lateinit var models: Map<PublicFeed, CatalogViewModel>

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
        models = PublicFeed.entries.associateWith { feed ->
            ViewModelProvider(this, CatalogViewModel.Factory(repository, feed))
                .get("catalog-" + feed.name, CatalogViewModel::class.java)
        }
        setContent {
            val today by models.getValue(PublicFeed.TODAY).state.collectAsStateWithLifecycle()
            val updates by models.getValue(PublicFeed.UPDATES).state.collectAsStateWithLifecycle()
            val todayDescription by models.getValue(PublicFeed.TODAY).description.collectAsStateWithLifecycle()
            val updatesDescription by models.getValue(PublicFeed.UPDATES).description.collectAsStateWithLifecycle()
            val network by container.networkSettings.configuration.collectAsStateWithLifecycle()
            SeasonvarApp(
                states = mapOf(PublicFeed.TODAY to today, PublicFeed.UPDATES to updates),
                descriptions = mapOf(PublicFeed.TODAY to todayDescription, PublicFeed.UPDATES to updatesDescription),
                onRefresh = { feed -> models.getValue(feed).refresh() },
                onSelect = { feed, entry, retry -> models.getValue(feed).select(entry, retry) },
                onFeedVisible = { feed -> models.getValue(feed).activate() },
                bypassEnabled = network.bypassEnabled,
                onBypassChange = { enabled ->
                    container.networkSettings.setBypassEnabled(enabled)
                    models.values.forEach { it.networkChanged() }
                },
                onExit = ::finishAndRemoveTask,
                dnsProvider = network.dnsProvider,
                onDnsChange = { provider ->
                    if (provider != network.dnsProvider) {
                        container.networkSettings.setDnsProvider(provider)
                        models.values.forEach { it.networkChanged() }
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
        models.values.forEach { it.refreshIfDateChanged() }
    }
}
