package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.*
import ru.seasonvar.tv.data.network.NetworkConfiguration
import ru.seasonvar.tv.ui.SeasonvarApp
import ru.seasonvar.tv.ui.catalog.*
import ru.seasonvar.tv.ui.details.SeriesDetailsViewModel
import ru.seasonvar.tv.ui.navigation.Section
import ru.seasonvar.tv.ui.sectionTitle
import ru.seasonvar.tv.ui.settings.InterfaceTheme
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object ListRefreshProbe {
    fun run(probe: Instrumentation, theme: InterfaceTheme) = with(probe) {
        val result = Bundle()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val store = ViewModelStore()
        var activity: MarkMenuPreviewActivity? = null
        var cover: Activity? = null
        var phase = "start"
        val artifacts = File(checkNotNull(targetContext.getExternalFilesDir("list-refresh-probe")), "run-" + System.currentTimeMillis())
        val rows = (1..24).map { SeriesEntry(it.toString(), "https://seasonvar.ru/serial-$it-fixture.html",
            "Карточка ${it.toString().padStart(2, '0')}", 1, "", "android.resource://ru.seasonvar.tv/drawable/poster_placeholder",
            previewDescription = "Синтетическое описание для проверки фонового обновления.") }
        val sections = Section.entries.filter { it.publicFeed != null || it.personalFeed != null ||
            it == Section.SEARCH || it == Section.SMART_CONTINUE || it == Section.CATALOG }
        val pages = sections.associateWith { MutableStateFlow(CatalogUiState(loading = false)) }
        val visited = linkedSetOf<Section>()
        val counts = sections.associateWith { 0 }.toMutableMap()
        val jobs = mutableMapOf<Section, Job>()
        val shown = AtomicReference<Section?>()
        var prepareGate = CompletableDeferred<Unit>()
        var responseGate = CompletableDeferred<Unit>()
        fun visit(section: Section) {
            if (visited.add(section)) pages.getValue(section).value = CatalogUiState(entries = rows, loaded = true, loading = false)
        }
        val coordinator = ListRefreshCoordinator(scope, NetworkConfiguration(), { prepareGate.await() }, sections.associateWith { section ->
            ListRefreshCoordinator.Target({ jobs.remove(section)?.cancel() }, {
                if (section in visited) {
                    counts[section] = counts.getValue(section) + 1
                    val state = pages.getValue(section)
                    state.value = state.value.copy(loading = true)
                    jobs[section] = scope.launch {
                        responseGate.await()
                        state.value = state.value.copy(loading = false, entries = rows.map {
                            it.copy(previewDescription = "Обновлённое описание для проверки фонового обновления.") })
                    }
                }
            })
        })
        val details = SeriesDetails(rows.first(), "100", listOf("Синтетическое описание."), emptyList(), emptyList(),
            listOf(SeasonOption("1", rows.first().url, "Сезон 1", 1)),
            listOf(Translation("0", "Стандартный", listOf(Episode("1", "1")))), "0")
        val model = SeriesDetailsViewModel { entry -> details.copy(entry = entry) }
        fun main(block: () -> Unit) = runOnMainSync(block)
        fun settle() {
            val frames = CountDownLatch(1)
            main { android.view.Choreographer.getInstance().postFrameCallback {
                android.view.Choreographer.getInstance().postFrameCallback { frames.countDown() }
            } }
            check(frames.await(5, TimeUnit.SECONDS))
            waitForIdleSync(); uiAutomation.waitForIdle(250, 5000)
            if (android.os.Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache()
        }
        fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            node ?: return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
            return null
        }
        fun card(title: String) = find(uiAutomation.rootInActiveWindow) { node ->
            node.isClickable && find(node) { it.text?.toString() == title } != null
        }
        fun focusedCard() = find(uiAutomation.rootInActiveWindow) { node ->
            node.isClickable && node.isFocused && find(node) { it.text?.toString()?.startsWith("Карточка ") == true } != null
        }
        fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
        fun capture(name: String) {
            check(artifacts.isDirectory || artifacts.mkdirs())
            val bitmap = checkNotNull(uiAutomation.takeScreenshot())
            File(artifacts, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun menu(section: Section) {
            phase = "menu_" + section.storageId
            val label = targetContext.getString(sectionTitle(section))
            fun control() = find(uiAutomation.rootInActiveWindow) { node ->
                node.isClickable && bounds(node).right <= bounds(checkNotNull(uiAutomation.rootInActiveWindow)).width() / 3 &&
                    find(node) { it.text?.toString() == label } != null
            }
            var node = control()
            if (node == null) {
                val labels = Section.startPages(true).map { targetContext.getString(sectionTitle(it)) }.toSet()
                val anchor = find(uiAutomation.rootInActiveWindow) { candidate ->
                    candidate.isClickable && bounds(candidate).right <= bounds(checkNotNull(uiAutomation.rootInActiveWindow)).width() / 3 &&
                        find(candidate) { it.text?.toString() in labels } != null
                }
                check(checkNotNull(anchor).performAction(AccessibilityNodeInfo.ACTION_FOCUS)); settle()
                for (direction in listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP)) {
                    repeat(20) {
                        if (node == null) {
                            sendKeyDownUpSync(direction); settle()
                            node = control()
                        }
                    }
                }
            }
            node?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id); settle()
            node = control()
            check(checkNotNull(node) { label }.performAction(AccessibilityNodeInfo.ACTION_FOCUS)); settle()
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); settle()
        }
        try {
            check(android.os.Build.MODEL.contains("sdk", true))
            main { store.put("details", model) }
            activity = startActivitySync(Intent(targetContext, MarkMenuPreviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MarkMenuPreviewActivity
            fun startupCase(start: Section, signedIn: Boolean, interrupt: Boolean = false) {
                phase = "startup_" + start.storageId
                val restoring = mutableStateOf(true)
                val opened = linkedSetOf<Section>()
                main { activity!!.setContent {
                    key(start, signedIn, interrupt) {
                        SeasonvarApp(PublicFeed.entries.associateWith { CatalogUiState(entries = rows, loaded = true, loading = false) }, {},
                            startPage = start, interfaceTheme = theme, imagePrefetchEnabled = false,
                            accountState = ru.seasonvar.tv.ui.account.AccountUiState(busy = restoring.value),
                            accountSession = if (!restoring.value && signedIn) AccountSession(AccountUser("12", "Проверка", null), 1) else AccountSession(),
                            personalStates = PersonalFeed.entries.associateWith { CatalogUiState(entries = rows, loaded = true, loading = false) },
                            onFeedVisible = { feed -> opened += sections.first { it.publicFeed == feed } },
                            onPersonalVisible = { feed -> feed?.let { opened += sections.first { it.personalFeed == feed } } })
                    }
                } }
                settle()
                check(opened == setOfNotNull(start.publicFeed?.let { start })) { "Unexpected startup activation: $opened" }
                if (interrupt) {
                    sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); settle()
                    check(opened == setOf(Section.TODAY))
                }
                main { restoring.value = false }; settle()
                val expected = if (interrupt || start.requiresAccount && !signedIn) Section.TODAY else start
                check(opened == setOfNotNull(expected.takeIf { it.publicFeed != null || it.personalFeed != null })) {
                    "Unexpected resolved startup: $opened"
                }
            }
            startupCase(Section.RESUME, signedIn = true)
            startupCase(Section.RESUME, signedIn = false)
            startupCase(Section.RESUME, signedIn = true, interrupt = true)
            startupCase(Section.SETTINGS, signedIn = true)
            startupCase(Section.TOP, signedIn = true)
            result.putBoolean("startup_selection", true)
            main { activity!!.setContent {
                val current = pages.mapValues { (_, state) -> state.collectAsState().value }
                SeasonvarApp(PublicFeed.entries.associateWith { feed -> current.getValue(sections.first { it.publicFeed == feed }) }, {},
                    startPage = Section.TOP, interfaceTheme = theme, imagePrefetchEnabled = false,
                    detailsModel = model, loadDescription = { SeriesDescription(listOf("Синтетическое описание.")) },
                    accountSession = AccountSession(AccountUser("12", "Проверка", null), 1),
                    personalStates = PersonalFeed.entries.associateWith { feed -> current.getValue(sections.first { it.personalFeed == feed }) },
                    smartState = current.getValue(Section.SMART_CONTINUE),
                    onFeedVisible = { feed -> visit(sections.first { it.publicFeed == feed }) },
                    onPersonalVisible = { feed -> feed?.let { visit(sections.first { it.personalFeed == feed }) } },
                    onSmartVisible = { if (it) visit(Section.SMART_CONTINUE) },
                    onListVisibilityChange = { coordinator.setVisible(it); shown.set(it) })
            } }
            phase = "visit_lists"; settle()
            check(shown.get() == Section.TOP)
            menu(Section.NEWEST); menu(Section.WANT); menu(Section.TOP)
            check(shown.get() == Section.TOP)
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); settle()
            repeat(7) { sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT) }; settle()
            val selected = checkNotNull(focusedCard())
            val title = checkNotNull(find(selected) { it.text?.toString()?.startsWith("Карточка ") == true }?.text).toString()
            val original = bounds(selected)
            capture("01-before")
            phase = "prepare_then_refresh"
            val firstCounts = counts.toMap()
            main { coordinator.networkChanged(NetworkConfiguration(bypassEnabled = false)) }
            settle(); check(counts == firstCounts)
            main { prepareGate.complete(Unit) }; settle()
            check(visited.all { counts[it] == firstCounts.getValue(it) + 1 })
            check((sections - visited).all { counts[it] == 0 })
            check(card(title)?.isFocused == true && bounds(checkNotNull(card(title))) == original) {
                "before=$original after=${card(title)?.let(::bounds)} focused=${card(title)?.isFocused}"
            }
            main { responseGate.complete(Unit) }; settle()
            check(card(title)?.isFocused == true && bounds(checkNotNull(card(title))) == original)
            capture("02-refreshed")
            val after = counts.toMap()
            main { coordinator.networkChanged(NetworkConfiguration(bypassEnabled = false)) }; settle()
            check(counts == after)
            phase = "details"
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); settle(); check(shown.get() == null)
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle()
            check(shown.get() == Section.TOP && card(title)?.isFocused == true)
            check(bounds(checkNotNull(card(title))) == original)
            phase = "settings_background_refresh"
            menu(Section.SETTINGS); check(shown.get() == null)
            val hiddenCounts = counts.toMap()
            main { coordinator.networkChanged(NetworkConfiguration()) }; settle()
            check(visited.all { counts[it] == hiddenCounts.getValue(it) + 1 })
            check((sections - visited).all { counts[it] == 0 })
            menu(Section.TOP); sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); settle()
            check(card(title)?.isFocused == true && bounds(checkNotNull(card(title))) == original)
            capture("03-returned")
            phase = "activity_visibility"
            cover = startActivitySync(Intent(targetContext, MarkMenuPreviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
            settle(); check(shown.get() == null)
            main { cover!!.finish() }; settle(); check(shown.get() == Section.TOP)
            result.putBoolean("list_visibility", true)
            result.putBoolean("background_refresh_preserved", true)
            result.putBoolean("single_refresh", true)
            result.putBoolean("network_prepared_first", true)
            result.putInt("visited_lists", visited.size)
            result.putInt("refresh_dispatches", firstCounts.keys.sumOf { counts.getValue(it) })
            result.putBoolean("geometry_stable", true); result.putBoolean("focus_preserved", true)
            result.putBoolean("fixture_only", true); result.putBoolean("script_success", true)
            result.putString("artifacts", artifacts.absolutePath)
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            runCatching { capture("failure") }
            fun describe(node: AccessibilityNodeInfo?, depth: Int = 0): String {
                node ?: return ""
                return " ".repeat(depth) + listOf(node.className, node.text, node.contentDescription, node.isFocused, node.isClickable)
                    .joinToString("|") + "\n" + (0 until node.childCount).joinToString("") { describe(node.getChild(it), depth + 1) }
            }
            runCatching { File(artifacts, "accessibility.txt").writeText(describe(uiAutomation.rootInActiveWindow)) }
            result.putString("failure_phase", phase); result.putString("failure_type", error.javaClass.simpleName)
            result.putString("failure_detail", error.message)
            result.putInt("failure_line", error.stackTrace.firstOrNull { it.fileName == "ListRefreshProbe.kt" }?.lineNumber ?: -1)
            result.putString("artifacts", artifacts.absolutePath)
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            main { cover?.finish(); activity?.finish(); store.clear(); scope.cancel() }
        }
    }
}
