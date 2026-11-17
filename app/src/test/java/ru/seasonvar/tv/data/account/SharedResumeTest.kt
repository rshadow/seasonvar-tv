package ru.seasonvar.tv.data.account

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.cache.SharedRequests
import ru.seasonvar.tv.data.ParsedSeriesEntries

@OptIn(ExperimentalCoroutinesApi::class)
class SharedResumeTest {
    @Test fun logout_cancels_a_response_waiting_for_its_computation_slot() = runTest {
        val html = javaClass.getResource("/account-resume-current.html")!!.readText()
        val store = MemorySessionStore().apply { cookies = listOf(okhttp3.Cookie.Builder().name("fixture").value("dummy")
            .domain("seasonvar.ru").secure().build()) }
        val scheduled = StandardTestDispatcher(testScheduler)
        var hold = false
        val queued = mutableListOf<Pair<kotlin.coroutines.CoroutineContext, Runnable>>()
        val cpu = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                if (hold) queued += context to block else scheduled.dispatch(context, block)
            }
        }
        val transport = object : AccountTransport {
            override suspend fun get(url: String, jar: SessionCookies) = html
            override suspend fun login(login: String, password: String, jar: SessionCookies) = error("No login")
            override suspend fun saveMark(mark: PlaybackMark, jar: SessionCookies) = error("No write")
        }
        val repository = AccountRepository(SessionManager(store), transport, workers = backgroundScope,
            dispatcher = scheduled, cpuDispatcher = cpu)
        repository.restore()
        hold = true
        val response = async { repository.personalList(PersonalFeed.RESUME, windowed = true) }
        runCurrent(); assertTrue(queued.isNotEmpty())
        repository.logout()
        hold = false
        queued.toList().forEach { (context, block) -> scheduled.dispatch(context, block) }
        queued.clear(); runCurrent()
        assertTrue(response.isCancelled)
        assertNull(repository.session.value.user)
    }
    @Test fun a_cancelled_reader_does_not_cancel_another_and_finished_requests_are_fresh() = runTest {
        val requests = SharedRequests<String, Any>(backgroundScope)
        val gate = CompletableDeferred<Unit>(); var count = 0
        suspend fun read() = requests.get("resume") { count++; gate.await(); Any() }
        val first = async { read() }; val second = async { read() }; runCurrent()
        assertEquals(1, count)
        first.cancelAndJoin(); gate.complete(Unit); runCurrent()
        assertNotNull(second.await()); read(); assertEquals(2, count)
    }

    @Test fun leaving_the_last_reader_or_invalidating_cancels_the_shared_load() = runTest {
        val requests = SharedRequests<String, Unit>(backgroundScope)
        val started = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>()
        val first = launch { requests.get("resume") { started.complete(Unit); try { awaitCancellation() } finally { stopped.complete(Unit) } } }
        started.await(); first.cancelAndJoin(); stopped.await()
        val gate = CompletableDeferred<Unit>()
        val second = async { requests.get("resume") { gate.await() } }; runCurrent()
        requests.cancel(); runCurrent(); assertTrue(second.isCancelled)
    }

    @Test fun one_native_snapshot_reuses_rows_but_each_view_has_an_independent_parsing_window() = runTest {
        val row = """<div class='pgs-marks-el' data-marksel='%d'><a href='/serial-%d-Test.html'></a><span class='pgs-marks-name'>Test</span><span class='pgs-marks-current'>1 из 2</span></div>"""
        val rows = (1..600).joinToString("") { row.format(java.util.Locale.ROOT, it, it) }
        val html = """<div class='headblock-menu'><a href='/profile/12'>Test</a></div><div class='pgs-marks'><li data-tabr='marks-new'>$rows</li><li data-tabr='marks-nonew'></li><li data-tabr='marks-want'></li><li data-tabr='marks-watched'></li></div>"""
        val snapshot = AccountParser.snapshot(html)
        val a = snapshot.page(PersonalFeed.RESUME, true).entries as ParsedSeriesEntries
        val b = snapshot.page(PersonalFeed.UPDATES, true).entries as ParsedSeriesEntries
        assertEquals(250, a.size); assertEquals(250, b.size); assertSame(a.first(), b.first())
        a.window!!.advance(200)
        assertEquals(500, a.window.state.value.entries.size); assertEquals(250, b.window!!.state.value.entries.size)
        a.window.close(); b.window.close()
        assertEquals(600, snapshot.progress.markedSeasonIds.size)
    }
    @Test fun all_personal_views_progress_and_filter_metadata_share_one_authenticated_response() = runTest {
        val html = javaClass.getResource("/account-resume-current.html")!!.readText() +
            javaClass.getResource("/account-filter-options.html")!!.readText()
        val store = MemorySessionStore().apply { cookies = listOf(okhttp3.Cookie.Builder().name("fixture").value("dummy")
            .domain("seasonvar.ru").secure().build()) }
        var calls = 0; var gate: CompletableDeferred<Unit>? = null
        val transport = object : AccountTransport {
            override suspend fun get(url: String, jar: SessionCookies): String { calls++; gate?.await(); return html }
            override suspend fun login(login: String, password: String, jar: SessionCookies) = error("No login")
            override suspend fun saveMark(mark: PlaybackMark, jar: SessionCookies) = error("No write")
        }
        val repository = AccountRepository(SessionManager(store), transport, workers = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler), cpuDispatcher = StandardTestDispatcher(testScheduler))
        repository.restore(); calls = 0; gate = CompletableDeferred()
        val lists = PersonalFeed.entries.filter { it != PersonalFeed.HISTORY }.map { feed -> async { repository.personalList(feed, windowed = true) } }
        val progress = async { repository.progressSnapshot() }; val options = async { repository.personalFilterOptions() }
        runCurrent(); assertEquals(1, calls)
        gate.complete(Unit)
        val pages = lists.awaitAll(); assertNotNull(progress.await()); assertTrue(options.await().genres.any { it.value == "17" })
        assertSame(pages[0].entries.first(), pages[1].entries.first())
        repository.resume(); assertEquals(2, calls)
        gate = CompletableDeferred()
        val stale = async { repository.resume() }; runCurrent()
        repository.invalidateSharedReads(); runCurrent(); assertTrue(stale.isCancelled)
        gate.complete(Unit)
    }

    @Test fun identical_filter_posts_are_shared_but_different_filters_and_logout_are_isolated() = runTest {
        val html = javaClass.getResource("/account-resume-current.html")!!.readText() +
            javaClass.getResource("/account-filter-options.html")!!.readText()
        val store = MemorySessionStore().apply { cookies = listOf(okhttp3.Cookie.Builder().name("fixture").value("dummy")
            .domain("seasonvar.ru").secure().build()) }
        val getGate = CompletableDeferred<Unit>(); val postGate = CompletableDeferred<Unit>()
        var loading = false; var gets = 0; var posts = 0
        val transport = object : AccountTransport {
            override suspend fun get(url: String, jar: SessionCookies): String {
                if (loading && url == PersonalFeed.RESUME.url) { gets++; getGate.await() }
                return html
            }
            override suspend fun filters(filters: PersonalListFilters, jar: SessionCookies): String {
                posts++; postGate.await(); return org.jsoup.Jsoup.parse(html).select("[data-tabr]").outerHtml()
            }
            override suspend fun login(login: String, password: String, jar: SessionCookies) = error("No login")
            override suspend fun saveMark(mark: PlaybackMark, jar: SessionCookies) = error("No write")
        }
        val repository = AccountRepository(SessionManager(store), transport, workers = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler), cpuDispatcher = StandardTestDispatcher(testScheduler))
        repository.restore(); loading = true
        val a = async { repository.filteredPersonalList(PersonalFeed.WANT, PersonalListFilters("17")) }
        val b = async { repository.filteredPersonalList(PersonalFeed.WATCHED, PersonalListFilters("17")) }
        val c = async { repository.filteredPersonalList(PersonalFeed.WANT, PersonalListFilters("18")) }
        runCurrent(); assertEquals(1, gets); getGate.complete(Unit); runCurrent(); assertEquals(2, posts)
        postGate.complete(Unit); awaitAll(a, b, c)
        val old = async { repository.resume() }; runCurrent()
        old.await()
        repository.logout(); assertNull(repository.session.value.user)
        assertTrue(runCatching { repository.resume() }.exceptionOrNull() is SessionExpiredException)
    }

}
