package ru.seasonvar.tv.probe

import android.content.Intent
import android.graphics.Rect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.*
import ru.seasonvar.tv.data.network.NetworkConfiguration
import ru.seasonvar.tv.ui.SeasonvarApp
import ru.seasonvar.tv.ui.account.MarkRemovalUiState
import ru.seasonvar.tv.ui.account.RemovalStage
import ru.seasonvar.tv.ui.catalog.*
import ru.seasonvar.tv.ui.navigation.Section
import ru.seasonvar.tv.ui.settings.InterfaceTheme

/** Один экран проходит обновление и возвраты без пересоздания состояния списка. */
internal class ListRefreshChecks(
    private val ui: ListRefreshProbeSession,
    private val fixture: ListRefreshFixtures,
    private val theme: InterfaceTheme,
) {
    data class StripReference(val title: String, val bounds: Rect)
    private val readyThumbnails = mutableSetOf<String>()
    private val observedRefresh = mutableListOf<Pair<String, Boolean>>()
    private var observingRefresh = false
    private var observedKeys: Set<String>? = null
    private val removal = mutableStateOf(MarkRemovalUiState())

    private fun thumbnail(key: String, ready: Boolean) {
        if (observingRefresh && key in readyThumbnails && observedKeys?.contains(key) != false) observedRefresh.add(key to ready)
        if (ready) readyThumbnails.add(key)
    }

    fun render() = with(ui) { with(fixture) {
        main { activity.setContent {
            CompositionLocalProvider(LocalThumbnailObserver provides ::thumbnail) {
                val current = pages.mapValues { (_, state) -> state.collectAsState().value }
                SeasonvarApp(PublicFeed.entries.associateWith { feed -> current.getValue(sections.first { it.publicFeed == feed }) }, {},
                    startPage = Section.TOP, interfaceTheme = theme, imagePrefetchEnabled = false,
                    catalogLayouts = if (grid.value) sections.associateWith { CatalogLayout.GRID } else emptyMap(),
                    detailsModel = model, loadDescription = { SeriesDescription(listOf("Синтетическое описание.")) },
                    accountSession = AccountSession(AccountUser("12", "Проверка", null), 1),
                    personalStates = PersonalFeed.entries.associateWith { feed -> current.getValue(sections.first { it.personalFeed == feed }) },
                    smartState = current.getValue(Section.SMART_CONTINUE),
                    removalState = removal.value,
                    onRemovalActions = { entry, _ -> removal.value = MarkRemovalUiState(entry, RemovalStage.CONFIRM, visible = true) },
                    onRemovalConfirm = { removal.value = MarkRemovalUiState() },
                    onFeedVisible = { feed -> visit(sections.first { it.publicFeed == feed }) },
                    onPersonalVisible = { feed -> feed?.let { visit(sections.first { it.personalFeed == feed }) } },
                    onSmartVisible = { if (it) visit(Section.SMART_CONTINUE) },
                    onListVisibilityChange = { coordinator.setVisible(it); shown.set(it) })
            }
        } }
    } }

    fun selectStrip(): StripReference = with(ui) {
        phase = "visit_lists"; settle()
        check(fixture.shown.get() == Section.TOP)
        menu(Section.NEWEST); menu(Section.WANT); menu(Section.TOP)
        check(fixture.shown.get() == Section.TOP)
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); settle()
        repeat(7) { sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT) }; settle()
        val selected = checkNotNull(focusedCard())
        val title = checkNotNull(find(selected) { it.text?.toString()?.startsWith("Карточка ") == true }?.text).toString()
        val original = settledCardBounds(title)
        capture("01-before")
        StripReference(title, original)
    }

    /** Барьеры подготовки и ответа завершаются здесь; последующие сценарии используют готовую сеть. */
    fun refreshAndReturn(reference: StripReference) = with(ui) { with(fixture) {
        val (title, original) = reference
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
        main {
            check(readyThumbnails.size >= 5)
            observingRefresh = true
            responseGate.complete(Unit)
        }; settle()
        main {
            observingRefresh = false
            check(observedRefresh.isEmpty()) { "Unchanged thumbnail visuals recomposed: ${observedRefresh.size}, ready=${observedRefresh.count { it.second }}" }
        }
        check(card(title)?.isFocused == true && bounds(checkNotNull(card(title))) == original)
        capture("02-refreshed")
        val after = counts.toMap()
        main { coordinator.networkChanged(NetworkConfiguration(bypassEnabled = false)) }; settle()
        check(counts == after)
        phase = "details"
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); settle(); check(fixture.shown.get() == null)
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle()
        check(fixture.shown.get() == Section.TOP && card(title)?.isFocused == true)
        check(bounds(checkNotNull(card(title))) == original)
        phase = "settings_background_refresh"
        menu(Section.SETTINGS); check(fixture.shown.get() == null)
        val hiddenCounts = counts.toMap()
        main { coordinator.networkChanged(NetworkConfiguration()) }; settle()
        check(visited.all { counts[it] == hiddenCounts.getValue(it) + 1 })
        check((sections - visited).all { counts[it] == 0 })
        menu(Section.TOP); sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); settle()
        check(card(title)?.isFocused == true && bounds(checkNotNull(card(title))) == original)
        capture("03-returned")
        result.putInt("visited_lists", visited.size)
    } }

    fun menuNavigation() = with(ui) {
        phase = "menu_navigation"
        menu(Section.TODAY)
        val listSections = Section.startPages(true).filter { it.publicFeed != null || it.personalFeed != null || it == Section.SMART_CONTINUE }
        for ((index, section) in listSections.withIndex()) {
            if (index > 0) { sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); settle() }
            check(fixture.shown.get() == section) { "Focus did not select $section" }
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); settle()
            check(fixture.shown.get() == section && focusedCard() != null) { "OK did not enter $section" }
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); settle()
            check(fixture.shown.get() == null)
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle()
            check(fixture.shown.get() == section && focusedCard() != null) { "Details did not return to $section" }
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle()
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); settle()
            check(fixture.shown.get() == section && focusedCard() != null) { "Right did not enter $section" }
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle()
        }
        for (label in listOf(R.string.start_page_title, R.string.about_github)) {
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); settle()
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); settle()
            check(find(uiAutomation.rootInActiveWindow) { node ->
                node.isFocused && find(node) { it.text?.toString() == targetContext.getString(label) } != null
            } != null) { "System page focus missing: $label" }
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle()
        }
        result.putBoolean("menu_navigation", true)
    }

    fun gridReturn() = with(ui) { with(fixture) {
        main { grid.value = true }; settle()
        for (section in listOf(Section.TOP, Section.WANT)) {
            menu(section); sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); settle()
            phase = "grid_return_" + section.storageId
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); settle()
            val selectedGrid = checkNotNull(focusedCard())
            val gridTitle = checkNotNull(find(selectedGrid) { it.text?.toString()?.startsWith("Карточка ") == true }?.text).toString()
            val gridBounds = settledCardBounds(gridTitle)
            capture("grid-before-" + section.storageId)
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); settle(); check(fixture.shown.get() == null)
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle()
            val returnedBounds = settledCardBounds(gridTitle)
            check(card(gridTitle)?.isFocused == true && returnedBounds == gridBounds) {
                "before=$gridBounds after=$returnedBounds focused=${card(gridTitle)?.isFocused}"
            }
            capture("grid-return-" + section.storageId)
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle()
        }
        main { grid.value = false }; settle()
        result.putBoolean("grid_return", true)
    } }

    fun removeMarks() = with(ui) { with(fixture) {
        for (section in listOf(Section.WANT, Section.WATCHED, Section.SMART_CONTINUE, Section.TOP)) {
            val removeLast = section == Section.WATCHED
            val original = if (removeLast) rows.take(3) else rows
            val page = pages.getValue(section)
            menu(section)
            phase = "mark_removal_" + section.storageId
            main {
                grid.value = removeLast
                page.value = page.value.copy(entries = original)
            }
            settle()
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); settle()
            repeat(if (removeLast) 2 else 3) { sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT) }; settle()
            val selected = checkNotNull(focusedCard())
            val index = original.indexOfFirst { entry -> find(selected) { it.text?.toString() == entry.title } != null }
            check(if (removeLast) index == original.lastIndex else index in 1 until original.lastIndex)
            val removed = original[index]
            val neighbor = original[if (removeLast) index - 1 else index + 1]
            check(selected.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)); settle()
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); settle()
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); settle()
            check(!removal.value.visible && card(removed.title)?.isFocused == true)

            val visibleSurvivors = original.filter { it.key != removed.key && card(it.title) != null }.map { it.key }.toSet()
            check(visibleSurvivors.isNotEmpty())
            main {
                observedRefresh.clear()
                observedKeys = visibleSurvivors
                observingRefresh = true
                page.value = page.value.copy(entries = original.filterNot { it.key == removed.key })
            }
            settle()
            check(card(removed.title) == null && card(neighbor.title)?.isFocused == true) {
                "Focus did not move from ${removed.title} to ${neighbor.title}"
            }
            main {
                observingRefresh = false
                observedKeys = null
            }
            val unprepared = observedRefresh.filterNot { it.second }.map { it.first }
            check(unprepared.isEmpty()) { "Visible prepared thumbnails disappeared: $unprepared" }
            capture("removed-mark-" + section.storageId)
        }
        result.putBoolean("mark_removal_focus", true)
    } }

    fun activityVisibility() = with(ui) {
        menu(Section.TOP)
        phase = "activity_visibility"
        cover = startActivitySync(Intent(targetContext, MarkMenuPreviewActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        settle(); check(fixture.shown.get() == null)
        main { cover!!.finish() }; settle(); check(fixture.shown.get() == Section.TOP)
    }
}
