package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.*
import okhttp3.Cookie
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.*
import ru.seasonvar.tv.data.smart.*
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

internal object SharedSourcesProbe {
    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        val artifacts = File(targetContext.getExternalFilesDir(null), "shared-sources-probe/run-${SystemClock.elapsedRealtime()}")
        try {
            check(artifacts.mkdirs())
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                try {
                    val html = buildString {
                        append("<div class='headblock-menu'><a href='/profile/12'>Fixture</a></div><div class='pgs-marks'>")
                        listOf("marks-new", "marks-nonew", "marks-want", "marks-watched").forEachIndexed { tabIndex, tab ->
                            append("<li data-tabr='$tab'>")
                            repeat(500) { index ->
                                val id = 1 + tabIndex * 500 + index
                                append("<div class='pgs-marks-el' data-marksel='$id'><a href='/serial-$id-Fixture.html'></a><span class='pgs-marks-name'>Fixture $id</span><span class='pgs-marks-current'>1 из 2</span></div>")
                            }
                            append("</li>")
                        }
                        append("</div><select name='marks-genre'><option value='all'>Any</option></select><select name='marks-country'><option value='all'>Any</option></select>")
                    }
                    suspend fun resume(shared: Boolean): Pair<Int, Long> {
                        val count = AtomicInteger(); val gate = CompletableDeferred<Unit>(); var measuring = false
                        val transport = object : AccountTransport {
                            override suspend fun get(url: String, jar: SessionCookies): String {
                                if (measuring) { count.incrementAndGet(); gate.await() }
                                return html
                            }
                            override suspend fun login(login: String, password: String, jar: SessionCookies) = error("No login")
                            override suspend fun saveMark(mark: PlaybackMark, jar: SessionCookies) = error("No writes")
                        }
                        val accounts = List(if (shared) 1 else 7) {
                            val sessions = SessionManager(object : SessionStore {
                                override fun read() = listOf(Cookie.Builder().name("fixture").value("dummy")
                                    .domain("seasonvar.ru").secure().build())
                                override fun save(cookies: List<Cookie>) = Unit
                                override fun clear() = Unit
                            })
                            AccountRepository(sessions, transport, workers = scope, dispatcher = Dispatchers.Unconfined).also { it.restore() }
                        }
                        measuring = true
                        val pages = PersonalFeed.entries.filter { it != PersonalFeed.HISTORY }.mapIndexed { index, feed ->
                            async(start = CoroutineStart.UNDISPATCHED) { accounts[index % accounts.size].personalList(feed, windowed = true) }
                        }
                        val progress = async(start = CoroutineStart.UNDISPATCHED) { accounts[5 % accounts.size].progressSnapshot() }
                        val options = async(start = CoroutineStart.UNDISPATCHED) { accounts[6 % accounts.size].personalFilterOptions() }
                        val started = SystemClock.elapsedRealtimeNanos(); gate.complete(Unit)
                        val received = pages.awaitAll(); check(progress.await() != null); options.await()
                        val elapsed = (SystemClock.elapsedRealtimeNanos() - started) / 1000
                        val identity = IdentityHashMap<SeriesEntry, Boolean>()
                        received.forEach { page -> page.entries.forEach { identity[it] = true } }
                        val prefix = if (shared) "shared" else "independent"
                        result.putInt("${prefix}_resume_requests", count.get())
                        result.putInt("${prefix}_entry_objects", identity.size)
                        received.forEach { (it.entries as? ParsedSeriesEntries)?.window?.close() }
                        return count.get() to elapsed
                    }
                    resume(true)
                    val before = resume(false); val after = resume(true)
                    check(before.first == 7 && after.first == 1)
                    result.putString("independent_resume_ready_us", before.second.toString())
                    result.putString("shared_resume_ready_us", after.second.toString())
                    result.putBoolean("resume_shared", true)
                    val seeds = (1..2).map { SeriesEntry("$it", "https://seasonvar.ru/serial-$it-Fixture.html", "Fixture", 1, "", "") }
                    suspend fun continuation(shared: Boolean): Int {
                        val calculations = AtomicInteger(); val seasonReads = AtomicInteger(); val gate = CompletableDeferred<Unit>()
                        fun calculator() = SmartContinueRepository({ calculations.incrementAndGet(); gate.await(); seeds }, { entry ->
                            seasonReads.incrementAndGet()
                            MarkedSeason(SeriesDetails(entry, "100" + entry.id, listOf("Fixture"), emptyList(), emptyList(),
                                listOf(SeasonOption(entry.id, entry.url, "Season 1", 1)),
                                listOf(Translation("0", "Fixture", listOf(Episode("1_seriya", "1"), Episode("2_seriya", "2")))), "0"),
                                PlaybackMarkSnapshot(PlaybackMark(entry.id, entry.url, "1", "0", 30)))
                        }, loadHistoryPage = { AccountPage(AccountUser("12", "Fixture", null), seeds) })
                        val source = SharedSmartContinueSource({ SmartContinueKey("12", 1, 0, 0, 0, emptyList()) }, calculator(), scope)
                        val a = async(start = CoroutineStart.UNDISPATCHED) { if (shared) source.load() else calculator().load() }
                        val b = async(start = CoroutineStart.UNDISPATCHED) { if (shared) source.load(true) else calculator().load() }
                        gate.complete(Unit)
                        val first = a.await(); val second = b.await(); check(first.entries.size == 2 && second.entries.size == 2)
                        if (shared) check(first === second)
                        val prefix = if (shared) "shared" else "independent"
                        result.putInt("${prefix}_continue_builds", calculations.get())
                        result.putInt("${prefix}_season_reads", seasonReads.get())
                        return calculations.get()
                    }
                    check(continuation(false) == 2 && continuation(true) == 1)
                    result.putBoolean("continue_shared", true)
                    val runtime = Runtime.getRuntime()
                    runtime.gc()
                    val beforeHeap = runtime.totalMemory() - runtime.freeMemory()
                    val snapshot = AccountParser.snapshot(html)
                    val held = PersonalFeed.entries.filter { it != PersonalFeed.HISTORY }.map { snapshot.page(it, false) }
                    snapshot.progress
                    runtime.gc()
                    val afterHeap = runtime.totalMemory() - runtime.freeMemory()
                    check(held.sumOf { it.entries.size } == 3000)
                    result.putString("heap_before_kib", (beforeHeap / 1024).toString())
                    result.putString("heap_after_kib", (afterHeap / 1024).toString())
                    result.putInt("snapshot_rows", 2000)
                    result.putBoolean("fixture_only", true); result.putBoolean("script_success", true)
                } finally { scope.cancel() }
            }
            File(artifacts, "metrics.json").writeText(org.json.JSONObject().apply {
                result.keySet().sorted().forEach { key ->
                    put(key, when {
                        key.endsWith("_us") || key.endsWith("_kib") -> result.getString(key)
                        key.endsWith("_shared") || key in setOf("fixture_only", "script_success") -> result.getBoolean(key)
                        else -> result.getInt(key)
                    })
                }
            }.toString(2))
            result.putString("artifacts", artifacts.absolutePath)
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("failure_type", error.javaClass.simpleName)
            result.putString("failure_line", error.stackTrace.firstOrNull { it.fileName == "SharedSourcesProbe.kt" }?.lineNumber.toString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
