package ru.seasonvar.tv.data.reviews

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.cache.*

class ReviewsRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val html = javaClass.getResource("/series-reviews.html")!!.readText()
    private val entry = SeriesEntry("101", "https://seasonvar.ru/serial-101-Test.html", "Тест", null, "", "")

    @Test fun first_page_reuses_the_card_html_and_more_uses_the_same_cache() = runBlocking {
        val cache = HtmlResponseCache(folder.newFolder())
        cache.save("series-101.html", html, cache.capture())
        var gets = 0
        var posts = 0
        val source = object : HtmlSource {
            override suspend fun get(url: String): String { gets++; return html }
            override suspend fun sidebar(feed: PublicFeed): String = error("Unexpected request")
            override suspend fun moreReviews(entry: SeriesEntry, cursor: String): String {
                posts++; return "<div class='pgs-review-post'>Другой отзыв</div>"
            }
        }
        val repository = ReviewsRepository(cache, source)
        assertEquals(2, repository.first(entry).entries.size)
        assertEquals(0, gets)
        assertEquals(1, repository.more(entry, "41").size)
        assertEquals(1, repository.more(entry, "41").size)
        assertEquals(1, posts)
        repository.first(entry, force = true)
        repository.more(entry, "41", force = true)
        assertEquals(1, gets)
        assertEquals(2, posts)
    }

    @Test fun account_change_rejects_a_late_response_and_private_html_is_not_reused() = runBlocking {
        var scope = CacheScope(true, 1, "fixture-one")
        val cache = HtmlResponseCache(folder.newFolder(), { scope }, { scope })
        cache.selectPrivateOwner("fixture-one")
        val source = object : HtmlSource {
            override suspend fun get(url: String): String {
                scope = CacheScope(false, 2)
                cache.clearPrivate()
                return html
            }
            override suspend fun sidebar(feed: PublicFeed): String = error("Unexpected request")
        }
        try { ReviewsRepository(cache, source).first(entry); fail("Expected scope cancellation") }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertNull(cache.read("series-101.html", cache.capture()))
    }
}
