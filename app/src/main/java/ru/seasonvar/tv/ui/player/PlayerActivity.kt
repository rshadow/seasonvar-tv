package ru.seasonvar.tv.ui.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import ru.seasonvar.tv.data.playback.AccountMarkPolicy
import ru.seasonvar.tv.data.playback.MarkEvent
import ru.seasonvar.tv.data.playback.PlaybackSample
import ru.seasonvar.tv.data.playback.MarkSyncPhase
import ru.seasonvar.tv.data.playback.markOwner
import ru.seasonvar.tv.data.playback.PlaybackRequest
import ru.seasonvar.tv.data.playback.playbackEpisodes
import ru.seasonvar.tv.data.playback.VideoUnavailableException
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import java.io.IOException

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var request by mutableStateOf<PlaybackRequest?>(null)
    private var controlsPlayer by mutableStateOf<Player?>(null)
    private var navigatingControls = false
    private var playback by mutableStateOf<ExoPlayer?>(null)
    private var loading by mutableStateOf(true)
    private var failure by mutableStateOf<Int?>(null)
    private var controlsVisible by mutableStateOf(false)
    private var view: PlayerView? = null
    private var resolveJob: Job? = null
    private var progressJob: Job? = null
    private var ready = false
    private var markPolicy: AccountMarkPolicy? = null
    private var markOwnerVersion: Long? = null
    private val container get() = application as SeasonvarApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = readRequest(intent)
        request = savedInstanceState?.getString("current_episode")?.let { initial?.selectEpisode(it) } ?: initial
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
                val markSync by container.accountMarks.state.collectAsStateWithLifecycle()
                val accountSession by container.account.session.collectAsStateWithLifecycle()
                val retry = remember { FocusRequester() }
                val surface = remember { FocusRequester() }
                Box(Modifier.fillMaxSize().background(colorResource(R.color.seasonvar_player_background))
                    .focusRequester(surface).onPreviewKeyEvent { handlePlaybackKey(it.nativeKeyEvent) }.focusable()) {
                    AndroidView(factory = { context ->
                        object : PlayerView(context) {
                            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                                val handled = handlePlaybackKey(event) || super.dispatchKeyEvent(event)
                                if (navigatingControls) traceControls()
                                return handled
                            }
                        }.apply {
                            setShowNextButton(true)
                            setShowPreviousButton(true)
                            findViewById<android.view.View>(androidx.media3.ui.R.id.exo_settings)?.visibility = android.view.View.GONE
                            controllerShowTimeoutMs = resources.getInteger(R.integer.seasonvar_player_controls_timeout_ms)
                            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                            findViewById<View>(androidx.media3.ui.R.id.exo_prev)?.contentDescription = getString(R.string.player_previous_episode)
                            findViewById<View>(androidx.media3.ui.R.id.exo_next)?.contentDescription = getString(R.string.player_next_episode)
                            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener {
                                controlsVisible = it == View.VISIBLE
                                if (!controlsVisible && navigatingControls) {
                                    navigatingControls = false
                                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                                    requestFocus()
                                }
                            })
                            view = this
                        }
                    }, update = { native ->
                        if (native.player !== controlsPlayer) {
                            native.player = controlsPlayer
                            if (controlsPlayer != null) native.requestFocus()
                        }
                        native.useController = failure == null
                        native.isFocusable = failure == null
                    }, modifier = Modifier.fillMaxSize())
                    if (controlsVisible || loading || failure != null) Column(
                        Modifier.align(Alignment.TopStart).padding(d.spacingLarge),
                    ) {
                        Text(stringResource(R.string.player_title, request!!.title, request!!.episodeLabel),
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.titleMedium)
                        val syncMessage = when {
                            accountSession.expired -> R.string.account_expired
                            markSync.owner != markOwner(accountSession.user) -> null
                            markSync.phase == MarkSyncPhase.RETRYING -> R.string.player_progress_sync_retry
                            markSync.phase == MarkSyncPhase.FAILED -> R.string.player_progress_sync_error
                            else -> null
                        }
                        if (syncMessage != null) Text(stringResource(syncMessage),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (loading || failure != null) Column(Modifier.align(Alignment.Center).padding(d.spacingLarge),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
                        Text(stringResource(failure ?: R.string.player_loading), color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.titleLarge)
                        if (failure != null) Row(horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                            if (request?.adjacent(-1) != null) ThemeButton(stringResource(R.string.player_previous_episode),
                                { switchEpisode(-1) }, Modifier.height(d.headerButtonSize))
                            ThemeButton(stringResource(R.string.retry), { preparePlayback() },
                                Modifier.focusRequester(retry).height(d.headerButtonSize))
                            if (request?.adjacent(1) != null) ThemeButton(stringResource(R.string.player_next_episode),
                                { switchEpisode(1) }, Modifier.height(d.headerButtonSize))
                        }
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

    private fun switchEpisode(direction: Int) {
        val next = request?.adjacent(direction) ?: return
        preparePlayback(next, resume = false)
    }

    private fun preparePlayback(selected: PlaybackRequest? = request, resume: Boolean = true) {
        selected ?: return
        trace("open season=" + selected.seasonId + " translation=" + selected.translationId + " episode=" + selected.episodeId)
        resolveJob?.cancel()
        releasePlayback()
        request = selected
        intent = intent(this, selected)
        loading = true
        failure = null
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        resolveJob = lifecycleScope.launch {
            try {
                val point = if (!resume) null else container.playbackProgress.read(selected.seasonId)?.takeIf {
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
                        if (playback !== player) return
                        loading = state == Player.STATE_BUFFERING
                        if (state == Player.STATE_READY) ready = true
                        if (state == Player.STATE_ENDED) savePosition(MarkEvent.ENDED)
                        else sampleAccountMark()
                        trace("state=" + state + " positionMs=" + player.currentPosition)
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (playback === player) sampleAccountMark()
                    }
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        if (playback !== player) return
                        if (!playWhenReady) savePosition(MarkEvent.PAUSE) else sampleAccountMark()
                    }
                    override fun onRenderedFirstFrame() {
                        if (playback === player) trace("first-frame positionMs=" + player.currentPosition)
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        if (playback !== player) return
                        loading = false
                        failure = R.string.player_playback_error
                        trace("error=" + error.errorCodeName + " " + failureSummary(error))
                    }
                })
                playback = player
                val account = container.account.session.value
                markPolicy = if (markOwner(account.user) != null) AccountMarkPolicy(selected) else null
                markOwnerVersion = account.version
                player.setMediaItem(MediaItem.fromUri(source.url))
                controlsPlayer = EpisodeControlsPlayer(player, selected, ::switchEpisode)
                if (point != null) player.seekTo(point.positionMillis)
                player.prepare()
                player.playWhenReady = true
                trace("prepared resumeMs=" + (point?.positionMillis ?: 0))
                progressJob = lifecycleScope.launch {
                    while (isActive) { delay(resources.getInteger(R.integer.seasonvar_player_progress_interval_ms).toLong()); savePosition() }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                ensureActive()
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

    private fun savePosition(event: MarkEvent = MarkEvent.SAMPLE) {
        val player = playback ?: return
        val selected = request ?: return
        if (!ready) return
        val position = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition.coerceAtLeast(0)
        container.playbackProgress.save(ResumePoint(selected.seasonId, selected.translationId, selected.episodeId, position))
        trace("saved positionMs=" + position)
        sampleAccountMark(event)
    }

    private fun sampleAccountMark(event: MarkEvent = MarkEvent.SAMPLE) {
        val player = playback ?: return
        val version = markOwnerVersion ?: return
        if (!ready || container.account.session.value.version != version) return
        val mark = markPolicy?.sample(PlaybackSample(player.currentPosition.coerceAtLeast(0), player.duration,
            player.isPlaying, SystemClock.elapsedRealtime()), event) ?: return
        container.accountMarks.enqueue(mark, version)
    }

    private fun releasePlayback() {
        progressJob?.cancel()
        savePosition(MarkEvent.STOP)
        navigatingControls = false
        view?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        view?.player = null
        controlsPlayer = null
        val previous = playback
        playback = null
        ready = false
        markPolicy = null
        markOwnerVersion = null
        previous?.release()
    }

    override fun onPause() {
        savePosition(MarkEvent.STOP)
        playback?.pause()
        super.onPause()
    }
    override fun onStop() {
        resolveJob?.cancel()
        releasePlayback()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun finish() {
        savePosition(MarkEvent.STOP)
        request?.let { selected ->
            setResult(RESULT_OK, Intent().putExtra("season", selected.seasonId)
                .putExtra("translation", selected.translationId).putExtra("episode", selected.episodeId))
        }
        super.finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        request?.let { outState.putString("current_episode", it.episodeId) }
        super.onSaveInstanceState(outState)
    }

    private fun handlePlaybackKey(event: KeyEvent): Boolean {
        if (event.keyCode in setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE)) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) finish()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || event.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                switchEpisode(if (event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) 1 else -1)
            }
            return true
        }
        val player = playback
        if (player != null && failure == null) {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    navigatingControls = true
                    view?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                    view?.showController()
                    view?.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus()
                    traceControls()
                }
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && navigatingControls) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    navigatingControls = false
                    view?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    view?.hideController()
                    view?.requestFocus()
                }
                return true
            }
            if (navigatingControls && event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) return false
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        if (player.playWhenReady) { player.pause(); savePosition(MarkEvent.PAUSE) } else player.play()
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

    private fun traceControls() {
        if (!BuildConfig.DEBUG) return
        val focus = when (view?.findFocus()?.id) {
            androidx.media3.ui.R.id.exo_prev -> "previous"
            androidx.media3.ui.R.id.exo_next -> "next"
            androidx.media3.ui.R.id.exo_play_pause -> "play_pause"
            androidx.media3.ui.R.id.exo_rew -> "rewind"
            androidx.media3.ui.R.id.exo_ffwd -> "forward"
            else -> "video"
        }
        trace("controls previous=" + (view?.findViewById<View>(androidx.media3.ui.R.id.exo_prev)?.isEnabled == true) +
            " next=" + (view?.findViewById<View>(androidx.media3.ui.R.id.exo_next)?.isEnabled == true) + " focus=" + focus)
    }

    private fun trace(message: String) { if (BuildConfig.DEBUG) android.util.Log.d("SeasonvarPlayback", message) }

    companion object {
        fun intent(context: Context, request: PlaybackRequest): Intent = Intent(context, PlayerActivity::class.java)
            .putExtra("season", request.seasonId).putExtra("page", request.pageUrl)
            .putExtra("translation", request.translationId).putExtra("episode", request.episodeId)
            .putExtra("label", request.episodeLabel).putExtra("title", request.title)
            .putStringArrayListExtra("episode_ids", ArrayList(request.episodes.map { it.id }))
            .putStringArrayListExtra("episode_labels", ArrayList(request.episodes.map { it.label }))

        private fun readRequest(intent: Intent): PlaybackRequest? {
            val season = intent.getStringExtra("season") ?: return null
            val page = intent.getStringExtra("page") ?: return null
            val translation = intent.getStringExtra("translation") ?: return null
            val episode = intent.getStringExtra("episode") ?: return null
            val label = intent.getStringExtra("label") ?: return null
            return PlaybackRequest(season, page, translation, episode, label, intent.getStringExtra("title").orEmpty(),
                playbackEpisodes(intent.getStringArrayListExtra("episode_ids"), intent.getStringArrayListExtra("episode_labels")))
        }
    }
}
