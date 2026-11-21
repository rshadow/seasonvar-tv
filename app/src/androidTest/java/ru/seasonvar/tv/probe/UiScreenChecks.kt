package ru.seasonvar.tv.probe

import android.graphics.Rect
import android.view.KeyEvent
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.*
import ru.seasonvar.tv.data.network.DnsProvider
import ru.seasonvar.tv.data.playback.PlaybackSpeed
import ru.seasonvar.tv.ui.SeasonvarApp
import ru.seasonvar.tv.ui.account.*
import ru.seasonvar.tv.ui.catalog.*
import ru.seasonvar.tv.ui.details.SeriesDetailsPane
import ru.seasonvar.tv.ui.menuGroupTitle
import ru.seasonvar.tv.ui.navigation.MenuGroup
import ru.seasonvar.tv.ui.navigation.MenuOrder
import ru.seasonvar.tv.ui.navigation.Section
import ru.seasonvar.tv.ui.search.SearchUiState

/** Каждая группа экранов имеет собственные проверки; общая fixture сохраняется между диалогами. */
internal class UiScreenChecks(private val ui: UiScreensProbeSession, private val fixture: UiScreensFixtures) {
    private val menuOrder = mutableStateOf(MenuOrder())
    private val hiddenSections = mutableStateOf(emptySet<Section>())

    private fun root(page: Section, state: CatalogUiState = fixture.catalog) = ui.render { with(fixture) {
        SeasonvarApp(PublicFeed.entries.associateWith { state }, {}, startPage = page,
            loadDescription = { SeriesDescription(listOf("Синтетическое описание для проверки общего движка.")) },
            accountSession = account, personalStates = PersonalFeed.entries.associateWith { state }, smartState = state,
            searchState = SearchUiState(showHistory = true, recentQueries = listOf("Комедия", "Сериал")),
            translationSettingsModel = translations, updateModel = update,
            personalFilters = PersonalFeed.entries.filter { it != PersonalFeed.HISTORY }.associateWith { filterController },
            publicFilters = mapOf(PublicFeed.BEST to filterController),
            showActors = toggles.getValue(R.string.show_actors_title), onShowActorsChange = { toggles[R.string.show_actors_title] = it },
            showComments = toggles.getValue(R.string.comments_setting_title),
            onShowCommentsChange = { toggles[R.string.comments_setting_title] = it },
            autoNextEnabled = toggles.getValue(R.string.auto_next_setting_title), onAutoNextChange = { toggles[R.string.auto_next_setting_title] = it },
            autoNextSeasonEnabled = toggles.getValue(R.string.auto_next_season_setting_title), onAutoNextSeasonChange = { toggles[R.string.auto_next_season_setting_title] = it },
            imagePrefetchEnabled = toggles.getValue(R.string.image_prefetch_title), onImagePrefetchChange = { toggles[R.string.image_prefetch_title] = it },
            listPrefetchEnabled = toggles.getValue(R.string.list_prefetch_title), onListPrefetchChange = { toggles[R.string.list_prefetch_title] = it },
            bypassEnabled = toggles.getValue(R.string.bypass_title), onBypassChange = { toggles[R.string.bypass_title] = it },
            playbackSpeed = playbackSpeed.value, onPlaybackSpeedChange = { playbackSpeed.value = it },
            fontSize = fontSize.value, onFontSizeChange = { fontSize.value = it },
            menuOrder = menuOrder.value, onMenuOrderChange = { menuOrder.value = it },
            hiddenSections = hiddenSections.value, onSectionVisibilityChange = { item, visible ->
                hiddenSections.value = if (visible) hiddenSections.value - item else hiddenSections.value + item
            },
            showPlayerControls = toggles.getValue(R.string.show_player_controls_title),
            onShowPlayerControlsChange = { toggles[R.string.show_player_controls_title] = it },
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

    fun settingsNavigation() = with(ui) { with(fixture) {
        stage("settings_navigation")
        root(Section.SETTINGS); click(text(R.string.settings_title))
        val rows = listOf(R.string.start_page_title, R.string.menu_visibility_title, R.string.home_settings_title,
            R.string.preferred_translation_title, R.string.show_actors_title, R.string.comments_setting_title,
            R.string.interface_theme_title, R.string.font_size_title,
            R.string.auto_next_setting_title, R.string.auto_next_season_setting_title, R.string.player_clock_title,
            R.string.playback_speed_default_title, R.string.show_player_controls_title, R.string.image_prefetch_title, R.string.list_prefetch_title,
            R.string.connection_refresh, R.string.bypass_title, R.string.dns_setting_title, R.string.proxy_title)
        for ((index, id) in rows.withIndex()) {
            if (index > 0) { sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); settle() }
            check(control(text(id))?.isFocused == true) { "Settings focus missing: " + text(id) }
            if (id in toggles) {
                val initial = toggles.getValue(id)
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); settle()
                check(toggles.getValue(id) != initial && control(text(id))?.isChecked == !initial)
                check(control(text(id))?.isFocused == true)
                if (id == R.string.list_prefetch_title) capture("settings-list-prefetch")
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); settle()
                check(toggles.getValue(id) == initial && control(text(id))?.isChecked == initial)
            }
        }
        for (id in rows.asReversed().drop(1)) {
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP); settle()
            check(control(text(id))?.isFocused == true) { "Reverse settings focus missing: " + text(id) }
        }
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT); settle()
        check(control(text(R.string.settings_title))?.isFocused == true)
        capture("settings-navigation")
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

