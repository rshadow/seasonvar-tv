package ru.seasonvar.tv.data.account

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.*
import java.nio.file.Files

class AccountCacheTest {
    private val html get() = javaClass.getResource("/series-details.html")!!.readText()
    private val entry = SeriesEntry("2219", "https://seasonvar.ru/serial-2219-test.html", "Тест", 1, "", "")
    @Test fun private_responses_neither_read_nor_overwrite_public_metadata_cache() = runBlocking {
        val directory = Files.createTempDirectory("account-cache-test").toFile()
        try {
            var token: Long? = 0
            var calls = 0
            val source = object : HtmlSource {
                override suspend fun get(url: String): String {
                    calls++
                    return html + if (token == null) "<div>Private test marker</div>" else ""
                }
                override suspend fun sidebar(feed: PublicFeed) = error("Unexpected request")
            }
            val repository = CatalogRepository(directory, source, cacheToken = { token })
            repository.details(entry)
            val file = directory.resolve("series-2219.html")
            val public = file.readText()
            token = null
            repository.details(entry)
            repository.description(entry)
            assertEquals(3, calls)
            assertEquals(public, file.readText())
            token = 1
            repository.details(entry)
            assertEquals(3, calls)
        } finally { directory.deleteRecursively() }
    }
    @Test fun response_that_crosses_an_account_change_cannot_be_written_to_public_cache() = runBlocking {
        val directory = Files.createTempDirectory("account-race-test").toFile()
        try {
            var token: Long? = 0
            val source = object : HtmlSource {
                override suspend fun get(url: String): String { token = null; return html }
                override suspend fun sidebar(feed: PublicFeed) = error("Unexpected request")
            }
            val repository = CatalogRepository(directory, source, cacheToken = { token })
            repository.details(entry)
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }
}
