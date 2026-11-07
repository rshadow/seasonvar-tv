package ru.seasonvar.tv.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ru.seasonvar.tv.R
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.data.SiteHttpException
import ru.seasonvar.tv.data.account.SessionExpiredException
import ru.seasonvar.tv.data.home.HomeLink
import ru.seasonvar.tv.data.home.HomePlaybackUnavailable
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.player.PlayerActivity
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

class HomePlaybackActivity : ComponentActivity() {
    private var failure by mutableStateOf<Int?>(null)
    private var launched = false
    private val player = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { finish() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launched = savedInstanceState?.getBoolean("launched") == true
        setContent {
            SeasonvarTheme {
                val d = SeasonvarTheme.dimensions
                val retry = remember { FocusRequester() }
                BackHandler { finish() }
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(d.spacingLarge),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(stringResource(failure ?: R.string.home_play_loading), color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.titleLarge)
                    if (failure != null) {
                        Spacer(Modifier.height(d.spacingLarge))
                        ThemeButton(stringResource(R.string.retry), ::open, Modifier.focusRequester(retry))
                        LaunchedEffect(failure) { retry.requestFocus() }
                    }
                }
            }
        }
        if (!launched) open()
    }
    private fun open() {
        failure = null
        lifecycleScope.launch {
            try {
                val token = HomeLink.parse(intent.dataString) ?: throw HomePlaybackUnavailable()
                val request = (application as SeasonvarApplication).homeChannels.open(token)
                launched = true
                player.launch(PlayerActivity.intent(this@HomePlaybackActivity, request))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                failure = when (error) {
                    is SessionExpiredException -> R.string.account_expired
                    is java.io.IOException -> R.string.today_network_error
                    is SiteHttpException -> R.string.today_http_error
                    is HomePlaybackUnavailable -> R.string.home_play_unavailable
                    else -> R.string.home_play_error
                }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("launched", launched); super.onSaveInstanceState(outState) }
    companion object {
        fun intent(context: Context, token: String): Intent = Intent(Intent.ACTION_VIEW,
            Uri.parse(HomeLink.uri(token)), context, HomePlaybackActivity::class.java)
    }
}
