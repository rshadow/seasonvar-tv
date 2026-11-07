package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.tv.TvContract
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.SessionManager
import ru.seasonvar.tv.data.account.SessionStore
import ru.seasonvar.tv.data.home.*
import ru.seasonvar.tv.ui.player.PlayerActivity
import ru.seasonvar.tv.ui.home.HomePlaybackActivity
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
object HomeChannelProbe {
    fun run(probe: Instrumentation) = with(probe) {
        val app = targetContext.applicationContext as SeasonvarApplication
        val home = app.homeChannels
        val result = Bundle()
        val automation = uiAutomation
        val progress = targetContext.getSharedPreferences("playback-progress", Context.MODE_PRIVATE)
        val progressKeys = listOf("season-2779", "updated-2779", "completed-2779")
        val savedProgress = progressKeys.associateWith { progress.all[it] }
        val savedEnabled = home.store.enabled.value
        val field = SeasonvarApplication::class.java.getDeclaredField("sessions\$delegate").apply { isAccessible = true }
        val originalSessions = field.get(app)
        val mutex = HomeChannels::class.java.getDeclaredField("mutex").apply { isAccessible = true }.get(home) as Mutex
        val directory = File(checkNotNull(targetContext.getExternalFilesDir("home-probe")), "run-" + System.currentTimeMillis())
        var activity: Activity? = null
        var consentHost: Activity? = null
        val monitor = addMonitor(PlayerActivity::class.java.name, null, false)
        fun <T> main(block: () -> T): T {
            var value: T? = null
            var error: Throwable? = null
            runOnMainSync { try { value = block() } catch (caught: Throwable) { error = caught } }
            error?.let { throw it }
            @Suppress("UNCHECKED_CAST") return value as T
        }
        fun waitFor(timeout: Long = 60_000, predicate: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + timeout
            while (!predicate()) { check(SystemClock.elapsedRealtime() < until); Thread.sleep(100) }
        }
        fun find(view: View): PlayerView? {
            if (view is PlayerView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        fun player(): Player = checkNotNull(find(checkNotNull(activity).window.decorView)?.player)
        fun capture(name: String) {
            check(directory.isDirectory || directory.mkdirs())
            val bitmap = checkNotNull(automation.takeScreenshot())
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun key(code: Int) { sendKeyDownUpSync(code); Thread.sleep(300) }
        fun stage(phase: String) { sendStatus(Activity.RESULT_OK, Bundle().apply { putString("phase", phase) }) }
        try {
            check(android.os.Build.MODEL.contains("sdk", true))
            check(app.account.session.value.user == null)
            check(home.publisher.supported)
            runBlocking { withContext(Dispatchers.IO) { mutex.withLock {
                val snapshots = HomeChannel.entries.associateWith { home.store.cards(it).values.toList() }
                try {
                    field.set(app, lazy { SessionManager(object : SessionStore {
                        override fun read(): List<okhttp3.Cookie> = emptyList()
                        override fun save(cookies: List<okhttp3.Cookie>) = Unit
                        override fun clear() = Unit
                    }) })
                    stage("publish")
                    val seed = SeriesEntry("2779", "https://seasonvar.ru/serial-2779--Naruto_pssrhvg-0-season.html", "Наруто", null, "", "")
                    val details = app.repository.details(seed, force = true)
                    val card = details.entry.copy(title = "Наруто · Проверка лент")
                    val voice = EntrySelectionResolver.standard(details)!!
                    val episode = voice.episodes!!.first()
                    app.playbackProgress.save(ResumePoint(card.id, voice.id, episode.id, 10_000))
                    val channelIds = HomeChannel.entries.associateWith(home.publisher::prepare)
                    HomeChannel.entries.forEach { channel ->
                        home.store.setEnabled(channel, true)
                        home.publisher.publish(channel, listOf(card), if (channel.personal) "probe-owner" else null) { true }
                    }
                    val programIds = HomeChannel.entries.associateWith(home.publisher::programs)
                    HomeChannel.entries.forEach { channel ->
                        home.publisher.publish(channel, listOf(card, card), if (channel.personal) "probe-owner" else null) { true }
                        check(programIds.getValue(channel) == home.publisher.programs(channel))
                        check(home.publisher.channels()[channel] == channelIds.getValue(channel))
                    }
                    result.putBoolean("stable_programs", true)
                    result.putBoolean("three_channels", channelIds.size == 3)
                    stage("channel_approval")
                    consentHost = startActivitySync(HomePlaybackActivity.intent(targetContext, "0".repeat(64)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    waitFor(5000) { main { (consentHost as? androidx.activity.ComponentActivity)?.lifecycle?.currentState
                        ?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) == true } }
                    for (channel in HomeChannel.entries) {
                        val id = channelIds.getValue(channel)
                        if (!home.publisher.browsable(id)) {
                            main { consentHost!!.startActivityForResult(
                                Intent(TvContract.ACTION_REQUEST_CHANNEL_BROWSABLE).putExtra(TvContract.EXTRA_CHANNEL_ID, id), 106) }
                            fun approve(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                                node ?: return false
                                if (node.text?.toString() in setOf("Add channel", "Add", "Allow", "Добавить", "Добавить канал", "Разрешить"))
                                    return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                                return (0 until node.childCount).any { approve(node.getChild(it)) }
                            }
                            waitFor(5000) { home.publisher.browsable(id) || approve(automation.rootInActiveWindow) }
                            waitFor(5000) { home.publisher.browsable(id) }
                        }
                    }
                    main { consentHost?.finish() }; consentHost = null
                    targetContext.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    Thread.sleep(1500); capture("01-launcher")
                    repeat(4) { index -> key(KeyEvent.KEYCODE_DPAD_DOWN); capture("01-launcher-row-$index") }
                    stage("guards")
                    for (channel in HomeChannel.entries.filter { it.personal }) {
                        try { home.open(HomeLink.token(channel, card.id)); error("Foreign owner accepted") }
                        catch (_: HomePlaybackUnavailable) { }
                    }
                    try { home.open("0".repeat(64)); error("Stale link accepted") }
                    catch (_: HomePlaybackUnavailable) { }
                    home.store.setEnabled(HomeChannel.NEWEST, false)
                    try { home.open(HomeLink.token(HomeChannel.NEWEST, card.id)); error("Disabled channel accepted") }
                    catch (_: HomePlaybackUnavailable) { }
                    home.store.setEnabled(HomeChannel.NEWEST, true)
                    result.putBoolean("guards_verified", true)
                    val token = HomeLink.token(HomeChannel.NEWEST, card.id)
                    val programId = programIds.getValue(HomeChannel.NEWEST).getValue(token)
                    val uri = checkNotNull(targetContext.contentResolver.query(TvContract.buildPreviewProgramUri(programId),
                        arrayOf(TvContract.PreviewPrograms.COLUMN_INTENT_URI), null, null, null)?.use { c -> check(c.moveToFirst()); c.getString(0) })
                    stage("playback")
                    startActivitySync(Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    activity = waitForMonitorWithTimeout(monitor, 60_000)
                    check(activity != null)
                    waitFor { main { find(activity!!.window.decorView)?.player?.let {
                        it.isPlaying && it.playbackState == Player.STATE_READY && it.currentPosition >= 10_000
                    } == true } }
                    check(main { player().currentPosition < 25_000 })
                    Thread.sleep(1500); capture("02-playing-a"); Thread.sleep(1500); capture("03-playing-b")
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    waitFor { main { !player().isPlaying } }
                    val paused = main { player().currentPosition }
                    capture("04-paused-a"); Thread.sleep(1000); capture("05-paused-b")
                    check(kotlin.math.abs(main { player().currentPosition } - paused) < 250)
                    key(KeyEvent.KEYCODE_DPAD_RIGHT)
                    waitFor { main { player().currentPosition > paused + 5000 } }
                    capture("06-seek")
                    key(KeyEvent.KEYCODE_BACK)
                    waitFor { main { activity!!.isDestroyed } }
                    activity = null
                    result.putBoolean("resume_pause_seek_back", true)
                    home.publisher.clear(HomeChannel.NEWEST)
                    check(home.publisher.programs(HomeChannel.NEWEST).isEmpty())
                    check(home.store.read(token) == null)
                    result.putBoolean("disable_clears_cards", true)
                    result.putBoolean("no_account_marks_written", app.account.session.value.user == null)
                    result.putBoolean("script_success", true)
                } catch (error: Exception) {
                    capture("failure-before-restore")
                    throw error
                } finally {
                    main { activity?.finish() }
                    main { consentHost?.finish() }
                    activity?.let { runCatching { waitFor(5000) { main { it.isDestroyed } } } }
                    HomeChannel.entries.forEach { channel ->
                        val old = snapshots.getValue(channel)
                        if (old.isEmpty()) home.publisher.clear(channel)
                        else home.publisher.publish(channel, old.map { it.entry }, old.first().owner) { true }
                        home.store.setEnabled(channel, channel in savedEnabled)
                    }
                }
            } } }
        } catch (error: Exception) {
            result.putString("failure_type", error.javaClass.simpleName)
            result.putInt("failure_line", error.stackTrace.firstOrNull { it.className.contains("HomeChannelProbe") }?.lineNumber ?: 0)
            result.putBoolean("script_success", false)
            runCatching { capture("failure") }
        } finally {
            field.set(app, originalSessions)
            removeMonitor(monitor)
            progress.edit().apply {
                savedProgress.forEach { (key, value) -> when (value) {
                    null -> remove(key)
                    is String -> putString(key, value)
                    is Long -> putLong(key, value)
                    is Boolean -> putBoolean(key, value)
                } }
            }.commit()
            result.putBoolean("settings_restored", home.store.enabled.value == savedEnabled && progressKeys.all { progress.all[it] == savedProgress[it] })
            result.putString("artifacts", directory.absolutePath)
            finish(Activity.RESULT_OK, result)
        }
    }
}
