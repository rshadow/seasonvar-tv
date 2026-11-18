package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.runBlocking
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.*
import ru.seasonvar.tv.data.cache.*
import ru.seasonvar.tv.ui.SeriesThumbnail
import ru.seasonvar.tv.ui.account.LocalCardDataCache
import ru.seasonvar.tv.ui.catalog.SeriesCardAppearance
import ru.seasonvar.tv.ui.catalog.rememberCatalogCardMetrics
import ru.seasonvar.tv.ui.details.DetailsUiState
import ru.seasonvar.tv.ui.details.SeasonProgress
import ru.seasonvar.tv.ui.settings.InterfaceTheme
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

internal object CardMarkStateProbe {
    fun run(probe: Instrumentation, theme: InterfaceTheme) = with(probe) {
        val result = Bundle()
        var phase = "start"
        var activity: MarkMenuPreviewActivity? = null
        val directory = java.nio.file.Files.createTempDirectory(targetContext.cacheDir.toPath(), "card-mark-probe-").toFile()
        val disk = HtmlResponseCache(directory)
        val cache = CardDataCache(disk)
        val row = SeriesEntry("123", "https://seasonvar.ru/serial-123-Test.html", "Карточка A", 1, "",
            "android.resource://ru.seasonvar.tv/drawable/poster_placeholder",
            accountProgress = AccountProgress(episodes = SeasonEpisodeProgress("123", ProgressCounter(2, 8))))
        val other = row.copy(id = "124", url = "https://seasonvar.ru/serial-124-Test.html", title = "Карточка B")
        val duplicate = row.copy(title = "Тот же сезон", sourceKey = "history:123")
        val episodes = (1..8).joinToString(",") {
            "\"$it\":{\"n\":\"$it\"" + (if (it < 8) ",\"next\":\"${it + 1}\"" else "") + "}"
        }
        val html = """<div class='headblock-menu'><a href='/profile/12'>Тест</a></div>
            <div class='pgs-sinfo' data-id-season='123' data-id-serial='99'>
            <div class='pgs-sinfo-info'><p>Локальная проверка общей отметки</p></div></div>
            <ul class='pgs-trans'><li data-translate='0'>Стандартный</li></ul>
            <script>var arEpisodes={"0":{"":{"next":"1"},$episodes}};var mark={"href":"${row.url}#rewind=2_seriya","trans":"0"};</script>"""
        val calls = mutableListOf<String>()
        var response = """{"msg":"success"}"""
        val transport = object : AccountTransport {
            override suspend fun get(url: String, jar: SessionCookies): String { calls += "GET"; return html }
            override suspend fun login(login: String, password: String, jar: SessionCookies) = ""
            override suspend fun saveMark(mark: PlaybackMark, jar: SessionCookies): String { calls += "POST"; return response }
            override suspend fun removeMark(mark: AccountListMark, jar: SessionCookies): String { calls += "POST"; return response }
        }
        val account = AccountRepository(SessionManager(object : SessionStore {
            override fun read(): List<okhttp3.Cookie> = emptyList()
            override fun save(cookies: List<okhttp3.Cookie>) = Unit
            override fun clear() = Unit
        }) { cache.clearPrivate() }, transport, cardData = cache)
        val rootCompositions = AtomicInteger(); val neighborCompositions = AtomicInteger()
        val focus = FocusRequester()
        val artifacts = File(checkNotNull(targetContext.getExternalFilesDir("card-marks-probe")), "run-" + System.currentTimeMillis())
        fun find(node: AccessibilityNodeInfo?, title: String): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString() == title) return node
            for (index in 0 until node.childCount) find(node.getChild(index), title)?.let { return it }
            return null
        }
        fun card(title: String): AccessibilityNodeInfo? {
            var node = find(uiAutomation.rootInActiveWindow, title) ?: return null
            while (!node.isClickable && node.parent != null) node = node.parent
            return node
        }
        fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
        fun badges(node: AccessibilityNodeInfo?): Int {
            node ?: return 0
            return (if (node.contentDescription?.toString() == targetContext.getString(R.string.series_watching_badge)) 1 else 0) +
                (0 until node.childCount).sumOf { badges(node.getChild(it)) }
        }
        fun waitFor(test: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 10_000
            while (!test()) { check(SystemClock.elapsedRealtime() < end) { phase }; Thread.sleep(40) }
        }
        fun progress(value: Int) = targetContext.getString(R.string.account_mark_progress,
            targetContext.getString(R.string.details_progress_episode, value, 8))
        fun capture(name: String) {
            check(artifacts.isDirectory || artifacts.mkdirs())
            val image = checkNotNull(uiAutomation.takeScreenshot())
            File(artifacts, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        try {
            check(android.os.Build.MODEL.contains("sdk", true))
            runBlocking {
                cache.load(row, disk.capture(), false, { CachedHtml(html, System.currentTimeMillis()) })
                account.login("fixture", "fixture-password")
            }
            val details = checkNotNull(cache.knownDetails(row.id))
            activity = startActivitySync(Intent(targetContext, MarkMenuPreviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MarkMenuPreviewActivity
            runOnMainSync { activity!!.setContent {
                SeasonvarTheme(theme) {
                    CompositionLocalProvider(LocalCardDataCache provides cache) {
                        SideEffect { rootCompositions.incrementAndGet() }
                        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            val d = SeasonvarTheme.dimensions
                            val metrics = rememberCatalogCardMetrics(maxWidth - d.spacingLarge * 2)
                            Column(Modifier.fillMaxSize().padding(d.spacingLarge), verticalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
                                SeasonProgress(DetailsUiState(entry = row, details = details), row)
                                Row(horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                                    for ((index, item) in listOf(row, other, duplicate).withIndex()) {
                                        key(item.key) {
                                            if (index == 1) SideEffect { neighborCompositions.incrementAndGet() }
                                            val requester = if (index == 0) focus else remember { FocusRequester() }
                                            SeriesThumbnail(item, requester, FocusRequester.Default, metrics, left = null, right = FocusRequester.Default,
                                                content = { SeriesCardAppearance(item.id, item.title, "Сезон 1", item.posterUrl, item.isNew, item.isWatching) },
                                                onFocused = {}, onClick = {})
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } }
            phase = "initial"
            waitFor { card(row.title) != null && find(uiAutomation.rootInActiveWindow, progress(2)) != null }
            runOnMainSync { focus.requestFocus() }
            waitFor { card(row.title)?.isFocused == true }
            val initial = bounds(checkNotNull(card(row.title)))
            val neighborBounds = bounds(checkNotNull(card(other.title)))
            capture("01-before")
            uiAutomation.waitForIdle(300, 3000)
            val roots = rootCompositions.get(); val neighbors = neighborCompositions.get()
            calls.clear()
            phase = "success"
            runBlocking { account.savePlaybackMark(PlaybackMark(row.id, row.url, "3", "0", 90)) }
            check(calls == listOf("GET", "POST"))
            waitFor { badges(uiAutomation.rootInActiveWindow) == 2 && find(uiAutomation.rootInActiveWindow, progress(3)) != null }
            check(card(row.title)?.isFocused == true)
            check(initial == bounds(checkNotNull(card(row.title))) && neighborBounds == bounds(checkNotNull(card(other.title))))
            capture("02-applied")
            result.putString("failure_counts", "roots_" + roots + "_" + rootCompositions.get() + "_neighbors_" + neighbors + "_" + neighborCompositions.get())
            check(rootCompositions.get() == roots && neighborCompositions.get() == neighbors)
            result.remove("failure_counts")
            phase = "failure"
            response = """{"msg":"error"}"""
            runBlocking { check(runCatching { account.savePlaybackMark(PlaybackMark(row.id, row.url, "4", "0", 90)) }
                .exceptionOrNull() is MarkSaveRejectedException) }
            check(cache.mark(row.id)?.snapshot?.mark?.episode == "3")
            phase = "remove"
            response = """{"msg":"success"}"""; calls.clear()
            runBlocking { account.removeListMark(AccountListMark(row.id, row.url), account.session.value.version) }
            check(calls == listOf("GET", "POST"))
            waitFor { badges(uiAutomation.rootInActiveWindow) == 0 }
            check(card(row.title)?.isFocused == true && initial == bounds(checkNotNull(card(row.title))))
            capture("03-removed")
            result.putBoolean("mark_reactive", true)
            result.putBoolean("unrelated_card_unchanged", true)
            result.putBoolean("post_without_readback", true)
            result.putInt("root_compositions", rootCompositions.get())
            result.putBoolean("geometry_stable", true)
            result.putBoolean("focus_preserved", true)
            result.putBoolean("fixture_only", true)
            result.putBoolean("script_success", true)
            result.putString("artifacts", artifacts.absolutePath)
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("artifacts", artifacts.absolutePath)
            result.putString("failure_phase", phase)
            result.putString("failure_type", error.javaClass.simpleName)
            result.putInt("failure_line", error.stackTrace.firstOrNull { it.fileName == "CardMarkStateProbe.kt" }?.lineNumber ?: -1)
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { activity?.finish() }
            directory.deleteRecursively()
        }
    }
}
