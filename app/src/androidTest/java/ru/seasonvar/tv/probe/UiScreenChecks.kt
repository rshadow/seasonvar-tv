package ru.seasonvar.tv.probe

import android.graphics.Rect
import android.view.KeyEvent
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.*
import ru.seasonvar.tv.data.network.DnsProvider
import ru.seasonvar.tv.ui.SeasonvarApp
import ru.seasonvar.tv.ui.account.*
import ru.seasonvar.tv.ui.catalog.*
import ru.seasonvar.tv.ui.details.SeriesDetailsPane
import ru.seasonvar.tv.ui.navigation.Section
import ru.seasonvar.tv.ui.search.SearchUiState

/** Каждая группа экранов имеет собственные проверки; общая fixture сохраняется между диалогами. */
internal class UiScreenChecks(private val ui: UiScreensProbeSession, private val fixture: UiScreensFixtures) {
    private fun root(page: Section, state: CatalogUiState = fixture.catalog) = ui.render { with(fixture) {
        SeasonvarApp(PublicFeed.entries.associateWith { state }, {}, startPage = page,
            loadDescription = { SeriesDescription(listOf("Синтетическое описание для проверки общего движка.")) },
            accountSession = account, personalStates = PersonalFeed.entries.associateWith { state }, smartState = state,
            searchState = SearchUiState(showHistory = true, recentQueries = listOf("Комедия", "Сериал")),
            translationSettingsModel = translations, updateModel = update,
            personalFilters = PersonalFeed.entries.filter { it != PersonalFeed.HISTORY }.associateWith { filterController },
            publicFilters = mapOf(PublicFeed.BEST to filterController), imagePrefetchEnabled = false,
            dnsProvider = dns.value, onDnsChange = { dns.value = it })
    } }

    fun catalogsAndSearch() = with(ui) { with(fixture) {
        stage("catalog_screens")
        for (page in Section.entries.filter { it.publicFeed != null || it.personalFeed != null || it == Section.SMART_CONTINUE }) {
            root(page); capture("catalog-" + page.storageId)
        }
        root(Section.TODAY, CatalogUiState(loading = false, failure = LoadFailure.NETWORK))
        centered(text(R.string.retry)); capture("catalog-error")
        root(Section.TODAY); click(text(R.string.search_title)); capture("search-history")
        centered(text(R.string.search_history_clear))
    } }

    fun accountStates() = with(ui) { with(fixture) {
        stage("account_screens")
        var accountActionTop: Int? = null
        var accountAvatarBounds: Rect? = null
        for ((name, session, state) in listOf(Triple("guest", AccountSession(), AccountUiState(busy = false)),
            Triple("busy", AccountSession(), AccountUiState(busy = true)),
            Triple("error", AccountSession(), AccountUiState(busy = false, failure = AccountFailure.NETWORK)),
            Triple("signed-in", account, AccountUiState(busy = false)),
            Triple("premium", account.copy(user = user.copy(avatarUrl = "android.resource://ru.seasonvar.tv/drawable/poster_placeholder",
                premium = AccountPremium.Until(java.time.LocalDate.of(2030, 12, 31)))), AccountUiState(busy = false)),
            Triple("avatar-error", account.copy(user = user.copy(avatarUrl = "android.resource://ru.seasonvar.tv/drawable/missing_account_avatar")), AccountUiState(busy = false)),
            Triple("premium-inactive", account.copy(user = user.copy(premium = AccountPremium.Inactive)), AccountUiState(busy = false)),
            Triple("premium-loading", account, AccountUiState(busy = true)))) {
            render { AccountPane(session, state, remember { FocusRequester() }, { _, _ -> }, {}, {}) }
            val actionLabel = text(if (session.user != null) R.string.account_sign_out else if (state.busy) R.string.account_wait else R.string.account_sign_in)
            centered(actionLabel)
            capture("account-$name")
            val expected = when (name) {
                "signed-in", "premium-loading" -> text(R.string.account_premium_unknown)
                "premium" -> targetContext.getString(R.string.account_premium_until, "31.12.2030")
                "premium-inactive" -> text(R.string.account_premium_inactive)
                else -> null
            }
            if (expected != null) check(find(uiAutomation.rootInActiveWindow, expected) != null)
            val hdHint = find(uiAutomation.rootInActiveWindow, text(R.string.account_premium_hd_hint))
            check((hdHint != null) == (session.user?.premium is AccountPremium.Until))
            if (name == "premium") {
                val hintBounds = Rect().also(checkNotNull(find(uiAutomation.rootInActiveWindow, text(R.string.account_signed_in_hint)))::getBoundsInScreen)
                val premiumBounds = Rect().also(checkNotNull(find(uiAutomation.rootInActiveWindow, checkNotNull(expected)))::getBoundsInScreen)
                val hdBounds = Rect().also(checkNotNull(hdHint)::getBoundsInScreen)
                val signOutBounds = Rect().also(checkNotNull(control(actionLabel))::getBoundsInScreen)
                check(hintBounds.bottom < signOutBounds.top && premiumBounds.bottom < hdBounds.top)
                check(premiumBounds.top - signOutBounds.bottom >= premiumBounds.height())
            }
            if (session.user != null) {
                val avatar = checkNotNull(find(uiAutomation.rootInActiveWindow, text(R.string.account_avatar)))
                val avatarBounds = Rect().also(avatar::getBoundsInScreen)
                val nameBounds = Rect().also(checkNotNull(find(uiAutomation.rootInActiveWindow, user.name))::getBoundsInScreen)
                check(!avatar.isFocusable && !avatar.isClickable)
                check(avatarBounds.width() == avatarBounds.height() && avatarBounds.width() > 0)
                check(avatarBounds.left > nameBounds.right && avatarBounds.top == nameBounds.top)
                accountAvatarBounds?.let { check(it == avatarBounds) }
                accountAvatarBounds = avatarBounds
                val action = checkNotNull(control(actionLabel))
                val bounds = Rect().also(action::getBoundsInScreen)
                accountActionTop?.let { check(it == bounds.top) }
                accountActionTop = bounds.top
            }
        }
    } }

