package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.flow.MutableStateFlow
import ru.seasonvar.tv.R
import ru.seasonvar.tv.ui.account.*
import ru.seasonvar.tv.ui.catalog.LoadFailure
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import java.io.File

object HistoryClearLayoutProbe {
    fun run(probe: Instrumentation) = with(probe) {
        val result = Bundle()
        var activity: MarkMenuPreviewActivity? = null
        var phase = "start"
        var confirmations = 0
        val controller = object : HistoryClearController {
            override val state = MutableStateFlow(HistoryClearUiState())
            override fun ask() { state.value = HistoryClearUiState(visible = true) }
            override fun dismiss() { state.value = state.value.copy(visible = false) }
            override fun confirm() {
                if (state.value.visible && !state.value.busy) {
                    confirmations++
                    state.value = state.value.copy(busy = true, failure = null)
                }
            }
        }
        val artifacts = File(checkNotNull(targetContext.getExternalFilesDir("history-clear-probe")), "run-" + System.currentTimeMillis())
        fun main(block: () -> Unit) { runOnMainSync(block) }
        fun waitFor(predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 8000
            while (!predicate()) { check(SystemClock.elapsedRealtime() < end) { "Timed out" }; Thread.sleep(50) }
        }
        fun stage(name: String) { phase = name; sendStatus(Activity.RESULT_OK, Bundle().apply { putString("phase", name) }) }
        fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
            return null
        }
        fun control(id: Int): AccessibilityNodeInfo? {
            var node = find(uiAutomation.rootInActiveWindow, targetContext.getString(id)) ?: return null
            while (!node.isClickable && node.parent != null) node = node.parent
            return node
        }
        fun focus(id: Int) = control(id)?.isFocused == true
        fun bounds(id: Int) = Rect().also { checkNotNull(control(id)).getBoundsInScreen(it) }
        fun settle() { Thread.sleep(350); waitForIdleSync() }
        fun key(code: Int) { sendKeyDownUpSync(code); settle() }
        fun capture(name: String) {
            check(artifacts.isDirectory || artifacts.mkdirs())
            val image = checkNotNull(uiAutomation.takeScreenshot())
            File(artifacts, name + ".png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        try {
            check(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.FINGERPRINT.contains("generic"))
            activity = startActivitySync(Intent(targetContext, MarkMenuPreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MarkMenuPreviewActivity
            stage("button_alignment")
            ButtonAlignmentChecks.preview(probe, activity, artifacts)
            main { activity.setContent { SeasonvarTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { HistoryClearDialog(controller) }
            } } }
            stage("cancel_default")
            main { controller.ask() }; waitFor { focus(R.string.history_clear_cancel) }
            val cancelBounds = bounds(R.string.history_clear_cancel)
            val clearBounds = bounds(R.string.history_clear_action)
            check(cancelBounds.right <= clearBounds.left)
            ButtonAlignmentChecks.centered(probe, targetContext.getString(R.string.history_clear_cancel))
            ButtonAlignmentChecks.centered(probe, targetContext.getString(R.string.history_clear_action))
            capture("01-confirmation")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            check(!controller.state.value.visible); check(confirmations == 0)
            main { controller.ask() }; waitFor { focus(R.string.history_clear_cancel) }
            key(KeyEvent.KEYCODE_BACK); check(!controller.state.value.visible); check(confirmations == 0)
            stage("confirmed_clear")
            main { controller.ask() }; waitFor { focus(R.string.history_clear_cancel) }
            key(KeyEvent.KEYCODE_DPAD_RIGHT); waitFor { focus(R.string.history_clear_action) }
            key(KeyEvent.KEYCODE_DPAD_CENTER); waitFor { controller.state.value.busy }
            key(KeyEvent.KEYCODE_DPAD_CENTER); check(confirmations == 1)
            waitFor { control(R.string.history_clear_close) != null && control(R.string.history_clearing) != null }
            check(bounds(R.string.history_clear_close) == cancelBounds)
            check(bounds(R.string.history_clearing) == clearBounds)
            ButtonAlignmentChecks.centered(probe, targetContext.getString(R.string.history_clear_close))
            ButtonAlignmentChecks.centered(probe, targetContext.getString(R.string.history_clearing))
            check(focus(R.string.history_clearing)); capture("02-clearing")
            stage("failure_retry")
            main { controller.state.value = controller.state.value.copy(busy = false, failure = LoadFailure.NETWORK) }
            settle(); waitFor { control(R.string.history_clear_action) != null }; check(bounds(R.string.history_clear_action) == clearBounds); check(focus(R.string.history_clear_action))
            key(KeyEvent.KEYCODE_DPAD_CENTER); check(confirmations == 2)
            main { controller.state.value = HistoryClearUiState() }; settle()
            result.putBoolean("geometry_stable", true)
            result.putBoolean("text_centered", true)
            result.putBoolean("focus_preserved", true)
            result.putBoolean("blocked_actions", true)
            result.putBoolean("fixture_only", true)
            result.putBoolean("script_success", true)
        } catch (error: Throwable) {
            runCatching { capture("99-failure") }
            result.putString("failure_counts", "${confirmations}_${controller.state.value.visible}_${controller.state.value.busy}")
            result.putString("failure_type", error.javaClass.simpleName)
            result.putString("failure_phase", phase)
            result.putString("failure_line", error.stackTrace.firstOrNull { it.className.contains("HistoryClearLayoutProbe") }?.lineNumber?.toString() ?: "unknown")
            result.putBoolean("script_success", false)
        } finally {
            if (artifacts.exists()) result.putString("artifacts", artifacts.absolutePath)
            main { activity?.finish() }
            finish(Activity.RESULT_OK, result)
        }
    }
}
