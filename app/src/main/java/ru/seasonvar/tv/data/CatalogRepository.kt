package ru.seasonvar.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.LocalDate
import java.time.ZoneId
import ru.seasonvar.tv.data.cache.*

class CatalogRepository(
    private val directory: File,
    private val source: HtmlSource,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val cacheToken: () -> Long? = { 0L },
    responseCache: HtmlResponseCache? = null,
    cardCache: CardDataCache? = null,
) {
    val htmlCache = responseCache ?: HtmlResponseCache(directory,
        scope = { cacheToken()?.let { CacheScope(false, it) } }, now = nowMillis)
    val cardData = cardCache ?: CardDataCache(htmlCache, now = nowMillis)
    suspend fun cached(day: LocalDate, feed: PublicFeed = PublicFeed.TODAY): CatalogPage? =
        withContext(Dispatchers.IO) {
            val lease = htmlCache.capture()
            val saved = htmlCache.read(cacheName(feed), lease, fresh = { timestamp -> when (feed) {
                PublicFeed.TODAY -> java.time.Instant.ofEpochMilli(timestamp)
                    .atZone(zone).toLocalDate() == day
                PublicFeed.UPDATES, PublicFeed.TOP, PublicFeed.NEWEST, PublicFeed.BEST, PublicFeed.ANNOUNCEMENTS -> nowMillis() - timestamp in 0 until HtmlResponseCache.DAY
            } }) ?: return@withContext null
            runCatching { parse(saved.html, day, feed) }.getOrNull()?.takeIf { htmlCache.isCurrent(lease) }
        }

    suspend fun load(day: LocalDate, feed: PublicFeed = PublicFeed.TODAY): CatalogPage =
        withContext(Dispatchers.IO) {
            val lease = htmlCache.capture()
            val html = when (feed) {
                PublicFeed.TODAY -> source.get("https://seasonvar.ru/")
                PublicFeed.BEST -> source.get("https://seasonvar.ru/?mode=top")
                PublicFeed.ANNOUNCEMENTS -> source.get("https://seasonvar.ru/?mode=soon")
                PublicFeed.UPDATES, PublicFeed.TOP, PublicFeed.NEWEST -> source.sidebar(feed)
            }
            val parsed = parse(html, day, feed)
            if (!htmlCache.isCurrent(lease)) throw kotlinx.coroutines.CancellationException("List cache scope changed")
            htmlCache.save(cacheName(feed), html, lease)
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
        val selected = filters.safe()
        val cached = htmlCache.read(selected.cacheName, htmlCache.capture()) ?: return@withContext null
        runCatching {
            if (selected.switches.isEmpty()) BestFilterParser.parse(cached.html, day, selected)
            else {
                val saved = JSONObject(cached.html)
                require(saved.getInt("version") == 1)
                val values = saved.getJSONObject("filters")
                require(values.keys().asSequence().associateWith { values.getString(it) } == selected.values())
                val metadata = BestFilterParser.parse(saved.getString("metadata"), day, selected.base())
                require(metadata.options.normalize(selected) == selected)
                metadata.copy(page = SeasonvarParser.bestFragment(saved.getString("fragment"), day), filters = selected)
            }
        }.getOrNull()
    }

    suspend fun best(day: LocalDate, filters: BestListFilters,
        authenticatedMetadata: (suspend (BestListFilters) -> String)? = null): FilteredBestPage = withContext(Dispatchers.IO) {
        suspend fun metadata(selected: BestListFilters): String = if (selected.switches.any { it.personal })
            (authenticatedMetadata ?: throw ru.seasonvar.tv.data.account.SessionExpiredException())(selected)
            else source.get(selected.url())
        val lease = htmlCache.capture()
        var selected = filters.safe()
        var html = metadata(selected)
        currentCoroutineContext().ensureActive()
        val normalized = BestFilterParser.options(html).normalize(selected)
        if (selected.base() != normalized.base()) {
            html = metadata(normalized)
            currentCoroutineContext().ensureActive()
        }
        selected = normalized
        var result = BestFilterParser.parse(html, day, selected.base())
        var cached = html
        if (selected.switches.isNotEmpty()) {
            val fragment = source.bestFilters(selected)
            currentCoroutineContext().ensureActive()
            result = result.copy(page = SeasonvarParser.bestFragment(fragment, day), filters = selected)
            cached = JSONObject().put("version", 1).put("filters", JSONObject(selected.values()))
                .put("metadata", html).put("fragment", fragment).toString()
        }
        if (!htmlCache.isCurrent(lease)) throw kotlinx.coroutines.CancellationException("List cache scope changed")
        htmlCache.save(selected.cacheName, cached, lease)
        result
    }

    fun cachedDescription(entry: SeriesEntry): SeriesDescription? = cardData.description(entry.id)

    suspend fun description(entry: SeriesEntry): SeriesDescription = withContext(Dispatchers.IO) {
        cachedDescription(entry) ?: loadCard(entry, force = false).description
    }

    suspend fun details(entry: SeriesEntry, force: Boolean = false): SeriesDetails = withContext(Dispatchers.IO) {
        val lease = htmlCache.capture()
        if (!force) cardData.details(entry, lease)?.let { return@withContext it }
        val data = loadCard(entry, force, lease, needDetails = true)
        if (!htmlCache.isCurrent(lease)) throw kotlinx.coroutines.CancellationException("Card cache scope changed")
        cardData.contextual(data, entry) ?: throw MarkupChangedException()
    }

    private suspend fun loadCard(entry: SeriesEntry, force: Boolean, lease: CacheLease = htmlCache.capture(), needDetails: Boolean = false): CardData =
        cardData.load(entry, lease, force, fetch = { bypassCache ->
            val name = "series-" + entry.id + ".html"
            val cached = if (!force && !bypassCache) htmlCache.read(name, lease) else null
            cached ?: CachedHtml(source.get(entry.url), nowMillis())
        }, persist = { response ->
            if (!response.fromDisk) htmlCache.save("series-" + entry.id + ".html", response.html, lease)
        }, needDetails = needDetails)
}
