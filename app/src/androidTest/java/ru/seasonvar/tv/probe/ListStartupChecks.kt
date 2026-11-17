package ru.seasonvar.tv.probe

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import ru.seasonvar.tv.data.PublicFeed
import ru.seasonvar.tv.data.account.*
import ru.seasonvar.tv.ui.SeasonvarApp
import ru.seasonvar.tv.ui.catalog.CatalogUiState
import ru.seasonvar.tv.ui.navigation.Section
import ru.seasonvar.tv.ui.settings.InterfaceTheme

internal class ListStartupChecks(
    private val ui: ListRefreshProbeSession,
    private val fixture: ListRefreshFixtures,
    private val theme: InterfaceTheme,
) {
    fun run() = with(ui) {
        startupCase(Section.RESUME, signedIn = true)
        startupCase(Section.RESUME, signedIn = false)
        startupCase(Section.RESUME, signedIn = true, interrupt = true)
        startupCase(Section.SETTINGS, signedIn = true)
        startupCase(Section.TOP, signedIn = true)
        result.putBoolean("startup_selection", true)
    }

    private fun startupCase(start: Section, signedIn: Boolean, interrupt: Boolean = false) = with(ui) {
        phase = "startup_" + start.storageId
        val restoring = mutableStateOf(true)
        val opened = linkedSetOf<Section>()
        main { activity.setContent {
            key(start, signedIn, interrupt) {
                SeasonvarApp(PublicFeed.entries.associateWith { CatalogUiState(entries = fixture.rows, loaded = true, loading = false) }, {},
                    startPage = start, interfaceTheme = theme, imagePrefetchEnabled = false,
                    accountState = ru.seasonvar.tv.ui.account.AccountUiState(busy = restoring.value),
                    accountSession = if (!restoring.value && signedIn) AccountSession(AccountUser("12", "Проверка", null), 1) else AccountSession(),
                    personalStates = PersonalFeed.entries.associateWith { CatalogUiState(entries = fixture.rows, loaded = true, loading = false) },
                    onFeedVisible = { feed -> opened += fixture.sections.first { it.publicFeed == feed } },
                    onPersonalVisible = { feed -> feed?.let { opened += fixture.sections.first { it.personalFeed == feed } } })
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
}
