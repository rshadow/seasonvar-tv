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
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogStartupTest {
    @get:Rule val folder = TemporaryFolder()
    private val store = ViewModelStore()
    private class Source : HtmlSource {
        val requests = AtomicInteger()
        var gate: CompletableDeferred<Unit>? = null
        var requested: CompletableDeferred<Unit>? = null
        var seriesId = "123"
        override suspend fun get(url: String): String {
            requests.incrementAndGet()
            requested?.complete(Unit)
            gate?.await()
            val date = LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.uuuu", Locale.ROOT))
            return "<div class='news'><div class='news-head'>$date</div>" +
                "<a href='/serial-$seriesId-Fixture.html' data-id='$seriesId'><div class='news-w'>" +
                "<div class='news_n'>Сериал</div><span class='news_s'>1 серия</span></div></a></div>"
        }
        override suspend fun sidebar(feed: PublicFeed): String = error("Unexpected sidebar request")
    }
    @Before fun before() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun after() { store.clear(); Dispatchers.resetMain() }
    private fun model(source: Source, feed: PublicFeed = PublicFeed.TODAY, repository: CatalogRepository? = null) =
        CatalogViewModel(repository ?: CatalogRepository(folder.newFolder(), source), feed).also { store.put(feed.name, it) }
    private suspend fun ready(model: CatalogViewModel) = withTimeout(10_000) { model.state.first { it.loaded && !it.loading } }

    @Test fun construction_and_background_signals_do_not_start_any_unvisited_public_feed() {
        val source = Source()
        PublicFeed.entries.forEach { feed ->
            val model = model(source, feed)
            assertFalse(model.state.value.loading)
            assertFalse(model.state.value.loaded)
            model.sessionChanged()
            model.cancelForNetworkChange()
            model.refreshIfStarted()
            model.refreshIfDateChanged()
            assertFalse(model.state.value.loading)
        }
        assertEquals(0, source.requests.get())
    }

    @Test fun today_starts_on_activation_and_keeps_visited_background_refreshes() = runBlocking {
        val source = Source(); val model = model(source)
        model.activate(); assertEquals(1, ready(model).entries.size)
        model.activate(); assertEquals(1, source.requests.get())
        model.sessionChanged(); ready(model); assertEquals(2, source.requests.get())
        val rows = model.state.value.entries
        source.gate = CompletableDeferred()
        model.cancelForNetworkChange(); model.refreshIfStarted()
        assertSame(rows, model.state.value.entries)
        source.gate!!.complete(Unit); ready(model)
        assertEquals(3, source.requests.get())
    }

    @Test fun activating_today_waits_for_the_fresh_response_when_an_old_snapshot_exists() = runBlocking {
        val source = Source(); val repository = CatalogRepository(folder.newFolder(), source)
        repository.load(LocalDate.now(), PublicFeed.TODAY)
        assertEquals("123", repository.cached(LocalDate.now())!!.entries.single().id)
        val model = model(source, repository = repository)
        assertFalse(model.state.value.loaded)
        source.gate = CompletableDeferred()
        source.requested = CompletableDeferred()
        source.seriesId = "456"
        try {
            model.activate()
            withTimeout(10_000) { source.requested!!.await() }
            val pending = model.state.value
            assertTrue(pending.loading)
            assertFalse(pending.cached || pending.loaded)
            assertTrue(pending.entries.isEmpty())
        } finally { source.gate!!.complete(Unit) }
        assertEquals("456", ready(model).entries.single().id)
        assertEquals(2, source.requests.get())
    }
}
