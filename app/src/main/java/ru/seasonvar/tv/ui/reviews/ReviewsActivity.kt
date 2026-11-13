package ru.seasonvar.tv.ui.reviews

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.reviews.ReviewsRequest
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

class ReviewsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entry = SeriesEntry(intent.getStringExtra("season_id").orEmpty(), intent.getStringExtra("season_url").orEmpty(),
            intent.getStringExtra("series_title").orEmpty(), null, "", "")
        if (runCatching { ReviewsRequest.page(entry) }.isFailure) { finish(); return }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val container = application as SeasonvarApplication
        val model = ViewModelProvider(this, ReviewsViewModel.Factory(container.reviews, entry))[ReviewsViewModel::class.java]
        setContent {
            SeasonvarTheme {
                val state by model.state.collectAsStateWithLifecycle()
                val account by container.account.session.collectAsStateWithLifecycle()
                val network by container.networkSettings.configuration.collectAsStateWithLifecycle()
                var previousAccount by remember { mutableLongStateOf(account.version) }
                LaunchedEffect(account.version, network) {
                    model.refresh(clear = previousAccount != account.version)
                    previousAccount = account.version
                }
                ReviewsScreen(entry.title, state, { model.refresh(force = true, preserveKey = it) }, model::more, ::finish)
            }
        }
    }

    companion object {
        fun open(context: Context, entry: SeriesEntry) {
            context.startActivity(Intent(context, ReviewsActivity::class.java).putExtra("season_id", entry.id)
                .putExtra("season_url", entry.url).putExtra("series_title", entry.title))
        }
    }
}