    fun settingsDialogs() = with(ui) { with(fixture) {
        stage("settings_dialogs")
        root(Section.SETTINGS); capture("settings-application")
        for ((name, id) in listOf("start" to R.string.start_page_title, "menu" to R.string.menu_visibility_title,
            "channels" to R.string.home_settings_title, "clock" to R.string.player_clock_title, "dns" to R.string.dns_setting_title)) {
            stage("settings_" + name)
            click(text(id))
            if (name in listOf("menu", "channels")) centered(text(R.string.menu_visibility_done))
            if (name == "dns") {
                check(control(text(R.string.dns_system)) != null)
                check(find(uiAutomation.rootInActiveWindow, text(R.string.dns_quad9)) != null)
                check(control(text(R.string.dns_cloudflare)) != null)
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                settle()
                check(dns.value == DnsProvider.CLOUDFLARE_DOH)
                check(find(uiAutomation.rootInActiveWindow, text(R.string.dns_cloudflare)) != null)
                capture("settings-dns-selected")
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                settle()
                check(control(text(R.string.dns_system)) != null)
            }
            capture("settings-$name-dialog"); back()
        }
        click(text(R.string.preferred_translation_title))
        centered(text(R.string.preferred_translations_add)); capture("settings-translation-priorities")
        click(text(R.string.preferred_translations_add))
        waitFor { control(text(R.string.preferred_translation_retry)) != null }
        settle(); centered(text(R.string.preferred_translation_retry)); capture("settings-translation-error")
        back(); back()
    } }

    fun aboutAndUpdate() = with(ui) { with(fixture) {
        root(Section.ABOUT)
        centered(text(R.string.about_github), text(R.string.update_download)); capture("about-update")
    } }

    fun filterDialogs() = with(ui) { with(fixture) {
        stage("filter_dialogs")
        root(Section.RESUME); click(text(R.string.filters))
        centered(text(R.string.filters_apply), text(R.string.filters_reset), text(R.string.filters_back)); capture("filters-personal")
        click(text(R.string.filter_genre)); capture("filters-options"); back(); back()
        root(Section.BEST); click(text(R.string.filters))
        centered(text(R.string.filters_apply), text(R.string.filters_reset)); capture("filters-best")
        main { filterController.state.value = filterController.state.value.copy(applying = true) }; settle()
        centered(text(R.string.filters_applying), text(R.string.filters_reset)); capture("filters-applying")
        main { filterController.state.value = filterController.state.value.copy(applying = false) }; back()
    } }

    fun detailsDialogs() = with(ui) { with(fixture) {
        stage("details_dialogs")
        main { detailsModel.open(entry) }; waitFor { detailsModel.state.value.details != null }
        render { SeriesDetailsPane(entry, detailsModel, remember { FocusRequester() }, {}, {}) }
        centered("Сезон 1 ▾", targetContext.getString(R.string.details_translation, "Оригинал") + " ▾")
        capture("details")
        click("Сезон 1 ▾"); capture("details-seasons"); back()
        click(targetContext.getString(R.string.details_translation, "Оригинал") + " ▾"); capture("details-translations"); back()
        click(text(R.string.details_all_episodes)); centered("1", "12"); capture("details-episodes"); back()
    } }
}
