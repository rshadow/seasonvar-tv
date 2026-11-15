package ru.seasonvar.tv.ui.catalog

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.seasonvar.tv.data.*
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogNetworkRefreshTest {
    @get:Rule val folder = TemporaryFolder()
    private val store = ViewModelStore()
    private val html = javaClass.getResource("/top-ajax.html")!!.readText()
    private class Source(var response: String) : HtmlSource {
        val requests = AtomicInteger()
        var gate: CompletableDeferred<Unit>? = null
        var entered = CompletableDeferred<Unit>()
        var completed = CompletableDeferred<Unit>()
        override suspend fun get(url: String): String = error("Unexpected source")
        override suspend fun sidebar(feed: PublicFeed): String {
            requests.incrementAndGet()
            val value = response; val pending = gate; val done = completed
            currentCoroutineContext().job.invokeOnCompletion { done.complete(Unit) }
            entered.complete(Unit)
            if (pending != null) withContext(NonCancellable) { pending.await() }
            return value
        }
    }
    @Before fun before() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun after() { store.clear(); Dispatchers.resetMain() }
    private suspend fun ready(model: CatalogViewModel) = withTimeout(10_000) { model.state.first { it.loaded && !it.loading } }

    @Test fun a_visited_public_list_updates_in_background_and_keeps_rows_while_loading() = runBlocking {
        val source = Source(html)
        val model = CatalogViewModel(CatalogRepository(folder.newFolder(), source), PublicFeed.TOP).also { store.put("top", it) }
        model.refreshIfStarted(); assertEquals(0, source.requests.get())
        model.activate(); val rows = ready(model).entries
        source.gate = CompletableDeferred(); source.entered = CompletableDeferred()
        model.cancelForNetworkChange(); model.refreshIfStarted()
        withTimeout(10_000) { source.entered.await() }
        assertSame(rows, model.state.value.entries); assertEquals(2, source.requests.get())
        source.gate!!.complete(Unit); ready(model)
        model.activate(); assertEquals(2, source.requests.get())
    }

    @Test fun cancelled_old_response_cannot_replace_the_new_list_or_disk_cache() = runBlocking {
        val source = Source(html)
        val repository = CatalogRepository(folder.newFolder(), source)
        val model = CatalogViewModel(repository, PublicFeed.TOP).also { store.put("top", it) }
        model.activate(); ready(model)
        val old = CompletableDeferred<Unit>(); val oldDone = CompletableDeferred<Unit>()
        source.gate = old; source.completed = oldDone; source.entered = CompletableDeferred()
        model.refreshIfStarted(); withTimeout(10_000) { source.entered.await() }
        model.cancelForNetworkChange()
        source.gate = null; source.response = html.replace("</a>", " новое</a>"); source.completed = CompletableDeferred()
        model.refreshIfStarted(); val latest = ready(model).entries
        old.complete(Unit); withTimeout(10_000) { oldDone.await() }
        assertSame(latest, model.state.value.entries)
        assertEquals(latest.map { it.title }, repository.cached(LocalDate.now(), PublicFeed.TOP)!!.entries.map { it.title })
    }
}
