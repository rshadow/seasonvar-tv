package ru.seasonvar.tv.probe

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import java.io.File
import kotlin.math.abs

internal object ButtonAlignmentChecks {
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }

    private fun geometry(probe: Instrumentation, label: String): Pair<Rect, RectF> {
        val text = checkNotNull(find(probe.uiAutomation.rootInActiveWindow, label)) { "Missing text" }
        var control = text
        while (!control.isClickable && control.parent != null) control = control.parent
        val bounds = Rect().also(control::getBoundsInScreen)
        val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        check(text.refreshWithExtraData(key, Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, label.length)
        }))
        @Suppress("DEPRECATION")
        val characters = checkNotNull(text.extras.getParcelableArray(key))
        val textBounds = RectF()
        characters.forEach { character -> (character as? RectF)?.let(textBounds::union) }
        check(!textBounds.isEmpty) { "Missing character bounds" }
        return bounds to textBounds
    }

    fun centered(probe: Instrumentation, label: String) {
        val (button, text) = geometry(probe, label)
        check(abs(button.exactCenterX() - text.centerX()) <= 3f) { "Horizontal text alignment" }
        check(abs(button.exactCenterY() - text.centerY()) <= 3f) { "Vertical text alignment" }
    }

    fun preview(probe: Instrumentation, activity: MarkMenuPreviewActivity, artifacts: File) = with(probe) {
        runOnMainSync {
            activity.setContent { SeasonvarTheme {
                val d = SeasonvarTheme.dimensions
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(d.spacingLarge),
                    verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                    Row(Modifier.width(d.dialogWidth), horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                        ThemeButton("Отмена", {}, Modifier.weight(1f).height(d.navigationButtonHeight))
                        ThemeButton("Подтвердить", {}, Modifier.weight(1f).height(d.navigationButtonHeight))
                    }
                    Row(Modifier.width(d.dialogWidth), horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                        ThemeButton("Повторить", {}, Modifier.height(d.navigationButtonHeight))
                        ThemeButton("Вернуться назад", {}, Modifier.height(d.navigationButtonHeight))
                    }
                    ThemeButton("Пункт меню", {}, Modifier.width(d.dialogWidth).height(d.navigationButtonHeight),
                        contentAlignment = Alignment.CenterStart)
                    ThemeButton("Строка с галочкой", {}, Modifier.width(d.dialogWidth).height(d.navigationButtonHeight), checked = true)
                    ThemeButton("Недоступно", {}, Modifier.width(d.dialogWidth).height(d.navigationButtonHeight), actionEnabled = false)
                    ThemeButton("Загрузка", {}, Modifier.width(d.dialogWidth).height(d.navigationButtonHeight), loading = true)
                }
            } }
        }
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (find(uiAutomation.rootInActiveWindow, "Загрузка") == null) {
            check(SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(50)
        }
        waitForIdleSync()
        for (label in listOf("Отмена", "Подтвердить", "Повторить", "Вернуться назад", "Недоступно", "Загрузка")) centered(probe, label)
        val full = geometry(probe, "Отмена").first
        val first = geometry(probe, "Повторить").first
        val second = geometry(probe, "Вернуться назад").first
        check(first.right < second.left && first.width() + second.width() < full.width()) { "Compact buttons expanded" }
        val (navigation, label) = geometry(probe, "Пункт меню")
        check(label.centerX() < navigation.exactCenterX() && label.left > navigation.left)
        val checked = geometry(probe, "Строка с галочкой").second
        check(checked.left > label.left) { "Check column lost" }
        check(artifacts.isDirectory || artifacts.mkdirs())
        val screenshot = checkNotNull(uiAutomation.takeScreenshot())
        File(artifacts, "00-common-buttons.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
}
