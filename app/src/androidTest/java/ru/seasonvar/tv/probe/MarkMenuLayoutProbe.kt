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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.flow.MutableStateFlow
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.Episode
import ru.seasonvar.tv.data.PublicFeed
import ru.seasonvar.tv.data.SeasonOption
import ru.seasonvar.tv.data.SeriesDetails
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.Translation
import ru.seasonvar.tv.data.account.AccountListMark
import ru.seasonvar.tv.data.account.AccountListStatus
import ru.seasonvar.tv.data.account.AccountSession
import ru.seasonvar.tv.data.account.AccountUser
import ru.seasonvar.tv.data.account.PlaybackMark
import ru.seasonvar.tv.data.account.PlaybackMarkSnapshot
import ru.seasonvar.tv.ui.SeasonvarApp
import ru.seasonvar.tv.ui.account.*
import ru.seasonvar.tv.ui.catalog.CatalogUiState
import ru.seasonvar.tv.ui.catalog.LoadFailure
import ru.seasonvar.tv.ui.details.SeriesDetailsViewModel
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import ru.seasonvar.tv.ui.settings.InterfaceTheme
import java.io.File

object MarkMenuLayoutProbe {
    internal fun run(probe: Instrumentation, theme: InterfaceTheme = InterfaceTheme.CLASSIC) = with(probe) {
        val result = Bundle()
        var activity: MarkMenuPreviewActivity? = null
        var phase = "start"
        val entry = SeriesEntry("123", "https://seasonvar.ru/serial-123-Test.html", "Проверка меню", 1, "", "")
        val marked = entry.copy(accountListMark = AccountListMark(entry.id, entry.url))
        val state = mutableStateOf(MarkRemovalUiState(entry = entry, visible = true, loadingStatus = true))
        var refreshes = 0
        var selections = 0
        var confirmations = 0
        var asks = 0
        var episodeRequests = 0
        val mark = PlaybackMark(entry.id, entry.url, "5", "0", 0)
        val models = ViewModelStore()
        val artifacts = File(checkNotNull(targetContext.getExternalFilesDir("mark-menu-probe")), "run-" + System.currentTimeMillis())
        fun <T> main(block: () -> T): T {
            var value: Any? = null
            var failure: Throwable? = null
            runOnMainSync { try { value = block() } catch (error: Throwable) { failure = error } }
            failure?.let { throw it }
            @Suppress("UNCHECKED_CAST")
            return value as T
        }
        fun waitFor(predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 8000
            while (!predicate()) {
                check(SystemClock.elapsedRealtime() < end) { "Timed out in " + phase }
                Thread.sleep(50)
                if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
            }
        }
        fun stage(name: String) { phase = name; sendStatus(Activity.RESULT_OK, Bundle().apply { putString("phase", name) }) }
        fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString()?.lineSequence()?.any { it == label } == true || node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
            return null
        }
        fun control(label: String): AccessibilityNodeInfo? {
            var node = find(uiAutomation.rootInActiveWindow, label) ?: return null
            while (node.className?.toString() != "android.widget.Button" && !node.isClickable && node.parent != null) node = node.parent
            return node.takeIf { it.className?.toString() == "android.widget.Button" || it.isClickable }
        }
        fun text(id: Int) = targetContext.getString(id)
        val labels = listOf(R.string.mark_list_want, R.string.mark_list_watching, R.string.mark_list_watched,
            R.string.mark_list_not_watching, R.string.mark_remove_from_list, R.string.mark_list_refresh).map(::text)
        fun bounds() = labels.associateWith { label ->
            Rect().also { checkNotNull(control(label)) { "Missing " + label }.getBoundsInScreen(it) }
        }
        fun focus(label: String) = control(label)?.isFocused == true
        fun settle() { Thread.sleep(350); waitForIdleSync(); uiAutomation.waitForIdle(250, 3000) }
        fun key(code: Int) { sendKeyDownUpSync(code); settle() }
        fun update(block: (MarkRemovalUiState) -> MarkRemovalUiState) { main { state.value = block(state.value) }; settle() }
        fun capture(name: String) {
            check(artifacts.isDirectory || artifacts.mkdirs())
            val image = checkNotNull(uiAutomation.takeScreenshot())
            File(artifacts, name + ".png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        try {
            check(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.FINGERPRINT.contains("generic"))
            activity = startActivitySync(Intent(targetContext, MarkMenuPreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MarkMenuPreviewActivity
            main {
                activity!!.setContent {
                    SeasonvarTheme(theme) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            MarkRemovalDialog(state.value,
                                onDismiss = { state.value = state.value.copy(visible = false) },
                                onAsk = { asks++; state.value = state.value.copy(stage = RemovalStage.CONFIRM, failure = null) },
                                onConfirm = { confirmations++; state.value = state.value.copy(busy = true) },
                                onInteraction = {},
                                onRefresh = { refreshes++; state.value = state.value.copy(loadingStatus = true, failure = null) },
                                onSelectStatus = { selections++; state.value = state.value.copy(busy = true, failure = null) },
                                onMarkEpisode = {
                                    episodeRequests++
                                    state.value = state.value.copy(stage = RemovalStage.EPISODE_CONFIRM,
                                        episodeConflict = PlaybackMarkSnapshot(mark.copy(episode = "8")))
                                })
                        }
                    }
                }
            }
            stage("initial_loading")
            waitFor { labels.all { control(it) != null } && focus(text(R.string.mark_list_refresh)) }
            val original = bounds(); capture("01-loading")
            fun checkGeometry() {
                val current = bounds()
                for ((index, label) in labels.withIndex()) if (current[label] != original[label]) {
                    result.putString("failure_geometry", (listOf(index) + listOf(original[label], current[label]).flatMap { listOf(it!!.left, it.top, it.right, it.bottom) }).joinToString("_"))
                    error("Geometry changed")
                }
            }
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN)
            waitFor { focus(text(R.string.mark_list_watching)) }
            val pressedAt = SystemClock.uptimeMillis()
            sendKeySync(KeyEvent(pressedAt, pressedAt, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
            update { it.copy(entry = marked, status = AccountListStatus.WATCHING, loadingStatus = false) }
            sendKeySync(KeyEvent(pressedAt, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
            check(main { selections == 0 }); waitFor { focus(text(R.string.mark_list_watching)) }
            checkGeometry(); capture("02-loaded")

            stage("manual_refresh")
            key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor { main { state.value.loadingStatus } }
            key(KeyEvent.KEYCODE_DPAD_CENTER); check(main { refreshes == 1 })
            waitFor { focus(text(R.string.mark_list_refresh)) }; checkGeometry()
            update { it.copy(entry = entry, status = null, loadingStatus = false) }
            waitFor { focus(text(R.string.mark_list_refresh)) }; checkGeometry()
            check(control(text(R.string.mark_remove_from_list))?.isEnabled == false)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            waitFor { focus(text(R.string.mark_remove_from_list)) }
            key(KeyEvent.KEYCODE_DPAD_CENTER); check(main { asks == 0 })

            stage("refresh_failure")
            key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER)
            update { it.copy(loadingStatus = false, failure = LoadFailure.NETWORK) }
            waitFor { focus(text(R.string.mark_list_refresh)) }; checkGeometry(); capture("03-refresh-error")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            update { it.copy(entry = marked, status = AccountListStatus.WATCHING, loadingStatus = false, failure = null) }
            waitFor { focus(text(R.string.mark_list_refresh)) }; checkGeometry()

            stage("saving_failure")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            waitFor { focus(text(R.string.mark_remove_from_list)) }
            key(KeyEvent.KEYCODE_DPAD_UP)
            waitFor { focus(text(R.string.mark_list_not_watching)) }
            key(KeyEvent.KEYCODE_DPAD_CENTER); waitFor { main { state.value.busy } }
            key(KeyEvent.KEYCODE_DPAD_CENTER); check(main { selections == 1 })
            waitFor { focus(text(R.string.mark_list_not_watching)) }; checkGeometry(); capture("04-saving")
            update { it.copy(busy = false, failure = LoadFailure.NETWORK) }
            waitFor { focus(text(R.string.mark_list_not_watching)) }; checkGeometry(); capture("05-save-error")
            Thread.sleep(4000)
            checkGeometry(); capture("06-error-expired")

            stage("confirmation")
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor { focus(text(R.string.mark_remove_cancel)) }
            val confirmLabels = listOf(text(R.string.mark_remove_cancel), text(R.string.mark_remove_action))
            fun confirmationBounds() = confirmLabels.associateWith { Rect().also { rect -> checkNotNull(control(it)).getBoundsInScreen(rect) } }
            val confirmation = confirmationBounds()
            confirmLabels.forEach { ButtonAlignmentChecks.centered(probe, it) }
            capture("07-confirmation")
            key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor { main { state.value.busy } }
            waitFor { focus(text(R.string.mark_remove_action)) }
            confirmLabels.forEach { ButtonAlignmentChecks.centered(probe, it) }
            capture("08-confirmation-busy")
            update { it.copy(busy = false, failure = LoadFailure.NETWORK) }
            waitFor { focus(text(R.string.mark_remove_action)) }; check(confirmationBounds() == confirmation)
            key(KeyEvent.KEYCODE_ESCAPE); waitFor { main { !state.value.visible } }
            check(main { confirmations == 1 })

            stage("episode_confirmation")
            update { MarkRemovalUiState(marked, visible = true, status = AccountListStatus.WATCHING, episodeMark = mark) }
            waitFor { focus(text(R.string.mark_list_watching)) }
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            val episodeLabel = targetContext.getString(R.string.mark_episode_action, mark.episode)
            waitFor { focus(episodeLabel) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor { focus(text(R.string.mark_remove_cancel)) }
            check(episodeRequests == 1 && confirmations == 1)
            val previous = targetContext.getString(R.string.episode_number, "8")
            val question = targetContext.getString(R.string.mark_episode_replace_explanation, previous, mark.episode)
            waitFor { find(uiAutomation.rootInActiveWindow, question) != null }
            ButtonAlignmentChecks.centered(probe, text(R.string.mark_remove_cancel))
            ButtonAlignmentChecks.centered(probe, text(R.string.mark_episode_confirm))
            capture("10-replace-episode-mark")
            key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor { focus(text(R.string.mark_episode_confirm)) && main { state.value.busy } }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            check(confirmations == 2)
            key(KeyEvent.KEYCODE_BACK); waitFor { main { !state.value.visible } }

            stage("mark_from_season_card")
            val account = AccountSession(AccountUser("12", "Тест", null), version = 1)
            val session = MutableStateFlow(account)
            var saved: PlaybackMark? = null
            main {
                val details = SeriesDetailsViewModel {
                    SeriesDetails(it, it.id, emptyList(), emptyList(), emptyList(),
                        listOf(SeasonOption(it.id, it.url, "Сезон 1", 1)),
                        listOf(Translation("0", "Оригинал", (1..8).map { number -> Episode("id-$number", number.toString()) })), "0")
                }.also { models.put("details", it) }
                val actions = MarkRemovalViewModel(session, { _, _ -> error("Unexpected removal") }, {},
                    loadStatus = { AccountListStatus.WATCHING }, completeEpisode = { target, _, _ -> saved = target })
                    .also { models.put("actions", it) }
                activity.setContent {
                    SeasonvarApp(mapOf(PublicFeed.TODAY to CatalogUiState(entries = listOf(marked), loaded = true, loading = false)),
                        onRefresh = {}, interfaceTheme = theme, accountSession = account, detailsModel = details,
                        onPlay = { error("Unexpected playback") },
                        removalState = actions.state.collectAsStateWithLifecycle().value,
                        onRemovalActions = { target, fromDetails -> actions.show(target, fromDetails = fromDetails) },
                        onMarkEpisode = actions::markEpisode, onRemovalDismiss = actions::dismiss)
                }
            }
            fun cardFocused(node: AccessibilityNodeInfo?): Boolean = node != null &&
                (node.isFocused && find(node, marked.title) != null || (0 until node.childCount).any { cardFocused(node.getChild(it)) })
            val today = text(R.string.today_title)
            waitFor { focus(today) || cardFocused(uiAutomation.rootInActiveWindow) }
            if (focus(today)) key(KeyEvent.KEYCODE_DPAD_RIGHT)
            waitFor { cardFocused(uiAutomation.rootInActiveWindow) }
            stage("select_episode")
            key(KeyEvent.KEYCODE_DPAD_CENTER); waitFor { focus(text(R.string.details_watch)) }
            key(KeyEvent.KEYCODE_DPAD_DOWN); waitFor { focus("1") }
            repeat(4) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }; waitFor { focus("5") }
            key(KeyEvent.KEYCODE_DPAD_UP); waitFor { focus(text(R.string.details_watch)) }
            stage("open_episode_action")
            key(KeyEvent.KEYCODE_DPAD_RIGHT); waitFor { focus(text(R.string.series_actions_menu)) }
            key(KeyEvent.KEYCODE_DPAD_CENTER); waitFor { focus(text(R.string.mark_list_watching)) }
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }; waitFor { focus(episodeLabel) }
            capture("09-mark-episode")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor { focus(text(R.string.series_actions_menu)) }
            check(main { saved } == mark)

            result.putBoolean("geometry_stable", true)
            result.putBoolean("refresh_focus_preserved", true)
            result.putBoolean("save_focus_preserved", true)
            result.putBoolean("blocked_actions", true)
            result.putBoolean("fixture_only", true)
            result.putBoolean("script_success", true)
        } catch (error: Throwable) {
            result.putBoolean("script_success", false)
            result.putString("failure_type", error.javaClass.simpleName)
            result.putString("failure_phase", phase)
            result.putString("failure_counts", main { listOf(refreshes, selections, state.value.loadingStatus, state.value.busy).joinToString("_") })
            result.putString("failure_focus", labels.mapIndexed { index, label ->
                val node = control(label)
                listOf(index, node?.className, node?.isFocused, node?.isEnabled).joinToString("_")
            }.joinToString("__"))
            result.putString("failure_line", error.stackTrace.firstOrNull { it.fileName == "MarkMenuLayoutProbe.kt" }?.lineNumber.toString())
            runCatching { capture("failure") }
        } finally {
            activity?.let { main { it.finish() } }
            main { models.clear() }
            result.putString("artifacts", artifacts.path)
            finish(if (result.getBoolean("script_success")) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
        }
    }
}
