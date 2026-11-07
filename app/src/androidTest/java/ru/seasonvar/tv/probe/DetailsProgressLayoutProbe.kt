package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.tv.material3.MaterialTheme
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.catalog.LoadFailure
import ru.seasonvar.tv.ui.details.DetailsUiState
import ru.seasonvar.tv.ui.details.SeasonProgress
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import java.io.File

object DetailsProgressLayoutProbe {
    fun run(probe: Instrumentation) = with(probe) {
        val result = Bundle()
        var activity: MarkMenuPreviewActivity? = null
        val entry = SeriesEntry("123", "https://seasonvar.ru/serial-123-Test.html", "Проверка прогресса", 1, "", "")
        val state = mutableStateOf(DetailsUiState())
        val artifacts = File(checkNotNull(targetContext.getExternalFilesDir("details-progress-probe")), "run-" + System.currentTimeMillis())
        fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString() == text) return node
            for (index in 0 until node.childCount) find(node.getChild(index), text)?.let { return it }
            return null
        }
        fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
        fun episode(): AccessibilityNodeInfo? {
            var node = find(uiAutomation.rootInActiveWindow, "1") ?: return null
            while (!node.isClickable && node.parent != null) node = node.parent
            return node
        }
        fun waitFor(predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 5000
            while (!predicate()) { check(SystemClock.elapsedRealtime() < end); Thread.sleep(50) }
        }
        fun message(value: String) = targetContext.getString(R.string.account_mark_progress, value)
        fun capture(name: String) {
            check(artifacts.isDirectory || artifacts.mkdirs())
            val image = checkNotNull(uiAutomation.takeScreenshot())
            File(artifacts, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        try {
            check(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.FINGERPRINT.contains("generic"))
            activity = startActivitySync(Intent(targetContext, MarkMenuPreviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MarkMenuPreviewActivity
            runOnMainSync {
                activity!!.setContent {
                    SeasonvarTheme {
                        val d = SeasonvarTheme.dimensions
                        val focus = remember { FocusRequester() }
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(d.spacingLarge),
                            verticalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
                            ThemeButton("Смотреть", {})
                            SeasonProgress(state.value, state.value.entry ?: entry)
                            ThemeButton("1", {}, Modifier.focusRequester(focus))
                        }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                    }
                }
            }
            val loading = message(targetContext.getString(R.string.details_progress_loading))
            waitFor { find(uiAutomation.rootInActiveWindow, loading) != null && episode()?.isFocused == true }
            val progressTop = bounds(checkNotNull(find(uiAutomation.rootInActiveWindow, loading))).top
            val episodeBounds = bounds(checkNotNull(episode()))
            capture("01-first-frame")
            fun checkState(name: String, next: DetailsUiState, expected: String) {
                runOnMainSync { state.value = next }
                waitFor { find(uiAutomation.rootInActiveWindow, expected) != null }
                check(bounds(checkNotNull(find(uiAutomation.rootInActiveWindow, expected))).top == progressTop)
                check(bounds(checkNotNull(episode())) == episodeBounds && episode()?.isFocused == true)
                capture(name)
            }
            checkState("02-loading", DetailsUiState(entry = entry, progressLoading = true), loading)
            val ready = entry.copy(accountProgress = AccountProgress(ProgressCounter(2, 5),
                SeasonEpisodeProgress(entry.id, ProgressCounter(3, 10))))
            val value = message(targetContext.getString(R.string.details_progress_season, 2, 5) + " · " +
                targetContext.getString(R.string.details_progress_episode, 3, 10))
            checkState("03-ready", DetailsUiState(entry = ready), value)
            checkState("04-refresh", DetailsUiState(entry = ready, progressLoading = true), value)
            val unknown = message(targetContext.getString(R.string.details_progress_unknown))
            checkState("05-unknown", DetailsUiState(entry = entry), unknown)
            checkState("06-failed", DetailsUiState(entry = entry, progressFailure = LoadFailure.NETWORK), unknown)
            result.putBoolean("geometry_stable", true)
            result.putBoolean("focus_preserved", true)
            result.putBoolean("progress_visible", true)
            result.putBoolean("fixture_only", true)
            result.putBoolean("script_success", true)
        } catch (error: Throwable) {
            result.putBoolean("script_success", false)
            result.putString("failure_type", error.javaClass.simpleName)
        } finally {
            runOnMainSync { activity?.finish() }
            if (artifacts.isDirectory) result.putString("artifacts", artifacts.absolutePath)
            finish(Activity.RESULT_OK, result)
        }
    }
}
