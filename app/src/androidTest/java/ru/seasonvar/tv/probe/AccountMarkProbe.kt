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
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        mode = arguments?.getString("mode") ?: "inspect"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var phase = "restore_session"
        try {
            check(mode in setOf("inspect", "roundtrip", "delete-roundtrip"))
            runBlocking { withContext(Dispatchers.IO) {
                val app = targetContext.applicationContext as SeasonvarApplication
                app.account.restore()
                val user = checkNotNull(app.account.session.value.user)
                result.putBoolean("authenticated", true)
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
