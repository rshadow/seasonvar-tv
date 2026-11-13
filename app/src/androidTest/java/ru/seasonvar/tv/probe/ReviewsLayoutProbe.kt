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
import androidx.compose.runtime.mutableStateOf
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.reviews.SeriesReview
import ru.seasonvar.tv.ui.catalog.LoadFailure
import ru.seasonvar.tv.ui.reviews.ReviewsScreen
import ru.seasonvar.tv.ui.reviews.ReviewsUiState
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import java.io.File

object ReviewsLayoutProbe {
    fun run(probe: Instrumentation) = with(probe) {
        val result = Bundle()
        var activity: MarkMenuPreviewActivity? = null
        val artifacts = File(checkNotNull(targetContext.getExternalFilesDir("reviews-probe")), "run-" + System.currentTimeMillis())
        val longText = (1..30).joinToString("\n\n") { "Абзац $it. Вымышленная рецензия для проверки чтения пультом. Герои отправляются в путешествие, а сюжет постепенно раскрывает их характеры." }
        val entries = (1..8).map { SeriesReview("review-$it", "Автор $it", "13 ноября 2026", if (it == 1) longText else "Вымышленный отзыв $it.\n\nВторой абзац.") }
        val state = mutableStateOf(ReviewsUiState())
        var phase = "start"
        fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString() == text || node.contentDescription?.toString() == text ||
                node.text?.toString()?.startsWith(text + "\n") == true || node.text?.toString()?.startsWith(text + ",") == true) return node
            for (index in 0 until node.childCount) find(node.getChild(index), text)?.let { return it }
            return null
        }
        fun node(text: String) = find(uiAutomation.rootInActiveWindow, text)
        fun focused(text: String): Boolean {
            fun search(item: AccessibilityNodeInfo?): Boolean {
                item ?: return false
                if (item.isFocused && find(item, text) != null) return true
                return (0 until item.childCount).any { search(item.getChild(it)) }
            }
            return search(uiAutomation.rootInActiveWindow)
        }
        fun waitFor(predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 5000
            do {
                waitForIdleSync()
                uiAutomation.waitForIdle(100, 1000)
                if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
                if (predicate()) return
            } while (SystemClock.elapsedRealtime() < deadline)
            error("Expected fixture state")
        }
        fun await(predicate: () -> Boolean) = waitFor(predicate)
        fun key(code: Int) { sendKeyDownUpSync(code); waitForIdleSync() }
        fun bounds(text: String) = Rect().also { checkNotNull(node(text)).getBoundsInScreen(it) }
        fun buttonBounds(text: String): Rect {
            var item = checkNotNull(node(text))
            while (!item.isClickable && item.parent != null) item = item.parent
            return Rect().also(item::getBoundsInScreen)
        }
        fun capture(name: String) {
            check(artifacts.isDirectory || artifacts.mkdirs())
            val image = checkNotNull(uiAutomation.takeScreenshot())
            File(artifacts, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        fun readerImageHash(): Int {
            val area = bounds(targetContext.getString(R.string.reviews_read)).apply { inset(8, 8) }
            val image = checkNotNull(uiAutomation.takeScreenshot())
            val pixels = IntArray(area.width() * area.height())
            image.getPixels(pixels, 0, area.width(), area.left, area.top, area.width(), area.height())
            image.recycle()
            return pixels.contentHashCode()
        }
        val refresh = targetContext.getString(R.string.reviews_refresh)
        val reader = targetContext.getString(R.string.reviews_read)
        try {
            check(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.FINGERPRINT.contains("generic"))
            activity = startActivitySync(Intent(targetContext, MarkMenuPreviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MarkMenuPreviewActivity
            runOnMainSync { activity!!.setContent { SeasonvarTheme {
                ReviewsScreen("Тестовый сериал", state.value, { state.value = state.value.copy(loading = true) }, {}, {})
            } } }
            phase = "loading"
            await { node(reader) != null }
            val readerBounds = bounds(reader)
            val refreshBounds = buttonBounds(refresh)
            capture("01-loading")
            runOnMainSync { state.value = ReviewsUiState(entries.take(3), 8, "41", loading = false, loaded = true) }
            phase = "first_focus"
            await { focused("Автор 1") }
            result.putString("failure_geometry", "${readerBounds.left}:${readerBounds.top}:${readerBounds.right}:${readerBounds.bottom}/" +
                bounds(reader).let { "${it.left}:${it.top}:${it.right}:${it.bottom}" } + "/" +
                refreshBounds.let { "${it.left}:${it.top}:${it.right}:${it.bottom}" } + "/" +
                buttonBounds(refresh).let { "${it.left}:${it.top}:${it.right}:${it.bottom}" })
            check(bounds(reader) == readerBounds && buttonBounds(refresh) == refreshBounds)
            capture("02-list")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await { focused(reader) }
            val firstImage = readerImageHash()
            repeat(8) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            await { focused(reader) }
            capture("03-reading")
            val scrolled = readerImageHash()
            check(scrolled != firstImage)
            phase = "append"
            runOnMainSync { state.value = state.value.copy(entries = entries, total = 8, nextCursor = null) }
            await { node(targetContext.getString(R.string.reviews_loaded_total, 8, 8)) != null }
            check(focused(reader) && readerImageHash() == scrolled)
            capture("04-appended")
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await { focused("Автор 1") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await { focused("Автор 2") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await { focused(reader) }
            capture("05-second")
            phase = "refresh"
            key(KeyEvent.KEYCODE_DPAD_UP)
            await { focused(refresh) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await { state.value.loading }
            check(bounds(reader) == readerBounds && buttonBounds(refresh) == refreshBounds)
            runOnMainSync { state.value = state.value.copy(loading = false) }
            await { focused(refresh) }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await { focused("Автор 2") }
            phase = "failure"
            runOnMainSync { state.value = state.value.copy(moreFailure = LoadFailure.NETWORK) }
            await { node(targetContext.getString(R.string.retry)) != null }
            check(focused("Автор 2") && bounds(reader) == readerBounds)
            capture("06-retry")
            phase = "empty"
            runOnMainSync { state.value = ReviewsUiState(loading = false, loaded = true, total = 0) }
            await { node(targetContext.getString(R.string.reviews_empty)) != null }
            check(bounds(reader) == readerBounds && buttonBounds(refresh) == refreshBounds)
            capture("07-empty")
            result.putBoolean("geometry_stable", true)
            result.remove("failure_geometry")
            result.putBoolean("focus_preserved", true)
            result.putBoolean("reader_scroll", true)
            result.putBoolean("append_bounded", true)
            result.putBoolean("fixture_only", true)
            result.putBoolean("script_success", true)
        } catch (error: Throwable) {
            capture("failure")
            File(artifacts, "failure-tree.txt").writeText(buildString {
                fun visit(item: AccessibilityNodeInfo?, depth: Int) {
                    item ?: return
                    appendLine("$depth focus=${item.isFocused} focusable=${item.isFocusable} click=${item.isClickable} text=${item.text?.take(100)} desc=${item.contentDescription}")
                    repeat(item.childCount) { visit(item.getChild(it), depth + 1) }
                }
                visit(uiAutomation.rootInActiveWindow, 0)
            })
            result.putBoolean("script_success", false)
            result.putString("failure_type", error.javaClass.simpleName)
            result.putString("failure_phase", phase)
            result.putString("failure_line", error.stackTrace.firstOrNull { it.fileName == "ReviewsLayoutProbe.kt" }?.lineNumber.toString())
        } finally {
            runOnMainSync { activity?.finish() }
            if (artifacts.isDirectory) result.putString("artifacts", artifacts.absolutePath)
            finish(Activity.RESULT_OK, result)
        }
    }
}
