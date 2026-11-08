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
    private val cacheToken: () -> Long? = { 0L },
) {
    suspend fun cached(day: LocalDate, feed: PublicFeed = PublicFeed.TODAY): CatalogPage? =
        withContext(Dispatchers.IO) {
            if (cacheToken() == null) return@withContext null
            val file = File(directory, cacheName(feed))
            if (!file.isFile) return@withContext null
            val fresh = when (feed) {
                PublicFeed.TODAY -> java.time.Instant.ofEpochMilli(file.lastModified())
                    .atZone(zone).toLocalDate() == day
                PublicFeed.UPDATES, PublicFeed.TOP, PublicFeed.NEWEST, PublicFeed.BEST, PublicFeed.ANNOUNCEMENTS -> nowMillis() - file.lastModified() in 0 until 86_400_000L
            }
            if (!fresh) return@withContext null
            runCatching { parse(file.readText(), day, feed) }.getOrNull()
        }

    suspend fun load(day: LocalDate, feed: PublicFeed = PublicFeed.TODAY): CatalogPage =
        withContext(Dispatchers.IO) {
            val token = cacheToken()
            val html = when (feed) {
                PublicFeed.TODAY -> source.get("https://seasonvar.ru/")
                PublicFeed.BEST -> source.get("https://seasonvar.ru/?mode=top")
                PublicFeed.ANNOUNCEMENTS -> source.get("https://seasonvar.ru/?mode=soon")
                PublicFeed.UPDATES, PublicFeed.TOP, PublicFeed.NEWEST -> source.sidebar(feed)
            }
            val parsed = parse(html, day, feed)
            if (token != null && cacheToken() == token) save(cacheName(feed), html)
            parsed
        }

    private fun parse(html: String, day: LocalDate, feed: PublicFeed): CatalogPage = when (feed) {
        PublicFeed.TODAY -> SeasonvarParser.today(html, day)
        PublicFeed.BEST -> SeasonvarParser.best(html, day)
        PublicFeed.ANNOUNCEMENTS -> SeasonvarParser.announcements(html, day)
        PublicFeed.UPDATES, PublicFeed.TOP, PublicFeed.NEWEST -> SeasonvarParser.sidebar(html, day)
    }

    private fun cacheName(feed: PublicFeed): String = when (feed) {
        PublicFeed.TODAY -> "today.html"
        PublicFeed.UPDATES -> "updates-ajax.html"
        PublicFeed.TOP -> "top-ajax.html"
        PublicFeed.NEWEST -> "newest-ajax.html"
        PublicFeed.BEST -> "best.html"
        PublicFeed.ANNOUNCEMENTS -> "announcements.html"
    }

    suspend fun search(query: String): List<SeriesEntry> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val url = "https://seasonvar.ru/autocomplete.php".toHttpUrl().newBuilder()
            .addQueryParameter("query", query).build()
        SeasonvarParser.search(source.get(url.toString()), query)
    }

    suspend fun bestFilterOptions(): BestFilterOptions = withContext(Dispatchers.IO) {
        BestFilterParser.options(source.get("https://seasonvar.ru/?mode=top"))
    }

    suspend fun cachedBest(day: LocalDate, filters: BestListFilters): FilteredBestPage? = withContext(Dispatchers.IO) {
        if (cacheToken() == null) return@withContext null
        val selected = filters.safe()
        val file = File(directory, selected.cacheName)
        if (!file.isFile || nowMillis() - file.lastModified() !in 0 until 86_400_000L) return@withContext null
        runCatching { BestFilterParser.parse(file.readText(), day, selected) }.getOrNull()
    }

    suspend fun best(day: LocalDate, filters: BestListFilters): FilteredBestPage = withContext(Dispatchers.IO) {
        val token = cacheToken()
        var selected = filters.safe()
        var html = source.get(selected.url())
        val normalized = BestFilterParser.options(html).normalize(selected)
        if (selected != normalized) {
            selected = normalized
            html = source.get(selected.url())
        }
        val result = BestFilterParser.parse(html, day, selected)
        if (token != null && cacheToken() == token) save(selected.cacheName, html)
        result
    }

    suspend fun description(entry: SeriesEntry): SeriesDescription {
        val token = withContext(Dispatchers.IO) { cacheToken() }
        val cached = withContext(Dispatchers.IO) {
            val file = File(directory, "series-" + entry.id + ".html")
            if (token != null && file.isFile && nowMillis() - file.lastModified() < 86_400_000L) {
                runCatching { SeasonvarParser.description(file.readText(), entry) }.getOrNull()
            } else null
        }
        if (cached != null) return cached
        return withContext(Dispatchers.IO) {
            val html = source.get(entry.url)
            val description = SeasonvarParser.description(html, entry)
            if (token != null && cacheToken() == token) save("series-" + entry.id + ".html", html)
            description
        }
    }

    suspend fun details(entry: SeriesEntry, force: Boolean = false): SeriesDetails = withContext(Dispatchers.IO) {
        val token = cacheToken()
        val name = "series-" + entry.id + ".html"
        val file = File(directory, name)
        if (token != null && !force && file.isFile && nowMillis() - file.lastModified() in 0 until 86_400_000L) {
            runCatching { SeriesDetailsParser.parse(file.readText(), entry) }.getOrNull()?.let { return@withContext it }
        }
        val html = source.get(entry.url)
        val details = SeriesDetailsParser.parse(html, entry)
        if (token != null && cacheToken() == token) save(name, html)
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