    private fun optionFocused(label: String): Boolean {
        var node = ui.find(ui.uiAutomation.rootInActiveWindow, label, exact = true)
        while (node != null) {
            if (node.isFocused) return true
            node = node.parent
        }
        return false
    }

    fun settingsDialogs() = with(ui) { with(fixture) {
        stage("settings_dialogs")
        root(Section.SETTINGS); capture("settings-application")
        for ((name, id) in listOf("start" to R.string.start_page_title, "menu" to R.string.menu_visibility_title,
            "channels" to R.string.home_settings_title, "font_size" to R.string.font_size_title, "clock" to R.string.player_clock_title,
            "speed" to R.string.playback_speed_default_title, "dns" to R.string.dns_setting_title)) {
            stage("settings_" + name)
            click(text(id))
            if (name in listOf("menu", "channels")) centered(text(R.string.menu_visibility_done))
            if (name == "menu") {
                fun label(id: Int, value: String) = targetContext.getString(id, value)
                fun dpad(key: Int) { sendKeyDownUpSync(key); settle() }
                val groupDown = label(R.string.menu_order_move_down, text(menuGroupTitle(MenuGroup.CATALOG)))
                waitFor { optionFocused(groupDown) }
                dpad(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor { optionFocused(groupDown) }
                check(menuOrder.value.groups().first().first == MenuGroup.SMART)
                dpad(KeyEvent.KEYCODE_DPAD_DOWN)
                val today = text(R.string.today_title)
                val todayDown = label(R.string.menu_order_move_down, today)
                waitFor { optionFocused(todayDown) }
                dpad(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor { optionFocused(todayDown) }
                check(menuOrder.value.groups().toMap().getValue(MenuGroup.CATALOG).first() == Section.UPDATES)
                dpad(KeyEvent.KEYCODE_DPAD_RIGHT)
                waitFor { optionFocused(label(R.string.menu_order_hide, today)) }
                dpad(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor { optionFocused(label(R.string.menu_order_show, today)) }
                check(Section.TODAY in hiddenSections.value)
                capture("settings-menu-hidden")
                dpad(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor { optionFocused(label(R.string.menu_order_hide, today)) }
                check(Section.TODAY !in hiddenSections.value)
            }
            if (name == "font_size") {
                check(optionFocused(text(R.string.font_size_normal)))
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); settle()
                check(fontSize.value == ru.seasonvar.tv.ui.settings.InterfaceFontSize.LARGE)
                check(control(text(R.string.font_size_title))?.isFocused == true)
                capture("settings-font-large")
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); settle()
                check(optionFocused(text(R.string.font_size_large)))
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); settle()
                check(fontSize.value == ru.seasonvar.tv.ui.settings.InterfaceFontSize.NORMAL)
                check(control(text(R.string.font_size_title))?.isFocused == true)
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); settle()
            }
            if (name == "speed") {
                capture("settings-speed-open")
                check(optionFocused(text(R.string.playback_speed_normal)))
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); settle()
                check(playbackSpeed.value == PlaybackSpeed.NORMAL)
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); settle()
                check(playbackSpeed.value == PlaybackSpeed.ONE_QUARTER)
                check(control(text(R.string.playback_speed_default_title))?.isFocused == true)
                capture("settings-speed-selected")
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); settle()
                check(optionFocused(text(R.string.playback_speed_one_quarter)))
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); settle()
                check(playbackSpeed.value == PlaybackSpeed.ONE_QUARTER)
            }
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

    fun detailsNavigation() = with(ui) { with(fixture) {
        stage("details_navigation")
        val showActors = mutableStateOf(true)
        render { SeriesDetailsPane(entry, actorModel, remember { FocusRequester() }, {}, {}, showActors = showActors.value) }
        waitFor { actorModel.state.value.details != null }; settle()
        val watch = text(R.string.details_watch)
        fun focused(label: String): Boolean {
            var node = find(uiAutomation.rootInActiveWindow, label, exact = true)
            while (node != null) { if (node.isFocused) return true; node = node.parent }
            return false
        }
        check(focused(watch))
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP); settle(); check(focused("Первый актёр"))
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); settle(); check(focused("Второй актёр"))
        capture("details-actor-focus")
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); settle(); check(focused(watch))
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); settle(); check(focused("Сезон 1 ▾"))
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP); settle(); check(focused("Второй актёр"))
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); settle(); check(focused("Сезон 1 ▾"))
        main { showActors.value = false }; settle()
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP); settle(); check(focused("Сезон 1 ▾"))
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); settle(); check(focused("1"))
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); settle()
        val all = text(R.string.details_all_episodes)
        check(focused(all))
        val anchor = Rect().also(checkNotNull(control(all))::getBoundsInScreen)
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); settle()
        // Accessibility исключает элементы, ушедшие за границы экрана.
        val scrolled = control(all)?.let { Rect().also(it::getBoundsInScreen) }
        check(scrolled == null || scrolled.top < anchor.top)
        check(find(uiAutomation.rootInActiveWindow, "Синтетическое описание для проверки прокрутки.") != null)
        capture("details-description-scrolled")
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP); settle()
        check(Rect().also(checkNotNull(control(all))::getBoundsInScreen) == anchor && focused(all))
        capture("details-description-returned")
    } }

    fun seasonComments() = with(ui) { with(fixture) {
        stage("season_comments_setting")
        val enabled = mutableStateOf(false)
        render { SeriesDetailsPane(entry, commentsModel, remember { FocusRequester() }, {}, {}, showComments = enabled.value) }
        val watch = text(R.string.details_watch)
        waitFor { optionFocused(watch) }
        val heading = text(R.string.comments_heading)
        check(commentPages.isEmpty())
        check(find(uiAutomation.rootInActiveWindow, heading, exact = true) == null)
        main { enabled.value = true }; waitFor { commentPages == listOf(1) }
        fun dpad(code: Int) { sendKeyDownUpSync(code); settle() }
        stage("season_comments_focus")
        dpad(KeyEvent.KEYCODE_DPAD_DOWN)
        check(optionFocused("1"))
        repeat(4) { if (!optionFocused("Первый зритель")) dpad(KeyEvent.KEYCODE_DPAD_DOWN) }
        check(optionFocused("Первый зритель"))
        check(find(uiAutomation.rootInActiveWindow, heading, exact = true) != null)
        check(find(uiAutomation.rootInActiveWindow, "Скрытый ответ.", exact = true) == null)
        dpad(KeyEvent.KEYCODE_DPAD_UP)
        check(optionFocused("1"))
        repeat(4) { if (!optionFocused("Первый зритель")) dpad(KeyEvent.KEYCODE_DPAD_DOWN) }
        check(optionFocused("Первый зритель"))
        dpad(KeyEvent.KEYCODE_DPAD_DOWN)
        val showSpoiler = text(R.string.comment_show_spoiler)
        waitFor { optionFocused(showSpoiler) }
        check(find(uiAutomation.rootInActiveWindow, "Автор ответа", exact = true) != null)
        check(commentPages == listOf(1))
        capture("comments-spoiler-hidden")
        stage("season_comments_spoiler")
        dpad(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor { find(uiAutomation.rootInActiveWindow, "Скрытый ответ.", exact = true) != null }
        capture("comments-spoiler-open")
        dpad(KeyEvent.KEYCODE_DPAD_CENTER)
        check(find(uiAutomation.rootInActiveWindow, "Скрытый ответ.", exact = true) == null)
        stage("season_comments_pagination")
        repeat(15) { if (!optionFocused("Последний зритель")) dpad(KeyEvent.KEYCODE_DPAD_DOWN) }
        check(commentPages == listOf(1, 2) && optionFocused("Последний зритель"))
        capture("comments-second-page")
    } }
}
