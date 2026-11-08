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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
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
import kotlinx.coroutines.yield
import ru.seasonvar.tv.diagnostics.failureSummary
import ru.seasonvar.tv.BuildConfig
import ru.seasonvar.tv.R
import ru.seasonvar.tv.ui.ShortMessageEffect
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
    private var autoNextJob: Job? = null
    private var seasonTransitionPending by mutableStateOf(false)
    private var completionVisible by mutableStateOf(false)
    private var markPolicy: AccountMarkPolicy? = null
    private var markOwnerVersion: Long? = null
    private var historyStart = ru.seasonvar.tv.data.playback.PlaybackHistoryStart()
    private val container get() = application as SeasonvarApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = readRequest(intent)
        request = savedInstanceState?.getString("current_episode")?.let { initial?.selectEpisode(it) } ?: initial
        historyStart.submitted = savedInstanceState?.getBoolean("history_submitted") ?: false
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
        lifecycleScope.launch {
            container.interfaceSettings.autoNextEnabled.collect { enabled ->
                if (!enabled && request?.adjacent(1) != null) cancelAutoNext()
            }
        }
        lifecycleScope.launch {
            container.interfaceSettings.autoNextSeasonEnabled.collect { enabled ->
                if (!enabled && seasonTransitionPending) {
                    cancelAutoNext()
                    if (playback?.playbackState == Player.STATE_ENDED) showCompletion()
                }
            }
        }
        setContent {
            SeasonvarTheme {
                val d = SeasonvarTheme.dimensions
                val markSync by container.accountMarks.state.collectAsStateWithLifecycle()
                val accountSession by container.account.session.collectAsStateWithLifecycle()
                val clockPosition by container.interfaceSettings.playerClockPosition.collectAsStateWithLifecycle()
                val syncMessage = when {
                    accountSession.expired -> R.string.account_expired
                    markSync.owner != markOwner(accountSession.user) -> null
                    markSync.phase == MarkSyncPhase.RETRYING -> R.string.player_progress_sync_retry
                    markSync.phase == MarkSyncPhase.FAILED -> R.string.player_progress_sync_error
                    else -> null
                }
                ShortMessageEffect(syncMessage?.let { stringResource(it) })
                val retry = remember { FocusRequester() }
                val surface = remember { FocusRequester() }
                val cancelNext = remember { FocusRequester() }
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
                                if (it == View.VISIBLE && completionVisible) hideController()
                                else {
                                    controlsVisible = it == View.VISIBLE
                                    if (!controlsVisible && navigatingControls) {
                                        navigatingControls = false
                                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                                        requestFocus()
                                    }
                                }
                            })
                            view = this
                        }
                    }, update = { native ->
                        if (native.player !== controlsPlayer) {
                            native.player = controlsPlayer
                            if (controlsPlayer != null) native.requestFocus()
                        }
                        native.useController = failure == null && !completionVisible && !seasonTransitionPending
                        native.isFocusable = failure == null && !completionVisible && !seasonTransitionPending
                    }, modifier = Modifier.fillMaxSize())
                    Column(
                        Modifier.align(Alignment.TopStart).fillMaxWidth().padding(d.spacingLarge),
                    ) {
                        val titleVisible = controlsVisible || loading || failure != null
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            if (clockPosition == PlayerClockPosition.LEFT) {
                                PlayerClock(clockPosition, Modifier.alignByBaseline())
                                if (titleVisible) Spacer(Modifier.width(d.spacingMedium))
                            }
                            if (titleVisible) {
                                val selected = checkNotNull(request)
                                val title = selected.seasonNumber?.let { season ->
                                    stringResource(R.string.player_title_with_season, selected.title, season, selected.episodeLabel)
                                } ?: stringResource(R.string.player_title, selected.title, selected.episodeLabel)
                                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).alignByBaseline().then(
                                        if (clockPosition == PlayerClockPosition.CENTER || clockPosition == PlayerClockPosition.RIGHT)
                                            Modifier.padding(end = d.spacingMedium) else Modifier),
                                    color = MaterialTheme.colorScheme.onBackground,
                                    style = MaterialTheme.typography.titleMedium)
                            } else Spacer(Modifier.weight(1f))
                            if (clockPosition == PlayerClockPosition.CENTER || clockPosition == PlayerClockPosition.RIGHT) {
                                PlayerClock(clockPosition, Modifier.alignByBaseline())
                            }
                            if (clockPosition == PlayerClockPosition.CENTER) Spacer(Modifier.weight(1f))
                        }
                    }
                    if (completionVisible) {
                        Column(Modifier.align(Alignment.BottomEnd).padding(d.spacingLarge).width(d.playerAutoNextWidth)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(d.cardCornerRadius))
                            .padding(d.spacingMedium), verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                            Text(stringResource(R.string.player_auto_next_none),
                                color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                            ThemeButton(stringResource(R.string.player_auto_next_close), { finish() },
                                Modifier.focusRequester(cancelNext).focusProperties {
                                    left = FocusRequester.Cancel; right = FocusRequester.Cancel
                                    up = FocusRequester.Cancel; down = FocusRequester.Cancel
                                })
                        }
                    }
                    if (loading || failure != null) Column(Modifier.align(Alignment.Center).padding(d.spacingLarge),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
                        Text(stringResource(failure ?: R.string.player_loading), color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.titleLarge)
                        if (failure != null) Row(horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                            if (request?.adjacent(-1) != null) ThemeButton(stringResource(R.string.player_previous_episode),
                                { switchEpisode(-1) }, Modifier.height(d.headerButtonSize))
                            ThemeButton(stringResource(R.string.retry), { retryPlayback() },
                                Modifier.focusRequester(retry).height(d.headerButtonSize))
                            if (request?.adjacent(1) != null) ThemeButton(stringResource(R.string.player_next_episode),
                                { switchEpisode(1) }, Modifier.height(d.headerButtonSize))
                        }
                    }
                }
                LaunchedEffect(playback, failure, completionVisible) {
                    if (completionVisible) {
                        repeat(2) { withFrameNanos { } }
                        cancelNext.requestFocus()
                    }
                    else if (failure == null) { withFrameNanos { }; surface.requestFocus() }
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
        if (!resume) historyStart = ru.seasonvar.tv.data.playback.PlaybackHistoryStart()
        historyStart.resetSurface()
        val historyGate = historyStart
        val historyAccount = container.account.session.value
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
                val position = if (!resume) null else selected.resumePositionMillis ?: container.playbackProgress.read(selected.seasonId)?.takeIf {
                    it.translationId == selected.translationId && it.episodeId == selected.episodeId
                }?.positionMillis
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
                        if (state == Player.STATE_ENDED) {
                            savePosition(MarkEvent.ENDED)
                            startAutoNext(player)
                        } else {
                            cancelAutoNext()
                            sampleAccountMark()
                        }
                        trace("state=" + state + " positionMs=" + player.currentPosition)
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (playback === player) {
                            sampleAccountMark()
                            if (historyGate.playing(isPlaying)) source.history?.let {
                                container.playbackHistory.enqueue(it, historyAccount.version)
                            }
                        }
                    }
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        if (playback !== player) return
                        if (!playWhenReady) savePosition(MarkEvent.PAUSE) else sampleAccountMark()
                    }
                    override fun onRenderedFirstFrame() {
                        if (playback === player) {
                            trace("first-frame positionMs=" + player.currentPosition)
                            if (historyGate.firstFrame(player.isPlaying)) source.history?.let {
                                container.playbackHistory.enqueue(it, historyAccount.version)
                            }
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        if (playback !== player) return
                        loading = false
                        cancelAutoNext()
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
                if (position != null) player.seekTo(position.coerceAtLeast(0))
                player.prepare()
                player.playWhenReady = true
                if (selected.resumePositionMillis != null) {
                    request = selected.copy(resumePositionMillis = null)
                    intent = intent(this@PlayerActivity, checkNotNull(request))
                }
                trace("prepared resumeMs=" + (position ?: 0))
                progressJob = lifecycleScope.launch {
                    while (isActive) { delay(resources.getInteger(R.integer.seasonvar_player_progress_interval_ms).toLong()); savePosition() }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                ensureActive()
                loading = false
                failure = resolveFailure(e)
                trace("resolve-error=" + failureSummary(e))
            }
        }
    }

    private fun savePosition(event: MarkEvent = MarkEvent.SAMPLE) {
        val player = playback ?: return
        val selected = request ?: return
        if (!ready) return
        val position = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition.coerceAtLeast(0)
        container.playbackProgress.save(ResumePoint(selected.seasonId, selected.translationId, selected.episodeId, position),
            completed = player.playbackState == Player.STATE_ENDED)
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

    private fun startAutoNext(player: ExoPlayer) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val current = request ?: return
        if (current.adjacent(1) == null) {
            if (container.interfaceSettings.autoNextSeasonEnabled.value) startNextSeason(player, current)
            else showCompletion()
            return
        }
        if (!container.interfaceSettings.autoNextEnabled.value || autoNextJob?.isActive == true) return
        autoNextJob = lifecycleScope.launch {
            yield()
            if (playback === player && player.playbackState == Player.STATE_ENDED && failure == null &&
                container.interfaceSettings.autoNextEnabled.value && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                switchEpisode(1)
            }
        }
    }

    private fun startNextSeason(player: ExoPlayer, source: PlaybackRequest) {
        if (autoNextJob?.isActive == true) return
        seasonTransitionPending = true
        loading = true
        failure = null
        completionVisible = false
        autoNextJob = lifecycleScope.launch {
            try {
                val next = container.nextSeasons.next(source)
                ensureActive()
                if (playback !== player || !container.interfaceSettings.autoNextSeasonEnabled.value ||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                seasonTransitionPending = false
                if (next == null) showCompletion()
                else {
                    autoNextJob = null
                    preparePlayback(next, resume = false)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                ensureActive()
                loading = false
                failure = resolveFailure(e)
                trace("season-error=" + failureSummary(e))
            }
        }
    }

    private fun retryPlayback() {
        val player = playback
        val source = request
        if (seasonTransitionPending && player != null && source != null) startNextSeason(player, source)
        else preparePlayback()
    }

    private fun showCompletion() {
        failure = null
        loading = false
        completionVisible = true
        navigatingControls = false
        view?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        view?.hideController()
    }

    private fun resolveFailure(error: Exception): Int = when (error) {
        is IOException -> R.string.player_network_error
        is SiteHttpException -> R.string.player_http_error
        is VideoUnavailableException -> R.string.player_unavailable
        else -> R.string.player_source_error
    }

    private fun cancelAutoNext() {
        autoNextJob?.cancel(); autoNextJob = null
        if (seasonTransitionPending) loading = false
        seasonTransitionPending = false
        completionVisible = false
    }

    private fun releasePlayback() {
        cancelAutoNext()
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
        cancelAutoNext()
        savePosition(MarkEvent.STOP)
        playback?.pause()
        super.onPause()
    }
    override fun onStop() {
        resolveJob?.cancel()
        releasePlayback()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        container.homeChannels.requestRefresh()
        super.onStop()
    }

    override fun finish() {
        cancelAutoNext()
        savePosition(MarkEvent.STOP)
        request?.let { selected ->
            setResult(RESULT_OK, Intent().putExtra("season", selected.seasonId)
                .putExtra("translation", selected.translationId).putExtra("episode", selected.episodeId))
        }
        super.finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        request?.let { outState.putString("current_episode", it.episodeId) }
        outState.putBoolean("history_submitted", historyStart.submitted)
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
        if (completionVisible && event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) return false
        if (seasonTransitionPending && failure == null) return true
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
            .putExtra("resume_position", request.resumePositionMillis ?: -1L)
            .putExtra("season_number", request.seasonNumber ?: -1)
            .putStringArrayListExtra("episode_ids", ArrayList(request.episodes.map { it.id }))
            .putStringArrayListExtra("episode_labels", ArrayList(request.episodes.map { it.label }))

        private fun readRequest(intent: Intent): PlaybackRequest? {
            val season = intent.getStringExtra("season") ?: return null
            val page = intent.getStringExtra("page") ?: return null
            val translation = intent.getStringExtra("translation") ?: return null
            val episode = intent.getStringExtra("episode") ?: return null
            val label = intent.getStringExtra("label") ?: return null
            return PlaybackRequest(season, page, translation, episode, label, intent.getStringExtra("title").orEmpty(),
                playbackEpisodes(intent.getStringArrayListExtra("episode_ids"), intent.getStringArrayListExtra("episode_labels")),
                intent.getLongExtra("resume_position", -1L).takeIf { it >= 0 },
                intent.getIntExtra("season_number", -1).takeIf { it >= 0 })
        }
    }
}
