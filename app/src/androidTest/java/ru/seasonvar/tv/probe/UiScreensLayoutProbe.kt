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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.ViewModelStore
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.flow.MutableStateFlow
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.*
import ru.seasonvar.tv.data.update.*
import ru.seasonvar.tv.ui.SeasonvarApp
import ru.seasonvar.tv.ui.account.*
import ru.seasonvar.tv.ui.catalog.*
import ru.seasonvar.tv.ui.details.*
import ru.seasonvar.tv.ui.filters.*
import ru.seasonvar.tv.ui.navigation.Section
import ru.seasonvar.tv.ui.search.*
import ru.seasonvar.tv.ui.settings.TranslationSettingsViewModel
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import ru.seasonvar.tv.ui.update.UpdateViewModel
import java.io.File
import java.io.IOException

object UiScreensLayoutProbe {
    fun run(probe: Instrumentation, dialogsOnly: Boolean = false) = with(probe) {
        val result = Bundle()
        val store = ViewModelStore()
        var activity: MarkMenuPreviewActivity? = null
        var phase = "start"
        var revision = 0
        val artifacts = File(checkNotNull(targetContext.getExternalFilesDir("ui-screens-probe")), "run-" + System.currentTimeMillis())
        val user = AccountUser("12", "Тестовый пользователь", null)
        val account = AccountSession(user, 1)
        val entry = SeriesEntry("123", "https://seasonvar.ru/serial-123-example.html", "Проверка интерфейса", 1, "", "")
        val catalog = CatalogUiState(loading = false, loaded = true, entries = listOf(entry))
        val preferred = MutableStateFlow(listOf("AniDub", "LostFilm"))
        val translations = TranslationSettingsViewModel(preferred, { preferred.value = it }, { throw IOException() })
        val update = UpdateViewModel("1.0.0", { GitHubRelease("99.0.0", ReleaseAsset("test.apk",
            "https://github.com/rshadow/seasonvar-tv/releases/download/v99.0.0/test.apk", 1, "0".repeat(64))) },
            { _, _ -> error("Download is outside this probe") })
        val details = SeriesDetails(entry, "100", listOf("Синтетические данные для проверки интерфейса."), emptyList(), emptyList(),
            listOf(SeasonOption("123", entry.url, "Сезон 1", 1), SeasonOption("124", "https://seasonvar.ru/serial-124-example.html", "Сезон 2", 2)),
            listOf(Translation("0", "Оригинал", (1..30).map { Episode(it.toString(), it.toString()) }),
                Translation("1", "Другая озвучка", listOf(Episode("1", "1")))), "0")
        val detailsModel = SeriesDetailsViewModel { details }
        val filterController = object : ListFiltersController {
            val fields = listOf(ListFilterField("genre", R.string.filter_genre,
                listOf(FilterOption("all", "Все жанры"), FilterOption("comedy", "Комедия"))))
            override val state = MutableStateFlow(ListFiltersState(fields = fields, values = mapOf("genre" to "all"), draft = mapOf("genre" to "all")))
            override fun open() { state.value = state.value.copy(open = true) }
            override fun dismiss() { state.value = state.value.copy(open = false) }
            override fun choose(field: String, value: String) { state.value = state.value.copy(draft = state.value.draft + (field to value)) }
            override fun reset() { state.value = state.value.copy(values = mapOf("genre" to "all"), draft = mapOf("genre" to "all")) }
            override fun apply() { state.value = state.value.copy(values = state.value.draft) }
        }
        fun main(block: () -> Unit) { runOnMainSync(block) }
        fun waitFor(predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10000
            while (!predicate()) { check(SystemClock.elapsedRealtime() < deadline) { "Timed out" }; Thread.sleep(50) }
        }
        fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString()?.startsWith(label) == true || node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
            return null
        }
        fun text(id: Int) = targetContext.getString(id)
        fun control(label: String): AccessibilityNodeInfo? {
            var node = find(uiAutomation.rootInActiveWindow, label) ?: return null
            while (!node.isClickable && node.parent != null) node = node.parent
            return node.takeIf { it.isClickable }
        }
        fun settle() { Thread.sleep(500); waitForIdleSync() }
        fun click(label: String) {
            if (control(label) == null) {
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                settle()
                for (direction in listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP)) {
                    for (attempt in 0 until 14) {
                        if (control(label) != null) break
                        sendKeyDownUpSync(direction)
                        settle()
                    }
                    if (control(label) == null && direction == KeyEvent.KEYCODE_DPAD_DOWN) {
                        check(artifacts.isDirectory || artifacts.mkdirs())
                        val image = checkNotNull(uiAutomation.takeScreenshot())
                        File(artifacts, "navigation-bottom.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        image.recycle()
                        val lines = mutableListOf<String>()
                        fun dump(node: AccessibilityNodeInfo?) {
                            node ?: return
                            lines += "${node.className}|${node.isClickable}|${node.isFocused}|${node.text}|${node.contentDescription}"
                            for (i in 0 until node.childCount) dump(node.getChild(i))
                        }
                        dump(uiAutomation.rootInActiveWindow)
                        File(artifacts, "navigation-tree.txt").writeText(lines.joinToString("\n"))
                    }
                }
            }
            waitFor { control(label) != null }
            control(label)?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            settle()
            check(checkNotNull(control(label)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            settle()
        }
        fun back() { sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle() }
        fun stage(name: String) { phase = name; sendStatus(Activity.RESULT_OK, Bundle().apply { putString("phase", name) }) }
        fun capture(name: String) {
            settle()
            check(artifacts.isDirectory || artifacts.mkdirs())
            val bitmap = checkNotNull(uiAutomation.takeScreenshot())
            File(artifacts, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun render(content: @Composable () -> Unit) {
            val current = ++revision
            main { checkNotNull(activity).setContent { key(current) { SeasonvarTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
            } } } }
            settle()
        }
        fun root(page: Section, state: CatalogUiState = catalog) = render {
            SeasonvarApp(PublicFeed.entries.associateWith { state }, {}, startPage = page,
                loadDescription = { SeriesDescription(listOf("Синтетическое описание для проверки общего движка.")) },
                accountSession = account, personalStates = PersonalFeed.entries.associateWith { state }, smartState = state,
                searchState = SearchUiState(showHistory = true, recentQueries = listOf("Комедия", "Сериал")),
                translationSettingsModel = translations, updateModel = update,
                personalFilters = PersonalFeed.entries.filter { it != PersonalFeed.HISTORY }.associateWith { filterController },
                publicFilters = mapOf(PublicFeed.BEST to filterController), imagePrefetchEnabled = false)
        }
        fun centered(vararg labels: String) { labels.forEach { label ->
            waitFor { control(label) != null }
            ButtonAlignmentChecks.centered(probe, label)
        } }
        try {
            check(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.FINGERPRINT.contains("generic"))
            main { store.put("details", detailsModel); store.put("translations", translations); store.put("update", update) }
            activity = startActivitySync(Intent(targetContext, MarkMenuPreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MarkMenuPreviewActivity
            if (!dialogsOnly) {
                stage("catalog_screens")
                for (page in Section.entries.filter { it.publicFeed != null || it.personalFeed != null || it == Section.SMART_CONTINUE }) {
                    root(page); capture("catalog-" + page.storageId)
                }
                root(Section.TODAY, CatalogUiState(loading = false, failure = LoadFailure.NETWORK))
                centered(text(R.string.retry)); capture("catalog-error")
                root(Section.TODAY); click(text(R.string.search_title)); capture("search-history")
                centered(text(R.string.search_history_clear))
                stage("account_screens")
                var accountActionTop: Int? = null
                for ((name, session, state) in listOf(Triple("guest", AccountSession(), AccountUiState(busy = false)),
                    Triple("busy", AccountSession(), AccountUiState(busy = true)),
                    Triple("error", AccountSession(), AccountUiState(busy = false, failure = AccountFailure.NETWORK)),
                    Triple("signed-in", account, AccountUiState(busy = false)),
                    Triple("premium", account.copy(user = user.copy(premium = AccountPremium.Until(java.time.LocalDate.of(2030, 12, 31)))), AccountUiState(busy = false)),
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
                        check(hintBounds.bottom < premiumBounds.top && premiumBounds.bottom < hdBounds.top)
                    }
                    if (session.user != null) {
                        val action = checkNotNull(control(actionLabel))
                        val bounds = Rect().also(action::getBoundsInScreen)
                        accountActionTop?.let { check(it == bounds.top) }
                        accountActionTop = bounds.top
                    }
                }
            }
            stage("settings_dialogs")
            root(Section.SETTINGS); capture("settings-application")
            for ((name, id) in listOf("start" to R.string.start_page_title, "menu" to R.string.menu_visibility_title,
                "channels" to R.string.home_settings_title, "clock" to R.string.player_clock_title, "dns" to R.string.dns_setting_title)) {
                stage("settings_" + name)
                click(text(id))
                if (name in listOf("menu", "channels")) centered(text(R.string.menu_visibility_done))
                capture("settings-$name-dialog"); back()
            }
            click(text(R.string.preferred_translation_title))
            centered(text(R.string.preferred_translations_add)); capture("settings-translation-priorities")
            click(text(R.string.preferred_translations_add))
            waitFor { control(text(R.string.preferred_translation_retry)) != null }
            settle(); centered(text(R.string.preferred_translation_retry)); capture("settings-translation-error")
            back(); back()
            root(Section.ABOUT)
            centered(text(R.string.about_github), text(R.string.update_download)); capture("about-update")
            stage("filter_dialogs")
            root(Section.RESUME); click(text(R.string.filters))
            centered(text(R.string.filters_apply), text(R.string.filters_reset), text(R.string.filters_back)); capture("filters-personal")
            click(text(R.string.filter_genre)); capture("filters-options"); back(); back()
            root(Section.BEST); click(text(R.string.filters))
            centered(text(R.string.filters_apply), text(R.string.filters_reset)); capture("filters-best")
            main { filterController.state.value = filterController.state.value.copy(applying = true) }; settle()
            centered(text(R.string.filters_applying), text(R.string.filters_reset)); capture("filters-applying")
            main { filterController.state.value = filterController.state.value.copy(applying = false) }; back()
            stage("details_dialogs")
            main { detailsModel.open(entry) }; waitFor { detailsModel.state.value.details != null }
            render { SeriesDetailsPane(entry, detailsModel, remember { FocusRequester() }, {}, {}) }
            centered("Сезон 1 ▾", targetContext.getString(R.string.details_translation, "Оригинал") + " ▾")
            capture("details")
            click("Сезон 1 ▾"); capture("details-seasons"); back()
            click(targetContext.getString(R.string.details_translation, "Оригинал") + " ▾"); capture("details-translations"); back()
            click(text(R.string.details_all_episodes)); centered("1", "12"); capture("details-episodes"); back()
            result.putBoolean("screens_verified", !dialogsOnly); result.putBoolean("dialogs_verified", true)
            result.putBoolean("text_centered", true); result.putBoolean("fixture_only", true); result.putBoolean("script_success", true)
        } catch (error: Throwable) {
            runCatching { capture("failure") }
            result.putString("failure_type", error.javaClass.simpleName); result.putString("failure_phase", phase)
            result.putString("failure_line", error.stackTrace.firstOrNull { it.className.contains("UiScreensLayoutProbe") }?.lineNumber?.toString() ?: "unknown")
            result.putBoolean("script_success", false)
        } finally {
            if (artifacts.exists()) result.putString("artifacts", artifacts.absolutePath)
            main { activity?.finish(); store.clear() }
            finish(Activity.RESULT_OK, result)
        }
    }
}
