package ru.seasonvar.tv.ui.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.seasonvar.tv.diagnostics.failureSummary
import ru.seasonvar.tv.BuildConfig
import ru.seasonvar.tv.R
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.data.ResumePoint
import ru.seasonvar.tv.data.SiteHttpException
import ru.seasonvar.tv.data.playback.PlaybackRequest
import ru.seasonvar.tv.data.playback.VideoUnavailableException
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import java.io.IOException

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var request: PlaybackRequest? = null
    private var playback by mutableStateOf<ExoPlayer?>(null)
    private var loading by mutableStateOf(true)
    private var failure by mutableStateOf<Int?>(null)
    private var controlsVisible by mutableStateOf(false)
    private var view: PlayerView? = null
    private var resolveJob: Job? = null
    private var progressJob: Job? = null
    private var ready = false
    private val container get() = application as SeasonvarApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = readRequest(intent)
        if (request == null) { finish(); return }
        // Media3's diagnostic exceptions may contain temporary media URLs.
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
        setContent {
            SeasonvarTheme {
                val d = SeasonvarTheme.dimensions
                val retry = remember { FocusRequester() }
                val surface = remember { FocusRequester() }
                Box(Modifier.fillMaxSize().background(colorResource(R.color.seasonvar_player_background))
                    .focusRequester(surface).onPreviewKeyEvent { handlePlaybackKey(it.nativeKeyEvent) }.focusable()) {
                    AndroidView(factory = { context ->
                        object : PlayerView(context) {
                            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                                handlePlaybackKey(event) || super.dispatchKeyEvent(event)
                        }.apply {
                            setShowNextButton(false)
                            setShowPreviousButton(false)
                            findViewById<android.view.View>(androidx.media3.ui.R.id.exo_settings)?.visibility = android.view.View.GONE
                            controllerShowTimeoutMs = resources.getInteger(R.integer.seasonvar_player_controls_timeout_ms)
                            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { controlsVisible = it == android.view.View.VISIBLE })
                            view = this
                        }
                    }, update = { native ->
                        if (native.player !== playback) {
                            native.player = playback
                            if (playback != null) native.requestFocus()
                        }
                        native.useController = failure == null
                        native.isFocusable = failure == null
                    }, modifier = Modifier.fillMaxSize())
                    if (controlsVisible || loading || failure != null) Text(
                        stringResource(R.string.player_title, request!!.title, request!!.episodeLabel),
                        Modifier.align(Alignment.TopStart).padding(d.spacingLarge),
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (loading || failure != null) Column(Modifier.align(Alignment.Center).padding(d.spacingLarge),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
                        Text(stringResource(failure ?: R.string.player_loading), color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.titleLarge)
                        if (failure != null) ThemeButton(stringResource(R.string.retry), ::preparePlayback,
                            Modifier.focusRequester(retry).height(d.headerButtonSize))
                    }
                }
                LaunchedEffect(playback, failure) {
                    if (failure == null) { withFrameNanos { }; surface.requestFocus() }
                }
                LaunchedEffect(failure) {
                    if (failure != null) { repeat(2) { withFrameNanos { } }; retry.requestFocus() }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); preparePlayback() }

    private fun preparePlayback() {
        val selected = request ?: return
        trace("open season=" + selected.seasonId + " translation=" + selected.translationId + " episode=" + selected.episodeId)
        resolveJob?.cancel()
        releasePlayback()
        loading = true
        failure = null
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        resolveJob = lifecycleScope.launch {
            try {
                val point = container.playbackProgress.read(selected.seasonId)?.takeIf {
                    it.translationId == selected.translationId && it.episodeId == selected.episodeId
                }
                val source = container.videos.resolve(selected)
                ensureActive()
                val transport = OkHttpDataSource.Factory(container.siteClient.mediaCalls)
                    .setUserAgent("Mozilla/5.0 (Android TV) SeasonvarTV/" + BuildConfig.VERSION_NAME)
                    .setDefaultRequestProperties(mapOf("Referer" to selected.pageUrl))
                val player = ExoPlayer.Builder(this@PlayerActivity)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(transport))
                    .setSeekBackIncrementMs(resources.getInteger(R.integer.seasonvar_player_seek_ms).toLong())
                    .setSeekForwardIncrementMs(resources.getInteger(R.integer.seasonvar_player_seek_ms).toLong())
                    .build()
                player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                player.setHandleAudioBecomingNoisy(true)
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        loading = state == Player.STATE_BUFFERING
                        if (state == Player.STATE_READY) ready = true
                        if (state == Player.STATE_ENDED) savePosition()
                        trace("state=" + state + " positionMs=" + player.currentPosition)
                    }
                    override fun onRenderedFirstFrame() { trace("first-frame positionMs=" + player.currentPosition) }
                    override fun onPlayerError(error: PlaybackException) {
                        loading = false
                        failure = R.string.player_playback_error
                        trace("error=" + error.errorCodeName + " " + failureSummary(error))
                    }
                })
                playback = player
                player.setMediaItem(MediaItem.fromUri(source.url))
                if (point != null) player.seekTo(point.positionMillis)
                player.prepare()
                player.playWhenReady = true
                trace("prepared resumeMs=" + (point?.positionMillis ?: 0))
                progressJob = lifecycleScope.launch {
                    while (isActive) { delay(resources.getInteger(R.integer.seasonvar_player_progress_interval_ms).toLong()); savePosition() }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                loading = false
                failure = when (e) {
                    is IOException -> R.string.player_network_error
                    is SiteHttpException -> R.string.player_http_error
                    is VideoUnavailableException -> R.string.player_unavailable
                    else -> R.string.player_source_error
                }
                trace("resolve-error=" + failureSummary(e))
            }
        }
    }

    private fun savePosition() {
        val player = playback ?: return
        val selected = request ?: return
        if (!ready) return
        val position = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition.coerceAtLeast(0)
        container.playbackProgress.save(ResumePoint(selected.seasonId, selected.translationId, selected.episodeId, position))
        trace("saved positionMs=" + position)
    }

    private fun releasePlayback() {
        progressJob?.cancel()
        savePosition()
        view?.player = null
        playback?.release()
        playback = null
        ready = false
    }

    override fun onPause() {
        savePosition()
        playback?.pause()
        super.onPause()
    }
    override fun onStop() {
        resolveJob?.cancel()
        releasePlayback()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    private fun handlePlaybackKey(event: KeyEvent): Boolean {
        if (event.keyCode in setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE)) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) finish()
            return true
        }
        val player = playback
        if (player != null && failure == null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        if (player.playWhenReady) { player.pause(); savePosition() } else player.play()
                        view?.showController()
                        trace("toggle playing=" + player.playWhenReady)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val step = resources.getInteger(R.integer.seasonvar_player_seek_ms).toLong()
                        val backwards = event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND)
                        val maximum = if (player.duration > 0) player.duration else Long.MAX_VALUE
                        player.seekTo((player.currentPosition + if (backwards) -step else step).coerceIn(0, maximum))
                        view?.showController()
                        trace("seek positionMs=" + player.currentPosition)
                    }
                    return true
                }
            }
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handlePlaybackKey(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        handlePlaybackKey(event) || super.onKeyUp(keyCode, event)

    private fun trace(message: String) { if (BuildConfig.DEBUG) android.util.Log.d("SeasonvarPlayback", message) }

    companion object {
        fun intent(context: Context, request: PlaybackRequest): Intent = Intent(context, PlayerActivity::class.java)
            .putExtra("season", request.seasonId).putExtra("page", request.pageUrl)
            .putExtra("translation", request.translationId).putExtra("episode", request.episodeId)
            .putExtra("label", request.episodeLabel).putExtra("title", request.title)

        private fun readRequest(intent: Intent): PlaybackRequest? {
            val season = intent.getStringExtra("season") ?: return null
            val page = intent.getStringExtra("page") ?: return null
            val translation = intent.getStringExtra("translation") ?: return null
            val episode = intent.getStringExtra("episode") ?: return null
            val label = intent.getStringExtra("label") ?: return null
            return PlaybackRequest(season, page, translation, episode, label, intent.getStringExtra("title").orEmpty())
        }
    }
}
