package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.runBlocking
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.data.SeriesDetailsParser
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.playback.PlaybackRequest
import ru.seasonvar.tv.ui.player.PlayerActivity
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
object HistoryPlaybackProbe {
    fun run(probe: Instrumentation) = with(probe) {
        val result = Bundle()
        val app = targetContext.applicationContext as SeasonvarApplication
        val id = "2779"
        val page = "https://seasonvar.ru/serial-2779--Naruto_pssrhvg-0-season.html"
        val preferences = targetContext.getSharedPreferences("playback-progress", Context.MODE_PRIVATE)
        val original = preferences.getString("season-$id", null)
        val originalTime = preferences.getLong("updated-$id", -1)
        val originalCompleted = if (preferences.contains("completed-$id")) preferences.getBoolean("completed-$id", false) else null
        val artifacts = File(checkNotNull(targetContext.getExternalFilesDir("history-probe")), "run-" + System.currentTimeMillis())
        var activity: PlayerActivity? = null
        var phase = "metadata"
        fun <T> main(block: () -> T): T {
            var value: Any? = null
            var failure: Throwable? = null
            runOnMainSync { try { value = block() } catch (error: Throwable) { failure = error } }
            failure?.let { throw it }
            @Suppress("UNCHECKED_CAST") return value as T
        }
        fun view(root: View): PlayerView? {
            if (root is PlayerView) return root
            if (root is ViewGroup) for (index in 0 until root.childCount) view(root.getChildAt(index))?.let { return it }
            return null
        }
        fun player(): Player = checkNotNull(view(checkNotNull(activity).window.decorView)?.player)
        fun waitFor(timeout: Long = 45_000, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (!condition()) { check(SystemClock.elapsedRealtime() < deadline); Thread.sleep(100) }
        }
        fun capture(name: String) {
            check(artifacts.isDirectory || artifacts.mkdirs())
            val bitmap = checkNotNull(uiAutomation.takeScreenshot())
            File(artifacts, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            check(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.FINGERPRINT.contains("generic"))
            runBlocking { if (app.account.session.value.user == null) app.account.restore() }
            check(app.account.session.value.user != null)
            val details = runBlocking { SeriesDetailsParser.parse(app.siteClient.get(page), SeriesEntry(id, page, "Наруто", 1, "", "")) }
            val translation = details.translations.single { it.id == "0" }
            val episode = checkNotNull(translation.episodes).single { it.label == "1" }
            val selected = PlaybackRequest(id, page, translation.id, episode.id, episode.label, details.entry.title,
                listOf(episode), resumePositionMillis = 10_000, seasonNumber = details.entry.season)
            val historyBefore = app.account.historyRevision.value
            val marksBefore = app.account.marksRevision.value
            phase = "video"
            activity = startActivitySync(PlayerActivity.intent(targetContext, selected)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as PlayerActivity
            waitFor { main { view(checkNotNull(activity).window.decorView)?.player?.isPlaying == true } }
            capture("playing-a"); Thread.sleep(1000); capture("playing-b")
            phase = "pause_seek"
            sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            waitFor { main { !player().isPlaying } }; capture("paused")
            val oldPosition = main { player().currentPosition }
            sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            waitFor { main { player().currentPosition > oldPosition + 1000 } }
            capture("seek")
            sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            waitFor { main { player().isPlaying } }
            Thread.sleep(1000)
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor { main { checkNotNull(activity).isFinishing } }
            phase = "history"
            waitFor { app.account.historyRevision.value > historyBefore }
            Thread.sleep(1000)
            result.putBoolean("history_recorded_once", app.account.historyRevision.value == historyBefore + 1)
            result.putBoolean("no_permanent_mark_written", app.account.marksRevision.value == marksBefore)
            result.putBoolean("pause_seek_back_verified", true)
            result.putBoolean("probe_success", result.getBoolean("history_recorded_once") && result.getBoolean("no_permanent_mark_written"))
        } catch (error: Throwable) {
            result.putBoolean("probe_success", false)
            result.putString("phase", phase)
            result.putString("failure_type", error.javaClass.simpleName)
            runCatching { capture("failure") }
        } finally {
            main { activity?.finish() }
            waitForIdleSync()
            val editor = preferences.edit()
            if (original == null) editor.remove("season-$id") else editor.putString("season-$id", original)
            if (originalTime < 0) editor.remove("updated-$id") else editor.putLong("updated-$id", originalTime)
            if (originalCompleted == null) editor.remove("completed-$id") else editor.putBoolean("completed-$id", originalCompleted)
            result.putBoolean("local_progress_restored", editor.commit())
        }
        result.putString("artifacts", artifacts.absolutePath)
        finish(Activity.RESULT_OK, result)
    }
}
