package ru.seasonvar.tv.probe

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import ru.seasonvar.tv.data.PublicFeed
import ru.seasonvar.tv.data.account.*
import ru.seasonvar.tv.ui.SeasonvarApp
import ru.seasonvar.tv.ui.account.AccountUiState
import ru.seasonvar.tv.ui.catalog.CatalogUiState
import ru.seasonvar.tv.ui.catalog.LoadFailure
import ru.seasonvar.tv.ui.navigation.PersonalStartPreview
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
        previewCase(expired = false)
        previewCase(expired = true)
        smartPreviewCase()
        result.putBoolean("startup_selection", true)
    }

    private fun previewCase(expired: Boolean) = with(ui) {
        phase = "startup_cached_personal"
        val account = mutableStateOf(AccountSession())
        val restoring = mutableStateOf(true)
        val cached = CatalogUiState(entries = fixture.rows, loaded = true, loading = false, cached = true)
        val live = mutableStateOf(CatalogUiState(loading = false))
        val cachedPreview = PersonalStartPreview(Section.RESUME, "12", cached)
        val preview = mutableStateOf(cachedPreview.takeIf { expired })
        val publicLoads = mutableListOf<PublicFeed>()
        val visible = java.util.concurrent.atomic.AtomicReference<Section?>()
        main { activity.setContent {
            key(expired) {
                SeasonvarApp(PublicFeed.entries.associateWith { CatalogUiState(loading = false) }, {},
                    startPage = Section.RESUME, interfaceTheme = theme, imagePrefetchEnabled = false,
                    detailsModel = fixture.model, accountSession = account.value,
                    accountState = AccountUiState(busy = restoring.value), personalStartPreview = preview.value,
                    personalStates = mapOf(PersonalFeed.RESUME to live.value),
                    onFeedVisible = { publicLoads += it }, onListVisibilityChange = { visible.set(it) })
            }
        } }
        settle()
        if (!expired) {
            check(card(fixture.rows.first().title) == null)
            main { preview.value = cachedPreview }; settle()
        }
        check(card(fixture.rows.first().title) != null && publicLoads.isEmpty())
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); settle()
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); settle()
        val selected = fixture.rows[1].title
        check(card(selected)?.isFocused == true)
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); settle()
        check(card(selected)?.isFocused == true)
        capture(if (expired) "startup-cached-before-expiry" else "startup-cached-before-confirmation")
        main {
            account.value = if (expired) AccountSession(version = 1, expired = true)
                else AccountSession(AccountUser("12", "Проверка", null), 1)
            restoring.value = false
        }; settle()
        if (expired) check(card(selected) == null && card(fixture.rows.first().title) == null)
        else {
            check(card(selected)?.isFocused == true && visible.get() == Section.RESUME)
            val originalBounds = settledCardBounds(selected)
            main { live.value = cached.copy(entries = fixture.rows.take(1), loading = true) }; settle()
            check(card(selected)?.isFocused == true && bounds(checkNotNull(card(selected))) == originalBounds)
            main {
                live.value = cached.copy(entries = fixture.rows.map { it.copy() }, cached = false)
                preview.value = null
            }; settle()
            check(card(selected)?.isFocused == true && bounds(checkNotNull(card(selected))) == originalBounds)
            capture("startup-cached-after-equal")
            refreshedPreview(live)
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); settle()
            check(visible.get() == null)
        }
    }

    private fun refreshedPreview(live: MutableState<CatalogUiState>) = with(ui) {
        phase = "startup_reconcile_personal"
        val rows = fixture.rows
        val selected = rows[1]
        val added = rows[0].copy(title = "Новая карточка", sourceKey = "added")
        val updated = selected.copy(title = "Новая подпись", sourceKey = selected.key)
        main { live.value = live.value.copy(entries = listOf(added), hasMore = true) }; settle()
        check(card(selected.title)?.isFocused == true)
        repeat(2) { sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT); settle() }
        check(card(added.title)?.isFocused == true)
        repeat(2) { sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); settle() }
        check(card(selected.title)?.isFocused == true)
        main {
            live.value = live.value.copy(
                entries = listOf(added) + rows.drop(2) + updated,
                hasMore = false,
            )
        }; settle()
        check(card(updated.title)?.isFocused == true && card(selected.title) == null)
        capture("startup-cached-after-change")
    }

    private fun smartPreviewCase() = with(ui) {
        phase = "startup_reconcile_continue"
        val account = mutableStateOf(AccountSession())
        val live = mutableStateOf(CatalogUiState(loading = false))
        val rows = fixture.rows
        val cached = CatalogUiState(entries = rows, loaded = true, loading = false, cached = true)
        val preview = mutableStateOf<PersonalStartPreview?>(PersonalStartPreview(Section.SMART_CONTINUE, "12", cached))
        val added = rows.first().copy(title = "Новая серия", sourceKey = "continue-added")
        main { activity.setContent {
            SeasonvarApp(PublicFeed.entries.associateWith { CatalogUiState(loading = false) }, {},
                startPage = Section.SMART_CONTINUE, interfaceTheme = theme, imagePrefetchEnabled = false,
                accountSession = account.value, accountState = AccountUiState(busy = account.value.user == null),
                personalStartPreview = preview.value, smartState = live.value)
        } }
        settle()
        repeat(2) { sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); settle() }
        val selected = rows[1].title
        check(card(selected)?.isFocused == true)
        main { account.value = AccountSession(AccountUser("12", "Проверка", null), 1) }; settle()
        main {
            live.value = cached.copy(entries = listOf(added) + rows.drop(1), failure = LoadFailure.MARKUP)
            preview.value = null
        }; settle()
        check(card(rows.first().title) == null && card(added.title) != null && card(selected)?.isFocused == true)
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
