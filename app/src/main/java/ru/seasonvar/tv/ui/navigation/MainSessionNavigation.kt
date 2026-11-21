package ru.seasonvar.tv.ui.navigation

import androidx.compose.runtime.*
import androidx.compose.ui.platform.SoftwareKeyboardController
import ru.seasonvar.tv.ui.search.SearchUiState
import ru.seasonvar.tv.BuildConfig
import ru.seasonvar.tv.data.account.AccountSession
import ru.seasonvar.tv.data.account.PersonalFeed

@Composable
internal fun MainStartupNavigation(startupTarget: Section?, pending: MutableState<Boolean>, section: MutableState<Section>,
    signedIn: Boolean, hiddenSections: Set<Section>, menuOrder: MenuOrder, focus: MainFocus, onSelect: (Section) -> Unit) {
    LaunchedEffect(startupTarget) {
        if (!pending.value) return@LaunchedEffect
        val target = startupTarget ?: Section.TODAY.availableAtStart(signedIn, hiddenSections, menuOrder)
        if (startupTarget != null) onSelect(target)
        repeat(2) { withFrameNanos { } }
        if (pending.value && section.value == target) {
            if (startupTarget != null) pending.value = false
            focus.sectionFocus(target).requestFocus()
            if (BuildConfig.BUILD_TYPE == "profile" && startupTarget != null)
                ru.seasonvar.tv.diagnostics.StartupDiagnostics.targetFocused(target)
        }
    }
    LaunchedEffect(Unit) {
        if (section.value.canHide && section.value in hiddenSections) {
            onSelect(section.value.availableAtStart(signedIn, hiddenSections, menuOrder))
        }
        if (!pending.value) {
            repeat(2) { withFrameNanos { } }
            focus.sectionFocus(section.value).requestFocus()
        }
    }
}

/** Выполняется в LaunchedEffect версии аккаунта; функции чтения возвращают актуальный экран после ожидания кадров. */
internal suspend fun syncMainAccountNavigation(account: AccountSession, hiddenSections: Set<Section>, menuOrder: MenuOrder, visiblePages: List<Section>,
    focus: MainFocus, positions: MainCatalogPositions, currentSection: () -> Section, menuOwnsInput: () -> Boolean,
    onClearPersonal: (Set<String>) -> Unit, onCloseDetails: () -> Unit, onRequireSignIn: () -> Unit, onSelect: (Section) -> Unit) {
    val menuTarget = currentSection().takeIf { menuOwnsInput() }
    val personalKeys = PersonalFeed.entries.map { it.catalogKey }.toSet() + "SMART_CONTINUE"
    onClearPersonal(personalKeys)
    focus.cards.keys.removeAll { it.substringBefore('|') in personalKeys }
    positions.resetPrivate()
    onCloseDetails()
    if (currentSection().requiresAccount && account.user == null) onRequireSignIn()
    if (currentSection() == Section.ACCOUNT && account.user != null) {
        val target = Section.RESUME.availableAtStart(true, hiddenSections, menuOrder)
        onSelect(target)
        repeat(2) { withFrameNanos { } }; focus.sectionFocus(target).requestFocus()
    }
    if (menuTarget != null) {
        repeat(2) { withFrameNanos { } }
        if (menuOwnsInput() && currentSection() == menuTarget) {
            focus.sectionFocus(menuTarget).requestFocus()
            if (menuTarget in visiblePages) focus.menuBringIntoView.getValue(menuTarget).bringIntoView()
        }
    }
}

@Composable
internal fun MainSearchNavigation(searching: Boolean, searchState: SearchUiState, pending: MutableState<String?>,
    focus: MainFocus, keyboard: SoftwareKeyboardController?, onEnterContent: () -> Unit) {
    LaunchedEffect(pending.value, searchState.showHistory, searchState.query, searchState.results.loading, searchState.results.loaded, searchState.results.failure) {
        if (!searchState.showHistory && pending.value == searchState.query && !searchState.results.loading &&
            (searchState.results.loaded || searchState.results.failure != null)) {
            pending.value = null
            keyboard?.hide()
            if (searchState.results.entries.isNotEmpty()) onEnterContent() else focus.refreshFocus.requestFocus()
        }
    }
    LaunchedEffect(searching, searchState.showHistory, searchState.focusHistoryOnShow) {
        if (!searching) pending.value = null
        else if (searchState.showHistory && searchState.focusHistoryOnShow) keyboard?.hide()
    }
}
