package ru.seasonvar.tv.data.smart

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.*
import ru.seasonvar.tv.data.cache.*

class SharedSeasonTest {
    @get:Rule val folder = TemporaryFolder()
    private val entry = SeriesEntry("2219", "https://seasonvar.ru/serial-2219-Test.html", "Test", 1, "", "")
    @Test fun fresh_continue_data_populates_the_common_card_cache_and_does_not_reuse_a_day_old_mark() = runBlocking {
        val scope = CacheScope(true, 1, "12")
        val disk = HtmlResponseCache(folder.newFolder(), { scope }, { scope }); disk.selectPrivateOwner("12")
        val fixture = javaClass.getResource("/series-details.html")!!.readText()
        var episode = "1"; var requests = 0
        val source = object : HtmlSource {
            override suspend fun sidebar(feed: PublicFeed): String = error("Unexpected list")
            override suspend fun get(url: String): String {
                requests++
                return fixture + "<div class='headblock-menu'><a href='/profile/12'>Test</a></div>" +
                    "<script>var mark={\"href\":\"/serial-2219-Test.html#rewind=" + episode + "_seriya_na_1_minute_0_sekunde\",\"trans\":\"0\"};</script>"
            }
        }
        val repository = CatalogRepository(folder.newFolder(), source, responseCache = disk)
        val first = repository.markedSeason(entry, "12")
        assertEquals("1", first.snapshot.mark!!.episode)
        val description = repository.description(entry)
        assertSame(description.paragraphs, repository.description(entry).paragraphs)
        repository.details(entry); assertEquals(1, requests)
        episode = "2"
        assertEquals("2", repository.markedSeason(entry, "12").snapshot.mark!!.episode)
        assertEquals(2, requests)
        try { repository.markedSeason(entry, "13"); fail("Wrong owner accepted") } catch (_: SessionExpiredException) { }
    }
}
