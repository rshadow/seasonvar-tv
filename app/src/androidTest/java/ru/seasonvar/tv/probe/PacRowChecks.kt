package ru.seasonvar.tv.probe

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.network.pac.PacFailure
import ru.seasonvar.tv.data.network.pac.PacState
import ru.seasonvar.tv.data.settings.PacRefreshInterval
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.settings.PacUpdateRow
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object PacRowChecks {
    fun run(probe: Instrumentation, activity: MarkMenuPreviewActivity, artifacts: File) = with(probe) {
        val initial = PacState(fetchedAt = 1_700_000_000_000L, interval = PacRefreshInterval.WEEK)
        val state = mutableStateOf(initial)
        val seconds = mutableIntStateOf(0)
        val focus = List(4) { FocusRequester() }
        var clicks = 0
        fun settle() {
            val frames = CountDownLatch(1)
            runOnMainSync { activity.window.decorView.postOnAnimation {
                activity.window.decorView.postOnAnimation { frames.countDown() }
            } }
            check(frames.await(3, TimeUnit.SECONDS)); waitForIdleSync()
            if (android.os.Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache()
        }
        fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
            return null
        }
        fun control(label: String): AccessibilityNodeInfo {
            var node = checkNotNull(find(uiAutomation.rootInActiveWindow, label))
            while (!node.isClickable && node.parent != null) node = node.parent
            return node
        }
        fun bounds(label: String): Rect = Rect().also(control(label)::getBoundsInScreen)
        runOnMainSync { activity.setContent { SeasonvarTheme {
            val d = SeasonvarTheme.dimensions
            Column(Modifier.width(d.dialogWidth).padding(d.spacingLarge), verticalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
                PacUpdateRow(state.value, seconds.intValue, true, {}, { clicks++ }, focus[0], focus[1],
                    FocusRequester.Cancel, focus[2], focus[3])
                Row {
                    ThemeButton("Сохранить", {}, Modifier.weight(1f).height(d.headerButtonSize).focusRequester(focus[2]))
                    ThemeButton("Отмена", {}, Modifier.weight(1f).height(d.headerButtonSize).focusRequester(focus[3]))
                }
            }
        } } }
        settle()
        fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            uiAutomation.executeShellCommand(command)).bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
        val originalTimeFormat = shell("settings get system time_12_24")
        val formatted = mutableListOf<String>()
        try {
            for (format in listOf("12", "24")) {
                shell("settings put system time_12_24 $format")
                runOnMainSync { state.value = initial.copy(revision = format.toLong()) }; settle()
                val instant = java.util.Date(initial.fetchedAt)
                val display = android.text.format.DateFormat.getDateFormat(targetContext).format(instant) + " " +
                    android.text.format.DateFormat.getTimeFormat(targetContext).format(instant)
                check(find(uiAutomation.rootInActiveWindow, targetContext.getString(R.string.pac_updated, display)) != null)
                formatted += display
            }
            check(formatted[0] != formatted[1])
        } finally {
            shell(if (originalTimeFormat in listOf("12", "24")) "settings put system time_12_24 $originalTimeFormat"
                else "settings delete system time_12_24")
            runOnMainSync { state.value = initial }; settle()
        }
        val period = targetContext.getString(R.string.pac_interval, targetContext.getString(R.string.pac_week))
        val reload = targetContext.getString(R.string.pac_refresh)
        val geometry = bounds(period) to bounds(reload)
        runOnMainSync { focus[1].requestFocus() }; settle()
        repeat(2) { sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); settle() }
        check(clicks == 2)
        for ((name, model, remaining) in listOf(
            Triple("ready", initial, 0), Triple("loading", initial.copy(downloading = true), 0),
            Triple("countdown", initial.copy(downloading = true), 37),
            Triple("preparing", initial.copy(preparing = true), 0),
            Triple("error", initial.copy(failure = PacFailure.DOWNLOAD), 0), Triple("empty", PacState(interval = PacRefreshInterval.WEEK), 0),
        )) {
            runOnMainSync { state.value = model; seconds.intValue = remaining }; settle()
            check(bounds(period) == geometry.first)
            check(bounds(if (remaining > 0) remaining.toString() else reload) == geometry.second)
            check(control(if (remaining > 0) remaining.toString() else reload).isFocused)
            if (remaining > 0) ButtonAlignmentChecks.centered(probe, remaining.toString())
            if (remaining > 0 || model.downloading || model.preparing) {
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); settle(); check(clicks == 2)
            }
            check(artifacts.isDirectory || artifacts.mkdirs())
            val image = checkNotNull(uiAutomation.takeScreenshot())
            File(artifacts, "pac-row-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
}
