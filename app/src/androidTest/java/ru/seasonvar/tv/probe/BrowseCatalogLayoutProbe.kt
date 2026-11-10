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
import android.view.accessibility.AccessibilityEvent
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.flow.MutableStateFlow
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.FilterOption
import ru.seasonvar.tv.ui.SeasonvarApp
import ru.seasonvar.tv.ui.catalog.*
import ru.seasonvar.tv.ui.details.SeriesDetailsViewModel
import ru.seasonvar.tv.ui.navigation.Section
import java.io.File

object BrowseCatalogLayoutProbe {
    fun run(probe: Instrumentation) = with(probe) {
        val result = Bundle()
        var phase = "start"
        var activity: MarkMenuPreviewActivity? = null
        val store = ViewModelStore()
        val detailsModel = SeriesDetailsViewModel { entry ->
            SeriesDetails(entry, entry.id, listOf("Синтетическая карточка для проверки возврата фокуса."), emptyList(), emptyList(),
                listOf(SeasonOption(entry.id, entry.url, "Сезон 1", 1)),
                listOf(Translation("0", "Оригинал", listOf(Episode("1", "1")))), "0")
        }.also { store.put("details", it) }
        val artifacts = File(checkNotNull(targetContext.getExternalFilesDir("catalog-layout-probe")), "run-" + System.currentTimeMillis())
        val entries = ListParsingWindow.open((1..600).asSequence().map {
            SeriesEntry(it.toString(), "https://seasonvar.ru/serial-$it-Test.html", "Сериал $it", 1, "",
                "android.resource://ru.seasonvar.tv/drawable/poster_placeholder")
        })
        val options = CatalogFilterOptions(mapOf(
            "country" to listOf(FilterOption("all", "Любая страна"), FilterOption("Япония", "Япония")),
            "genres" to listOf(FilterOption("all", "Любой жанр"), FilterOption("1", "Комедия")),
            "year" to listOf(FilterOption("all", "Любой год"), FilterOption("2025", "2025")),
            "seasons" to listOf(FilterOption("all", "Любое число сезонов"), FilterOption("1", "1")),
            "rating" to listOf(FilterOption("all", "Любой рейтинг"), FilterOption("8", "8")),
            "voice" to listOf(FilterOption("all", "Любая озвучка"), FilterOption("Тест", "Тест")),
            "sort" to listOf(FilterOption("newest", "По добавлению"), FilterOption("name", "По названию")),
            "origin" to listOf(FilterOption("", "Все"), FilterOption("rus", "Отечественные"), FilterOption("eng", "Зарубежные")),
            "ratingSource" to listOf(FilterOption("kp", "КиноПоиск"), FilterOption("imdb", "IMDb")),
        ) + listOf("yearOp", "seasonsOp", "ratingOp").associateWith { listOf(FilterOption("=", "Равно"), FilterOption(">", "Больше"), FilterOption("<", "Меньше")) })
        val controller = object : BrowseCatalogController {
            override val state = MutableStateFlow(BrowseCatalogState(CatalogUiState(entries = entries, loaded = true, loading = false), options))
            var applied = 0; var resets = 0
            override fun activate() = Unit
            override fun refresh() = Unit
            override fun choose(key: String, value: String) { state.value = state.value.copy(draft = state.value.draft.with(key, value)) }
            override fun apply() { applied++; state.value = state.value.copy(applied = state.value.draft) }
            override fun reset() { resets++; state.value = state.value.copy(applied = CatalogFilters(), draft = CatalogFilters()) }
        }
        fun main(block: () -> Unit) = runOnMainSync(block)
        fun settle() { waitForIdleSync(); if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache() }
        fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString()?.lineSequence()?.any { it == text } == true || node.contentDescription?.toString() == text) return node
            for (i in 0 until node.childCount) find(node.getChild(i), text)?.let { return it }
            return null
        }
        fun control(text: String): AccessibilityNodeInfo? {
            fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                node ?: return null
                if (node.text?.toString()?.lineSequence()?.any { it == text } == true || node.contentDescription?.toString() == text) {
                    var candidate: AccessibilityNodeInfo = node
                    while (!candidate.isClickable) candidate = candidate.parent ?: break
                    if (candidate.isClickable) return candidate
                }
                for (i in 0 until node.childCount) search(node.getChild(i))?.let { return it }
                return null
            }
            return search(uiAutomation.rootInActiveWindow)
        }
        fun waitFor(predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10000
            while (!predicate()) { check(SystemClock.elapsedRealtime() < deadline) { "Timed out" }; Thread.sleep(25) }
        }
        fun key(code: Int) { sendKeyDownUpSync(code); settle() }
        fun focused(text: String) = control(text)?.isFocused == true
        fun capture(name: String) {
            check(artifacts.isDirectory || artifacts.mkdirs())
            val image = checkNotNull(uiAutomation.takeScreenshot())
            File(artifacts, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        }
        fun stage(name: String) { phase = name; sendStatus(Activity.RESULT_OK, Bundle().apply { putString("phase", name) }) }
        try {
            check(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.FINGERPRINT.contains("generic"))
            activity = startActivitySync(Intent(targetContext, MarkMenuPreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MarkMenuPreviewActivity
            main { activity.setContent { SeasonvarApp(PublicFeed.entries.associateWith {
                CatalogUiState(entries = entries.take(1), loaded = true, loading = false)
            }, {}, browseCatalog = controller, detailsModel = detailsModel, hiddenSections = Section.catalogPages.toSet() - Section.TODAY,
                loadDescription = { SeriesDescription(listOf("Описание выделенного сериала остаётся под фильтрами и над общим списком карточек.")) }) } }
            stage("open_catalog")
            waitFor { focused(targetContext.getString(R.string.today_title)) }
            key(KeyEvent.KEYCODE_DPAD_UP); key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor { focused("Страна") }; capture("catalog")
            ButtonAlignmentChecks.centered(this, "Страна"); ButtonAlignmentChecks.centered(this, "Применить")
            waitFor { find(uiAutomation.rootInActiveWindow, "Описание выделенного сериала остаётся под фильтрами и над общим списком карточек.") != null }
            stage("country_dialog")
            key(KeyEvent.KEYCODE_DPAD_CENTER); waitFor { find(uiAutomation.rootInActiveWindow, "Любая страна") != null }
            key(KeyEvent.KEYCODE_DPAD_CENTER); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_BACK); waitFor { focused("Страна: Япония") }; capture("country_selected")
            check(controller.state.value.applied["country"] == "all")
            key(KeyEvent.KEYCODE_DPAD_DOWN); waitFor { focused("Применить") }
            key(KeyEvent.KEYCODE_DPAD_CENTER); check(controller.applied == 1)
            repeat(5) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }; waitFor { focused("Сбросить") }
            key(KeyEvent.KEYCODE_DPAD_CENTER); check(controller.resets == 1); check(controller.state.value.applied["country"] == "all")
            stage("filter_dialogs")
            key(KeyEvent.KEYCODE_DPAD_UP)
            for ((index, name) in listOf("Рейтинг", "Сезоны", "Год", "Жанр").withIndex()) {
                stage(listOf("rating", "seasons", "year", "genres")[index])
                waitFor { focused(name) }; key(KeyEvent.KEYCODE_DPAD_CENTER);
                waitFor { focused(if (index == 0) "Источник рейтинга" else if (index == 3) "Комедия" else name) }
                capture("dialog_" + index)
                key(KeyEvent.KEYCODE_BACK); waitFor { control("Назад") == null && focused(name) }; key(KeyEvent.KEYCODE_DPAD_LEFT)
            }
            stage("window_navigation")
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN)
            stage("first_card")
            waitFor { focused("Сериал 1") }
            val headerBounds = Rect().also { checkNotNull(control("Применить")).getBoundsInScreen(it) }
            val originalBounds = Rect().also { checkNotNull(control("Сериал 1")).getBoundsInScreen(it) }
            repeat(199) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            stage("next_batch")
            waitFor { entries.window!!.state.value.entries.size == 500 }; capture("second_batch")
            val newHeaderBounds = Rect().also { checkNotNull(control("Применить")).getBoundsInScreen(it) }
            val nextCardBounds = Rect().also { checkNotNull(control("Сериал 200")).getBoundsInScreen(it) }
            check(headerBounds == newHeaderBounds && originalBounds.height() == nextCardBounds.height())
            key(KeyEvent.KEYCODE_DPAD_UP); waitFor { focused("Применить") }
            stage("return_card")
            key(KeyEvent.KEYCODE_DPAD_DOWN); waitFor { focused("Сериал 200") }
            stage("details_return")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor { find(uiAutomation.rootInActiveWindow, "Синтетическая карточка для проверки возврата фокуса.") != null }
            val searchFocusEvents = java.util.concurrent.atomic.AtomicInteger()
            uiAutomation.setOnAccessibilityEventListener { event ->
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED &&
                    find(event.source, targetContext.getString(R.string.search_title)) != null) searchFocusEvents.incrementAndGet()
            }
            key(KeyEvent.KEYCODE_BACK); waitFor { focused("Сериал 200") }
            uiAutomation.setOnAccessibilityEventListener(null)
            check(searchFocusEvents.get() == 0) { "Search focused during details return" }
            capture("details_return")
            key(KeyEvent.KEYCODE_BACK); waitFor { focused("Каталог") }
            stage("return_catalog")
            key(KeyEvent.KEYCODE_DPAD_CENTER); waitFor { focused("Страна") }
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN); waitFor { focused("Сериал 200") }
            result.putBoolean("window_bounded", true); result.putBoolean("focus_preserved", true)
            result.putBoolean("geometry_stable", true); result.putBoolean("text_centered", true)
            result.putBoolean("fixture_only", true); result.putBoolean("script_success", true)
            result.putString("artifacts", artifacts.absolutePath); finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putBoolean("script_success", false); result.putString("failure_type", error.javaClass.simpleName)
            result.putString("failure_phase", phase)
            result.putString("failure_line", error.stackTrace.firstOrNull { it.fileName == "BrowseCatalogLayoutProbe.kt" }?.lineNumber?.toString())
            fun describe(node: AccessibilityNodeInfo?): String = if (node == null) "" else
                "${node.className}|${node.text}|click=${node.isClickable}|focus=${node.isFocused}\n" +
                    (0 until node.childCount).joinToString("") { describe(node.getChild(it)) }
            check(artifacts.isDirectory || artifacts.mkdirs())
            File(artifacts, "tree.txt").writeText(describe(uiAutomation.rootInActiveWindow))
            capture("failure"); result.putString("artifacts", artifacts.absolutePath); finish(Activity.RESULT_CANCELED, result)
        } finally {
            uiAutomation.setOnAccessibilityEventListener(null)
            entries.window?.close(); activity?.let { main { it.finish() } }; main { store.clear() }
        }
    }
}
