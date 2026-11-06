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
import ru.seasonvar.tv.R
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.data.SeriesDetailsParser
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.playback.PlaybackRequest
import ru.seasonvar.tv.ui.player.PlayerActivity
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
object PlayerAutoNextProbe {
    fun run(probe: Instrumentation, arguments: Bundle) = with(probe) {
        val result = Bundle()
        val app = targetContext.applicationContext as SeasonvarApplication
        val preferences = targetContext.getSharedPreferences("playback-progress", Context.MODE_PRIVATE)
        val originalProgress = linkedMapOf<String, String?>()
        fun rememberProgress(id: String) {
            val key = "season-$id"
            if (key !in originalProgress) originalProgress[key] = preferences.getString(key, null)
        }
        var activity: PlayerActivity? = null
        val originalEnabled = app.interfaceSettings.autoNextEnabled.value
        val originalSeasonEnabled = app.interfaceSettings.autoNextSeasonEnabled.value
        var phase = "metadata"
        fun <T> main(block: () -> T): T {
            var value: Any? = null
            var error: Throwable? = null
            runOnMainSync { try { value = block() } catch (failure: Throwable) { error = failure } }
            error?.let { throw it }
            @Suppress("UNCHECKED_CAST")
            return value as T
        }
        fun waitFor(timeout: Long = 60_000, predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + timeout
            while (!predicate()) { check(SystemClock.elapsedRealtime() < end); Thread.sleep(100) }
        }
        fun playerView(view: View): PlayerView? {
            if (view is PlayerView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) playerView(view.getChildAt(index))?.let { return it }
            return null
        }
        fun player(): Player = checkNotNull(playerView(checkNotNull(activity).window.decorView)?.player)
        fun currentEpisode(): String? = main { activity?.intent?.getStringExtra("episode") }
        fun playing(episode: String) = waitFor {
            main { activity?.intent?.getStringExtra("episode") == episode &&
                playerView(checkNotNull(activity).window.decorView)?.player?.let { it.playbackState == Player.STATE_READY && it.isPlaying } == true }
        }
        fun key(code: Int) { sendKeyDownUpSync(code); Thread.sleep(300) }
        fun visibleText(label: String): Boolean {
            fun contains(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                node ?: return false
                if (node.text?.contains(label) == true || node.contentDescription?.contains(label) == true) return true
                return (0 until node.childCount).any { contains(node.getChild(it)) }
            }
            return contains(uiAutomation.rootInActiveWindow)
        }
        fun offer(): Boolean = visibleText(targetContext.getString(R.string.player_auto_next_none))
        fun endEpisode(next: String? = null) {
            main { val media = player(); check(media.duration > 2000); media.seekTo(media.duration - 250); media.play() }
            if (next == null) waitFor { main { player().playbackState == Player.STATE_ENDED } }
            else waitFor(2000) { currentEpisode() == next }
        }
        val artifacts = File(checkNotNull(targetContext.getExternalFilesDir("player-probe")), "run-" + System.currentTimeMillis())
        fun capture(name: String) {
            check(artifacts.isDirectory || artifacts.mkdirs())
            val bitmap = checkNotNull(uiAutomation.takeScreenshot())
            File(artifacts, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun stage(name: String) { phase = name; sendStatus(Activity.RESULT_OK, Bundle().apply { putString("phase", name) }) }
        try {
            check(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.FINGERPRINT.contains("generic"))
            check(app.account.session.value.user == null)
            val page = arguments.getString("page") ?: "https://seasonvar.ru/serial-2779--Naruto_pssrhvg-0-season.html"
            check(page.matches(Regex("https://seasonvar\\.ru/serial-[0-9]+[^/?#]*\\.html")))
            val id = checkNotNull(Regex("/serial-([0-9]+)").find(page)).groupValues[1]
            val details = runBlocking { SeriesDetailsParser.parse(app.siteClient.get(page), SeriesEntry(id, page, "Проверка плеера", null, "", "")) }
            val translation = details.translations.firstOrNull { it.id == "0" && (it.episodes?.size ?: 0) >= 2 }
                ?: details.translations.first { (it.episodes?.size ?: 0) >= 2 }
            val episodes = checkNotNull(translation.episodes).take(2)
            val request = PlaybackRequest(id, page, translation.id, episodes[0].id, episodes[0].label, details.entry.title, episodes, resumePositionMillis = 10_000)
            rememberProgress(id)
            preferences.edit().remove("season-$id").commit()
            result.putBoolean("default_enabled", originalEnabled)
            result.putBoolean("default_season_enabled", originalSeasonEnabled)
            main { app.interfaceSettings.setAutoNextEnabled(true); app.interfaceSettings.setAutoNextSeasonEnabled(false) }
            activity = startActivitySync(PlayerActivity.intent(targetContext, request).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as PlayerActivity
            stage("first_video"); playing(episodes[0].id);
            waitFor { main { player().currentPosition in 10_000..25_000 } }
            result.putBoolean("explicit_resume_verified", true)
            Thread.sleep(2500); capture("01-first-a"); Thread.sleep(2000); capture("02-first-b")
            stage("pause_seek")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor(5000) { main { !player().isPlaying } }
            val paused = main { player().currentPosition }
            capture("03-paused-a"); Thread.sleep(1000); capture("04-paused-b")
            check(kotlin.math.abs(main { player().currentPosition } - paused) < 250)
            key(KeyEvent.KEYCODE_DPAD_CENTER); playing(episodes[0].id)
            val beforeSeek = main { player().currentPosition }
            key(KeyEvent.KEYCODE_DPAD_RIGHT); playing(episodes[0].id)
            check(main { player().currentPosition } > beforeSeek + 5000); capture("05-forward")
            val forward = main { player().currentPosition }
            key(KeyEvent.KEYCODE_DPAD_LEFT); playing(episodes[0].id)
            check(main { player().currentPosition } < forward - 5000); capture("06-rewind")
            stage("automatic_next"); endEpisode(episodes[1].id); playing(episodes[1].id)
            check(!offer())
            Thread.sleep(2000); capture("08-auto-next-a"); Thread.sleep(2000); capture("09-auto-next-b")
            result.putBoolean("automatic_next", true)
            stage("manual_previous_and_disabled")
            key(KeyEvent.KEYCODE_MEDIA_PREVIOUS); playing(episodes[0].id); Thread.sleep(2000); capture("10-previous")
            main { app.interfaceSettings.setAutoNextEnabled(false) }
            endEpisode(); Thread.sleep(6000)
            check(!offer() && currentEpisode() == episodes[0].id)
            result.putBoolean("disabled_stays", true)
            stage("manual_next_and_last")
            main { app.interfaceSettings.setAutoNextEnabled(true) }
            key(KeyEvent.KEYCODE_MEDIA_NEXT); playing(episodes[1].id); Thread.sleep(2000); capture("11-manual-next-a"); Thread.sleep(2000); capture("12-manual-next-b")
            endEpisode(); Thread.sleep(6000)
            check(currentEpisode() == episodes[1].id)
            check(visibleText(targetContext.getString(R.string.player_auto_next_none)))
            capture("13-last-episode")
            result.putBoolean("last_stays", true)
            stage("return")
            key(KeyEvent.KEYCODE_DPAD_CENTER); waitFor(5000) { main { activity?.isDestroyed == true } }
            result.putBoolean("last_closes", true)
            activity = startActivitySync(PlayerActivity.intent(targetContext, request).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as PlayerActivity
            playing(episodes[0].id); key(KeyEvent.KEYCODE_BACK)
            waitFor(5000) { main { activity?.isDestroyed == true } }
            stage("season_disabled")
            val seasonPage = checkNotNull(arguments.getString("season-page"))
            check(seasonPage.matches(Regex("https://seasonvar\\.ru/serial-[0-9]+[^/?#]*\\.html")))
            val seasonId = checkNotNull(Regex("/serial-([0-9]+)").find(seasonPage)).groupValues[1]
            val seasonDetails = runBlocking { app.repository.details(SeriesEntry(seasonId, seasonPage, "Проверка перехода сезонов", null, "", ""), force = true) }
            val seasonVoice = seasonDetails.translations.first { it.id == "0" && !it.episodes.isNullOrEmpty() }
            val seasonEpisodes = checkNotNull(seasonVoice.episodes)
            val last = seasonEpisodes.last()
            val seasonRequest = PlaybackRequest(seasonId, seasonPage, seasonVoice.id, last.id, last.label,
                seasonDetails.entry.title, seasonEpisodes, resumePositionMillis = 0)
            val nextSeason = checkNotNull(runBlocking { app.nextSeasons.next(seasonRequest) })
            rememberProgress(seasonId); rememberProgress(nextSeason.seasonId)
            main { app.interfaceSettings.setAutoNextEnabled(false); app.interfaceSettings.setAutoNextSeasonEnabled(false) }
            activity = startActivitySync(PlayerActivity.intent(targetContext, seasonRequest).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as PlayerActivity
            playing(last.id); endEpisode(); waitFor(5000) { offer() }
            check(main { activity?.intent?.getStringExtra("season") } == seasonId)
            result.putBoolean("season_disabled_stays", true)
            key(KeyEvent.KEYCODE_BACK); waitFor(5000) { main { activity?.isDestroyed == true } }
            stage("automatic_season")
            main { app.interfaceSettings.setAutoNextSeasonEnabled(true) }
            activity = startActivitySync(PlayerActivity.intent(targetContext, seasonRequest).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as PlayerActivity
            playing(last.id)
            main { player().seekTo(60_000L.coerceAtMost(player().duration / 3)) }
            playing(last.id); Thread.sleep(2000); capture("18-previous-season-a"); Thread.sleep(2000); capture("19-previous-season-b")
            main { val media = player(); media.seekTo(media.duration - 250); media.play() }
            waitFor(120_000) { main { activity?.intent?.getStringExtra("season") == nextSeason.seasonId } }
            playing(nextSeason.episodeId); check(!offer())
            check(main { player().currentPosition } < 20_000)
            Thread.sleep(2500); capture("14-next-season-a"); Thread.sleep(2500); capture("15-next-season-b")
            result.putBoolean("automatic_season", true)
            key(KeyEvent.KEYCODE_DPAD_CENTER); waitFor(5000) { main { !player().isPlaying } }
            capture("16-season-paused")
            key(KeyEvent.KEYCODE_DPAD_CENTER); playing(nextSeason.episodeId)
            val seasonPosition = main { player().currentPosition }
            repeat(6) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }; playing(nextSeason.episodeId)
            check(main { player().currentPosition } > seasonPosition + 50_000)
            Thread.sleep(2000); capture("17-season-seek"); Thread.sleep(2000); capture("20-season-after-seek")
            result.putBoolean("season_pause_seek", true)
            key(KeyEvent.KEYCODE_BACK); waitFor(5000) { main { activity?.isDestroyed == true } }
            check(app.account.session.value.user == null && app.account.marksRevision.value == 0L)
            result.putBoolean("no_account_marks_written", true)
            result.putBoolean("script_success", true)
        } catch (error: Throwable) {
            runCatching { capture("failure") }
            result.putString("activity_state", main { activity?.lifecycle?.currentState?.name ?: "NONE" })
            result.putBoolean("offer_visible", offer())
            result.putBoolean("script_success", false)
            result.putString("failure_type", error.javaClass.simpleName)
            result.putString("phase", phase)
        } finally {
            main { activity?.finish() }
            activity?.let { runCatching { waitFor(5000) { main { it.isDestroyed } } } }
            originalProgress.forEach { (name, value) ->
                preferences.edit().apply { if (value == null) remove(name) else putString(name, value) }.commit()
            }
            result.putBoolean("position_restored", originalProgress.all { (name, value) -> preferences.getString(name, null) == value })
            main {
                app.interfaceSettings.setAutoNextEnabled(originalEnabled)
                app.interfaceSettings.setAutoNextSeasonEnabled(originalSeasonEnabled)
            }
            val settings = targetContext.getSharedPreferences("interface", Context.MODE_PRIVATE)
            val saved = settings.edit().putBoolean("auto_next_episode", originalEnabled)
                .putBoolean("auto_next_season", originalSeasonEnabled).commit()
            result.putBoolean("settings_restored", saved && settings.getBoolean("auto_next_episode", !originalEnabled) == originalEnabled &&
                settings.getBoolean("auto_next_season", !originalSeasonEnabled) == originalSeasonEnabled)
            result.putString("artifacts", artifacts.absolutePath)
        }
        finish(if (result.getBoolean("script_success")) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
