package ru.seasonvar.tv.probe

import android.content.Intent
import android.graphics.Rect
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.*
import ru.seasonvar.tv.data.network.NetworkConfiguration
import ru.seasonvar.tv.ui.SeasonvarApp
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

    fun render() = with(ui) { with(fixture) {
        main { activity.setContent {
            val current = pages.mapValues { (_, state) -> state.collectAsState().value }
            SeasonvarApp(PublicFeed.entries.associateWith { feed -> current.getValue(sections.first { it.publicFeed == feed }) }, {},
                startPage = Section.TOP, interfaceTheme = theme, imagePrefetchEnabled = false,
                catalogLayouts = if (grid.value) sections.associateWith { CatalogLayout.GRID } else emptyMap(),
                detailsModel = model, loadDescription = { SeriesDescription(listOf("Синтетическое описание.")) },
                accountSession = AccountSession(AccountUser("12", "Проверка", null), 1),
                personalStates = PersonalFeed.entries.associateWith { feed -> current.getValue(sections.first { it.personalFeed == feed }) },
                smartState = current.getValue(Section.SMART_CONTINUE),
                onFeedVisible = { feed -> visit(sections.first { it.publicFeed == feed }) },
                onPersonalVisible = { feed -> feed?.let { visit(sections.first { it.personalFeed == feed }) } },
                onSmartVisible = { if (it) visit(Section.SMART_CONTINUE) },
                onListVisibilityChange = { coordinator.setVisible(it); shown.set(it) })
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
        val original = bounds(selected)
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
        main { responseGate.complete(Unit) }; settle()
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

    fun activityVisibility() = with(ui) {
        menu(Section.TOP)
        phase = "activity_visibility"
        cover = startActivitySync(Intent(targetContext, MarkMenuPreviewActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        settle(); check(fixture.shown.get() == null)
        main { cover!!.finish() }; settle(); check(fixture.shown.get() == Section.TOP)
    }
}
