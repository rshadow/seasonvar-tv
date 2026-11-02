package ru.seasonvar.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.LocalDate
import java.time.ZoneId

class CatalogRepository(
    private val directory: File,
    private val source: HtmlSource,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun cached(day: LocalDate, feed: PublicFeed = PublicFeed.TODAY): CatalogPage? =
        withContext(Dispatchers.IO) {
            val file = File(directory, cacheName(feed))
            if (!file.isFile) return@withContext null
            val fresh = when (feed) {
                PublicFeed.TODAY -> java.time.Instant.ofEpochMilli(file.lastModified())
                    .atZone(zone).toLocalDate() == day
                PublicFeed.UPDATES, PublicFeed.TOP, PublicFeed.NEWEST, PublicFeed.BEST -> nowMillis() - file.lastModified() in 0 until 86_400_000L
            }
            if (!fresh) return@withContext null
            runCatching { parse(file.readText(), day, feed) }.getOrNull()
        }

    suspend fun load(day: LocalDate, feed: PublicFeed = PublicFeed.TODAY): CatalogPage =
        withContext(Dispatchers.IO) {
            val html = when (feed) {
                PublicFeed.TODAY -> source.get("https://seasonvar.ru/")
                PublicFeed.BEST -> source.get("https://seasonvar.ru/?mode=top")
                PublicFeed.UPDATES, PublicFeed.TOP, PublicFeed.NEWEST -> source.sidebar(feed)
            }
            val parsed = parse(html, day, feed)
            save(cacheName(feed), html)
            parsed
        }

    private fun parse(html: String, day: LocalDate, feed: PublicFeed): CatalogPage = when (feed) {
        PublicFeed.TODAY -> SeasonvarParser.today(html, day)
        PublicFeed.BEST -> SeasonvarParser.best(html, day)
        PublicFeed.UPDATES, PublicFeed.TOP, PublicFeed.NEWEST -> SeasonvarParser.sidebar(html, day)
    }

    private fun cacheName(feed: PublicFeed): String = when (feed) {
        PublicFeed.TODAY -> "today.html"
        PublicFeed.UPDATES -> "updates-ajax.html"
        PublicFeed.TOP -> "top-ajax.html"
        PublicFeed.NEWEST -> "newest-ajax.html"
        PublicFeed.BEST -> "best.html"
    }

    suspend fun search(query: String): List<SeriesEntry> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val url = "https://seasonvar.ru/autocomplete.php".toHttpUrl().newBuilder()
            .addQueryParameter("query", query).build()
        SeasonvarParser.search(source.get(url.toString()), query)
    }

    suspend fun description(entry: SeriesEntry): SeriesDescription {
        val cached = withContext(Dispatchers.IO) {
            val file = File(directory, "series-" + entry.id + ".html")
            if (file.isFile && nowMillis() - file.lastModified() < 86_400_000L) {
                runCatching { SeasonvarParser.description(file.readText(), entry) }.getOrNull()
            } else null
        }
        if (cached != null) return cached
        return withContext(Dispatchers.IO) {
            val html = source.get(entry.url)
            val description = SeasonvarParser.description(html, entry)
            save("series-" + entry.id + ".html", html)
            description
        }
    }

    suspend fun details(entry: SeriesEntry, force: Boolean = false): SeriesDetails = withContext(Dispatchers.IO) {
        val name = "series-" + entry.id + ".html"
        val file = File(directory, name)
        if (!force && file.isFile && nowMillis() - file.lastModified() in 0 until 86_400_000L) {
            runCatching { SeriesDetailsParser.parse(file.readText(), entry) }.getOrNull()?.let { return@withContext it }
        }
        val html = source.get(entry.url)
        val details = SeriesDetailsParser.parse(html, entry)
        save(name, html)
        details
    }

    private fun save(name: String, html: String) {
        runCatching {
            directory.mkdirs()
            val temporary = File.createTempFile("response-", ".html", directory)
            try {
                temporary.writeText(html)
                check(temporary.renameTo(File(directory, name)))
            } finally {
                temporary.delete()
            }
        }
    }
}
