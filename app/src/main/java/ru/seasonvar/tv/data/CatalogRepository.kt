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
        PublicFeed.TODAY -> SeasonvarParser.today(html, day, windowed = true)
        PublicFeed.BEST -> SeasonvarParser.best(html, day, windowed = true)
        PublicFeed.ANNOUNCEMENTS -> SeasonvarParser.announcements(html, day, windowed = true)
        PublicFeed.UPDATES, PublicFeed.TOP, PublicFeed.NEWEST -> SeasonvarParser.sidebar(html, day, windowed = true)
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
        SeasonvarParser.search(source.get(url.toString()), query, windowed = true)
    }

    suspend fun cachedActorPage(actorUrl: String): CatalogPage? = withContext(Dispatchers.IO) {
        val url = requireNotNull(ActorPages.url(actorUrl))
        val lease = htmlCache.capture()
        val saved = htmlCache.read(ActorPages.cacheName(url), lease) ?: return@withContext null
        runCatching { ActorPages.parse(saved.html, url, LocalDate.now(zone), windowed = true) }
            .getOrNull()?.takeIf { htmlCache.isCurrent(lease) }
    }

    suspend fun actorPage(actorUrl: String): CatalogPage = withContext(Dispatchers.IO) {
        val url = requireNotNull(ActorPages.url(actorUrl))
        val lease = htmlCache.capture()
        val html = source.get(url)
        val page = ActorPages.parse(html, url, LocalDate.now(zone), windowed = true)
        if (!htmlCache.isCurrent(lease)) throw kotlinx.coroutines.CancellationException("List cache scope changed")
        htmlCache.save(ActorPages.cacheName(url), html, lease)
        page
    }

    suspend fun bestFilterOptions(): BestFilterOptions = withContext(Dispatchers.IO) {
        BestFilterParser.options(source.get("https://seasonvar.ru/?mode=top"))
    }

    suspend fun cachedBest(day: LocalDate, filters: BestListFilters): FilteredBestPage? = withContext(Dispatchers.IO) {
        val selected = filters.safe()
        val cached = htmlCache.read(selected.cacheName, htmlCache.capture()) ?: return@withContext null
        runCatching {
            if (selected.switches.isEmpty()) BestFilterParser.parse(cached.html, day, selected, windowed = true)
            else {
                val saved = JSONObject(cached.html)
                require(saved.getInt("version") == 1)
                val values = saved.getJSONObject("filters")
                require(values.keys().asSequence().associateWith { values.getString(it) } == selected.values())
                val metadata = BestFilterParser.parse(saved.getString("metadata"), day, selected.base(), windowed = true)
                require(metadata.options.normalize(selected) == selected)
                (metadata.page.entries as? ParsedSeriesEntries)?.window?.close()
                metadata.copy(page = SeasonvarParser.bestFragment(saved.getString("fragment"), day, windowed = true), filters = selected)
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
        var result = BestFilterParser.parse(html, day, selected.base(), windowed = true)
        var cached = html
        if (selected.switches.isNotEmpty()) {
            val fragment = source.bestFilters(selected)
            currentCoroutineContext().ensureActive()
            (result.page.entries as? ParsedSeriesEntries)?.window?.close()
            result = result.copy(page = SeasonvarParser.bestFragment(fragment, day, windowed = true), filters = selected)
            cached = JSONObject().put("version", 1).put("filters", JSONObject(selected.values()))
                .put("metadata", html).put("fragment", fragment).toString()
        }
        if (!htmlCache.isCurrent(lease)) throw kotlinx.coroutines.CancellationException("List cache scope changed")
        htmlCache.save(selected.cacheName, cached, lease)
        result
    }

    fun cachedDescription(entry: SeriesEntry): SeriesDescription? = cardData.description(entry.id)

    suspend fun catalogOptions(authenticated: (suspend () -> String)? = null): CatalogFilterOptions = withContext(Dispatchers.IO) {
        val lease = htmlCache.capture()
        val html = authenticated?.invoke() ?: source.get("https://seasonvar.ru/")
        val options = BrowseCatalogParser.options(html)
        currentCoroutineContext().ensureActive()
        if (!htmlCache.isCurrent(lease)) throw kotlinx.coroutines.CancellationException("Catalog scope changed")
        htmlCache.save("catalog-options.html", html, lease)
        options
    }

    suspend fun cachedCatalogOptions(): CatalogFilterOptions? = withContext(Dispatchers.IO) {
        htmlCache.read("catalog-options.html", htmlCache.capture())?.let { runCatching { BrowseCatalogParser.options(it.html) }.getOrNull() }
    }

    private fun catalogCacheName(filters: CatalogFilters) = HtmlResponseCache.requestKey("catalog-", "POST",
        "https://seasonvar.ru/index.php", CatalogFilters.defaults + filters.values)

    suspend fun cachedCatalog(filters: CatalogFilters): CatalogPage? = withContext(Dispatchers.IO) {
        val lease = htmlCache.capture()
        val reader = htmlCache.openReader(catalogCacheName(filters), lease) ?: return@withContext null
        val page = runCatching { CatalogPage(LocalDate.now(), BrowseCatalogParser.entries(reader)) }.getOrNull()
        if (htmlCache.isCurrent(lease)) page else {
            (page?.entries as? ParsedSeriesEntries)?.window?.close()
            null
        }
    }

    suspend fun catalog(filters: CatalogFilters): CatalogPage = withContext(Dispatchers.IO) {
        val lease = htmlCache.capture()
        val html = source.catalog(filters)
        val initial = BrowseCatalogParser.entries(html.reader())
        initial.window?.close()
        currentCoroutineContext().ensureActive()
        if (!htmlCache.isCurrent(lease)) throw kotlinx.coroutines.CancellationException("Catalog scope changed")
        htmlCache.save(catalogCacheName(filters), html, lease)
        val reader = htmlCache.openReader(catalogCacheName(filters), lease)
            ?: throw java.io.IOException("Catalog snapshot unavailable")
        CatalogPage(LocalDate.now(), BrowseCatalogParser.entries(reader))
    }

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
