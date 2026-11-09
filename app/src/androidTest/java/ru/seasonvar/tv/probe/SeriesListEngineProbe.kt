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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.SeriesDescription
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.catalog.*
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object SeriesListEngineProbe {
    fun run(probe: Instrumentation, sharedCache: Boolean = false) = with(probe) {
        val result = Bundle()
        var activity: MarkMenuPreviewActivity? = null
        var phase = "start"
        val all = List(33000) { index -> SeriesEntry(index.toString(), "/$index", "Сериал " + index.toString().padStart(5, '0'),
            1, "", "android.resource://ru.seasonvar.tv/drawable/poster_placeholder") }
        val rows = mutableStateOf(all.take(100))
        val selected = mutableStateOf(all[0].key)
        val selectedEntry = mutableStateOf(all[0])
        val layout = mutableStateOf(CatalogLayout.STRIP)
        val enabled = mutableStateOf(true)
        val catalog = mutableStateOf("fixture")
        val cards = mutableMapOf<String, FocusRequester>()
        val menu = FocusRequester(); val header = FocusRequester()
        lateinit var engine: SeriesListEngine
        var initialized = false
        lateinit var scope: CoroutineScope
        lateinit var jump: (Int) -> Unit
        var imageLoads = 0; var opened = 0; var actions = 0
        val descriptionLoads = ConcurrentHashMap<String, AtomicInteger>()
        val backend = if (sharedCache) SharedCardCacheFixture(targetContext, descriptionLoads) else null
        val holder = Any()
        val artifacts = File(checkNotNull(targetContext.getExternalFilesDir(if (sharedCache) "shared-card-cache-probe" else "series-list-probe")), "run-" + System.currentTimeMillis())
        fun <T> main(block: () -> T): T {
            var value: Any? = null; var failure: Throwable? = null
            runOnMainSync { try { value = block() } catch (error: Throwable) { failure = error } }
            failure?.let { throw it }
            @Suppress("UNCHECKED_CAST") return value as T
        }
        fun waitFor(predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 10000
            while (!predicate()) { check(SystemClock.elapsedRealtime() < end) { "Timed out in $phase" }; Thread.sleep(35) }
        }
        fun stage(name: String) { phase = name; sendStatus(Activity.RESULT_OK, Bundle().apply { putString("phase", name) }) }
        fun settle() {
            Thread.sleep(250); waitForIdleSync()
            if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
        }
        fun key(code: Int) { sendKeyDownUpSync(code); settle() }
        fun capture(name: String) {
            check(artifacts.isDirectory || artifacts.mkdirs())
            val image = checkNotNull(uiAutomation.takeScreenshot())
            File(artifacts, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString()?.lineSequence()?.any { it == text } == true) return node
            for (index in 0 until node.childCount) find(node.getChild(index), text)?.let { return it }
            return null
        }
        fun button(text: String): AccessibilityNodeInfo? {
            var node = find(uiAutomation.rootInActiveWindow, text) ?: return null
            while (!node.isClickable && node.parent != null) node = node.parent
            return node
        }
        fun bounds(index: Int): Rect {
            waitFor { button(all[index].title) != null }
            return Rect().also { checkNotNull(button(all[index].title)).getBoundsInScreen(it) }
        }
        fun focus(index: Int) { waitFor { main { cards.containsKey(catalog.value + "|" + all[index].key) } }
            main { cards.getValue(catalog.value + "|" + all[index].key).requestFocus() }; settle() }
        fun ready() { waitFor { main {
            if (!initialized) false else {
                val expected = engine.window.preparationOrder(rows.value.indexOfFirst { it.key == selected.value }).size
                expected > 0 && engine.preparedCount == expected && engine.descriptionCount == expected
            }
        } } }
        try {
            check(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.FINGERPRINT.contains("generic"))
            activity = startActivitySync(Intent(targetContext, MarkMenuPreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MarkMenuPreviewActivity
            main { activity.setContent { SeasonvarTheme {
                scope = rememberCoroutineScope()
                val rowState = rememberLazyListState(); val gridState = rememberLazyGridState()
                engine = remember { SeriesListEngine(scope,
                    prepare = { SeriesCardContent(it, "Сезон 1") }, loadImage = { imageLoads++ },
                    permits = Semaphore(3), nextFrame = { withFrameNanos { } }, loadDescription = { entry ->
                        if (backend != null) backend.repository.description(entry) else {
                            descriptionLoads.getOrPut(entry.id) { AtomicInteger() }.incrementAndGet()
                            delay(80)
                            SeriesDescription(listOf("Описание: " + entry.title))
                        }
                    }, cachedDescription = { backend?.repository?.cachedDescription(it) },
                    retainData = { backend?.cards?.retain(holder, it) }) }
                initialized = true
                jump = { index -> scope.launch {
                    selected.value = all[index].key
                    selectedEntry.value = all[index]
                    if (layout.value == CatalogLayout.GRID) gridState.scrollToItem(index) else rowState.scrollToItem(index)
                    repeat(3) { withFrameNanos { } }
                    cards[catalog.value + "|" + all[index].key]?.requestFocus()
                } }
                BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    val d = SeasonvarTheme.dimensions
                    val metrics = rememberCatalogCardMetrics(maxWidth - d.spacingLarge * 2)
                    Column(Modifier.fillMaxSize().padding(d.spacingLarge)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                            ThemeButton("Меню", {}, Modifier.focusRequester(menu))
                            ThemeButton("Вид списка", {}, Modifier.focusRequester(header))
                        }
                        Spacer(Modifier.height(d.spacingSmall))
                        if (layout.value == CatalogLayout.STRIP) {
                            Column(Modifier.weight(1f)) {
                                androidx.tv.material3.Text("Проверка общего списка: " + rows.value.size,
                                    color = MaterialTheme.colorScheme.onBackground)
                                val description = engine.description(selectedEntry.value)
                                androidx.tv.material3.Text(when {
                                    description.loading -> "Загрузка описания…"
                                    description.failed -> "Ошибка описания"
                                    else -> description.description?.paragraphs?.joinToString("\n").orEmpty()
                                }, color = MaterialTheme.colorScheme.onBackground)
                            }
                        }
                        SeriesList(rows.value, catalog.value, selected.value, layout.value, rowState, gridState,
                            cards, header, menu, metrics, enabled.value,
                            if (layout.value == CatalogLayout.GRID) Modifier.fillMaxWidth().weight(1f)
                            else Modifier.fillMaxWidth().height(metrics.rowHeight),
                            onFocused = { selected.value = it.key; selectedEntry.value = it }, onOpen = { opened++ }, onActions = { actions++ }, engine = engine)
                    }
                }
            } } }
            stage("small_list")
            ready(); focus(0); val original = bounds(0)
            val preparedBeforeAppend = main { engine.preparedCount }; var imagesBeforeAppend = main { imageLoads }
            var descriptionsBeforeAppend = descriptionLoads.values.sumOf { it.get() }
            capture("01-small-strip")
            if (backend != null) {
                stage("shared_lists")
                val originalDescription = backend.repository.cachedDescription(all[0])
                main { rows.value = all.drop(3).take(100); selected.value = all[3].key; selectedEntry.value = all[3]; catalog.value = "list_b" }
                settle(); ready(); focus(3)
                val count = descriptionLoads.values.sumOf { it.get() }
                main { rows.value = all.take(100); selected.value = all[0].key; selectedEntry.value = all[0]; catalog.value = "fixture" }
                check(main { !engine.description(all[0]).loading })
                settle(); ready(); focus(0)
                check(backend.repository.cachedDescription(all[0]) === originalDescription)
                check(descriptionLoads.values.sumOf { it.get() } == count)
                result.putBoolean("native_shared", true)
                capture("01-shared-return")
                imagesBeforeAppend = main { imageLoads }
                descriptionsBeforeAppend = descriptionLoads.values.sumOf { it.get() }
            }
            stage("append_full_data")
            main { rows.value = all }; settle(); ready()
            check(main { engine.preparedCount == preparedBeforeAppend && imageLoads == imagesBeforeAppend && selected.value == all[0].key })
            check(descriptionLoads.values.sumOf { it.get() } == descriptionsBeforeAppend)
            check(bounds(0) == original && button(all[0].title)?.isFocused == true)
            check(main { cards.size < 100 && rows.value.size == 33000 })
            result.putBoolean("append_bounded", true)
            stage("strip_navigation")
            check(main { !engine.description(all[1]).loading })
            repeat(12) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }; ready()
            check(descriptionLoads["12"]?.get() == 1)
            check(main { selected.value == all[12].key && engine.preparedCount < 100 && cards.size < 100 })
            key(KeyEvent.KEYCODE_DPAD_CENTER); check(main { opened == 1 })
            sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)); Thread.sleep(800)
            sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)); settle()
            check(main { actions == 1 && opened == 1 })
            val focusedBounds = bounds(12)
            stage("disable_prefetch")
            main { enabled.value = false }; settle(); ready()
            check(main { engine.window.ahead.isEmpty() && engine.preparedCount == engine.window.visible.count() &&
                engine.descriptionCount == engine.window.visible.count() })
            check(bounds(12) == focusedBounds && button(all[12].title)?.isFocused == true)
            val stopped = main { imageLoads }
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }; ready(); check(main { imageLoads == stopped })
            capture("02-prefetch-off")
            result.putBoolean("geometry_stable", true); result.putBoolean("prefetch_toggle", true)
            stage("far_jump")
            main { enabled.value = true; jump(20000) }; settle(); ready(); focus(20000)
            check(main { engine.preparedCount < 100 && cards.size < 100 && selected.value == all[20000].key })
            capture("03-far-strip")
            stage("grid_navigation")
            main { layout.value = CatalogLayout.GRID }; settle(); main { jump(20000) }; settle(); ready(); focus(20000)
            key(KeyEvent.KEYCODE_DPAD_DOWN); ready()
            check(main { selected.value != all[20000].key && engine.preparedCount < 150 && cards.size < 100 })
            capture("04-large-grid")
            main { jump(0) }; settle(); ready(); focus(0)
            key(KeyEvent.KEYCODE_DPAD_UP); check(button("Вид списка")?.isFocused == true)
            focus(0); key(KeyEvent.KEYCODE_DPAD_LEFT); check(button("Меню")?.isFocused == true)
            result.putBoolean("focus_preserved", true)
            stage("replace_source")
            main { rows.value = all.take(7); selected.value = all[0].key; selectedEntry.value = all[0]; catalog.value = "other"; layout.value = CatalogLayout.STRIP }
            settle(); ready(); focus(0)
            check(main { engine.preparedCount <= 7 && cards.keys.all { it.startsWith("other|") } })
            result.putBoolean("window_bounded", true); result.putBoolean("fixture_only", true)
            result.putBoolean("descriptions_prefetched", true)
            if (backend != null) {
                stage("private_clear")
                runBlocking { backend.verifyLogout(all[0]) }
                check(main { !engine.description(all[0]).loading })
                result.putBoolean("private_cleared", true)
                stage("memory_capacity")
                result.putString("cache_heap_growth_kib", runBlocking { backend.measureCapacity() }.toString())
                result.putString("cache_records", backend.cards.size.toString())
                result.putBoolean("memory_bounded", true)
            }
            result.putBoolean("script_success", true); result.putString("artifacts", artifacts.absolutePath)
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putBoolean("script_success", false); result.putString("failure_type", error.javaClass.simpleName)
            result.putString("failure_phase", phase)
            result.putString("failure_line", error.stackTrace.firstOrNull { it.fileName == "SeriesListEngineProbe.kt" }?.lineNumber?.toString().orEmpty())
            capture("failure"); result.putString("artifacts", artifacts.absolutePath)
            fun describe(node: AccessibilityNodeInfo?, depth: Int = 0): String {
                node ?: return "null"
                return " ".repeat(depth) + listOf(node.className, node.text, node.contentDescription, node.isFocused).joinToString("|") + "\n" +
                    (0 until node.childCount).joinToString("") { describe(node.getChild(it), depth + 1) }
            }
            File(artifacts, "accessibility.txt").writeText(describe(uiAutomation.rootInActiveWindow))
            finish(Activity.RESULT_CANCELED, result)
        } finally { activity?.let { main { it.finish() } }; backend?.close() }
    }
}
