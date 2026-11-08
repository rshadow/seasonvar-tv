package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.data.account.AccountParser
import ru.seasonvar.tv.data.account.PlaybackMarkParser

/** Explicit live probe; no personal fields or session values are returned to adb. */
class AccountMarkProbe : Instrumentation() {
    private var mode = "inspect"
    private var probeArguments = Bundle()
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        probeArguments = arguments ?: Bundle()
        mode = arguments?.getString("mode") ?: "inspect"
        start()
    }

    override fun onStart() {
        if (mode == "details-progress-layout") { DetailsProgressLayoutProbe.run(this); return }
        if (mode == "home-channels") { HomeChannelProbe.run(this); return }
        if (mode == "smart-inspect") { SmartContinueLiveProbe.run(this, probeArguments); return }
        if (mode == "mark-menu-layout") {
            MarkMenuLayoutProbe.run(this)
            return
        }
        if (mode == "history-playback") { HistoryPlaybackProbe.run(this); return }
        if (mode == "player-auto-next") {
            PlayerAutoNextProbe.run(this, probeArguments)
            return
        }
        if (mode in setOf("update-preview", "update-inspect")) {
            UpdateProbe.run(this, probeArguments)
            return
        }
        val result = Bundle()
        var phase = "restore_session"
        try {
            check(mode in setOf("inspect", "roundtrip", "delete-roundtrip", "status-roundtrip", "catalog-badges"))
            runBlocking { withContext(Dispatchers.IO) {
                val app = targetContext.applicationContext as SeasonvarApplication
                app.account.restore()
                val user = checkNotNull(app.account.session.value.user)
                result.putBoolean("authenticated", true)
                if (mode == "catalog-badges") {
                    phase = "catalog_badges"
                    val home = app.siteClient.accountGet("https://seasonvar.ru/", app.sessions.current())
                    check(AccountParser.user(home)?.id == user.id)
                    val updates = app.siteClient.sidebar(ru.seasonvar.tv.data.PublicFeed.UPDATES)
                    fun record(prefix: String, html: String, selector: String) {
                        val document = Jsoup.parse(html, "https://seasonvar.ru/")
                        val anchor = document.select(selector).firstOrNull {
                            it.text().contains("Сильнейшая ведьма", ignoreCase = true)
                        }
                        result.putBoolean(prefix + "_target_found", anchor != null)
                        val parsed = if (prefix == "today") ru.seasonvar.tv.data.SeasonvarParser.today(html, java.time.LocalDate.now())
                            else ru.seasonvar.tv.data.SeasonvarParser.sidebar(html, java.time.LocalDate.now())
                        val index = parsed.entries.indexOfFirst { it.title.contains("Сильнейшая ведьма", ignoreCase = true) }
                        result.putString(prefix + "_target_index", index.toString())
                        result.putBoolean(prefix + "_watching_parsed", parsed.entries.getOrNull(index)?.isWatching == true)
                        if (anchor != null) {
                            result.putString(prefix + "_classes", anchor.classNames().sorted().joinToString(","))
                            result.putString(prefix + "_child_classes", anchor.select("*")
                                .flatMap { it.classNames() }.distinct().sorted().joinToString(","))
                            result.putString(prefix + "_marker_parents", anchor.select(".svico-mwatch").map {
                                it.tagName() + "." + it.classNames().sorted().joinToString(".") + "_in_" +
                                    it.parent()!!.tagName() + "." + it.parent()!!.classNames().sorted().joinToString(".")
                            }.distinct().joinToString(","))
                            result.putString(prefix + "_attributes", anchor.attributes().asList()
                                .map { it.key }.sorted().joinToString(","))
                        }
                        result.putString(prefix + "_script_variables", document.select("script:not([src])")
                            .flatMap { script -> Regex("""(?:var|let|const)\s+([A-Za-z_][A-Za-z0-9_]*(?:mark|watch|view)[A-Za-z0-9_]*)\s*=""",
                                RegexOption.IGNORE_CASE).findAll(script.data()).map { it.groupValues[1] }.toList() }
                            .distinct().sorted().joinToString(","))
                    }
                    record("today", home, ".news a[data-id]")
                    record("updates", updates, "a.pst.rside-p")
                    phase = "complete"
                    return@withContext
                }
                phase = "read_existing_mark"
                val entries = app.account.resume().entries
                val entry = entries.firstOrNull() ?: error("No existing viewing entries")
                suspend fun html(): String {
                    val page = app.siteClient.accountGet(entry.url, app.sessions.current())
                    check(AccountParser.user(page)?.id == user.id)
                    return page
                }
                val originalHtml = html()
                val doc = Jsoup.parse(originalHtml)
                val declaration = doc.select("script:not([src])").firstNotNullOfOrNull { script ->
                    Regex("""\bvar\s+mark\s*=""").find(script.data())?.let { match ->
                        script.data().substring(match.range.last + 1)
                    }
                }
                result.putBoolean("mark_declaration_present", declaration != null)
                if (declaration != null) {
                    val value = JSONTokener(declaration).nextValue() as? JSONObject
                    result.putString("mark_field_names", value?.keys()?.asSequence()?.sorted()?.joinToString(","))
                    result.putString("href_type", value?.opt("href")?.javaClass?.simpleName)
                    result.putString("translation_type", value?.opt("trans")?.javaClass?.simpleName)
                }
                val before = PlaybackMarkParser.snapshot(originalHtml, entry.id, entry.url)
                result.putBoolean("mark_parsed", true)
                result.putBoolean("existing_playback_mark", before.mark != null)
                if (mode == "status-roundtrip") {
                    val original = checkNotNull(before.mark) { "An existing playback mark is required for restoration" }
                    val version = app.account.session.value.version
                    val selected = entry.copy(initialSelection = ru.seasonvar.tv.data.EntrySelection(
                        episodeLabel = original.episode, translationId = original.translationId))
                    try {
                        phase = "status_roundtrip"
                        for (status in ru.seasonvar.tv.data.account.AccountListStatus.entries) {
                            val saved = app.accountMarks.setListStatus(selected, status, version)
                            check(ru.seasonvar.tv.data.account.AccountListStatus.from(saved) == status)
                            val reread = PlaybackMarkParser.snapshot(html(), entry.id, entry.url)
                            check(ru.seasonvar.tv.data.account.AccountListStatus.from(reread) == status)
                            if (status == ru.seasonvar.tv.data.account.AccountListStatus.WANT_TO_WATCH ||
                                status == ru.seasonvar.tv.data.account.AccountListStatus.WATCHED) {
                                val feed = if (status == ru.seasonvar.tv.data.account.AccountListStatus.WANT_TO_WATCH)
                                    ru.seasonvar.tv.data.account.PersonalFeed.WANT else ru.seasonvar.tv.data.account.PersonalFeed.WATCHED
                                check(app.account.personalList(feed).entries.any { it.id == entry.id })
                            }
                        }
                        result.putBoolean("post_verified", true)
                        result.putBoolean("statuses_verified", true)
                    } finally {
                        phase = "restore_original"
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            app.account.savePlaybackMark(original, version)
                            result.putBoolean("original_restored", PlaybackMarkParser.parse(html(), entry.id, entry.url) == original)
                        }
                    }
                }
                if (mode == "delete-roundtrip") {
                    val original = checkNotNull(before.mark) { "An existing playback mark is required" }
                    val target = checkNotNull(entry.accountListMark)
                    val version = app.account.session.value.version
                    try {
                        phase = "delete_and_readback"
                        app.accountMarks.removeMark(target, version)
                        val current = PlaybackMarkParser.snapshot(html(), entry.id, entry.url)
                        check(current.mark == null && current.status == null)
                        result.putBoolean("post_verified", true)
                        val feeds = ru.seasonvar.tv.data.account.PersonalFeed.entries.filter {
                            it != ru.seasonvar.tv.data.account.PersonalFeed.HISTORY
                        }
                        val absent = feeds.all { feed -> app.account.personalList(feed).entries.none { it.id == entry.id } }
                        result.putBoolean("removed_from_lists", absent)
                        check(absent)
                    } finally {
                        phase = "restore_original"
                        val current = PlaybackMarkParser.snapshot(html(), entry.id, entry.url)
                        if (current.mark == null && current.status == null) {
                            app.account.savePlaybackMark(original, version)
                            result.putBoolean("original_restored", PlaybackMarkParser.parse(html(), entry.id, entry.url) == original)
                        } else {
                            result.putBoolean("original_restored", current.mark == original)
                            result.putBoolean("concurrent_change_left_intact", current.mark != original)
                        }
                    }
                }
                if (mode == "roundtrip") {
                    val original = checkNotNull(before.mark) { "An existing playback mark is required for a reversible probe" }
                    val temporary = original.copy(positionSeconds = if (original.positionSeconds == 17L) 18 else 17)
                    val version = app.account.session.value.version
                    try {
                        phase = "post_and_readback"
                        check(app.account.savePlaybackMark(temporary, version) == temporary)
                        result.putBoolean("post_verified", true)
                    } finally {
                        phase = "restore_original"
                        val current = PlaybackMarkParser.parse(html(), entry.id, entry.url)
                        if (current == temporary) {
                            app.account.savePlaybackMark(original, version)
                            result.putBoolean("original_restored", PlaybackMarkParser.parse(html(), entry.id, entry.url) == original)
                        } else {
                            result.putBoolean("original_restored", current == original)
                            result.putBoolean("concurrent_change_left_intact", current != original)
                        }
                    }
                }
                phase = "complete"
            } }
            result.putBoolean("probe_success", true)
            finish(Activity.RESULT_OK, result)
        } catch (error: Exception) {
            result.putBoolean("probe_success", false)
            result.putString("phase", phase)
            result.putString("failure_type", error.javaClass.simpleName)
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
