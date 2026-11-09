package ru.seasonvar.tv.probe

import android.content.Context
import kotlinx.coroutines.*
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.cache.*
import ru.seasonvar.tv.data.settings.CacheSettings
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class SharedCardCacheFixture(context: Context, private val requests: ConcurrentHashMap<String, AtomicInteger>) {
    private val directory = File(context.cacheDir, "shared-card-probe-" + System.nanoTime())
    private val session = AtomicReference(CacheScope(false, 0))
    private var delayedId: String? = null
    private val started = CompletableDeferred<Unit>()
    private val gate = CompletableDeferred<Unit>()
    val disk = HtmlResponseCache(directory, session::get, session::get)
    val cards = CardDataCache(disk)
    val repository = CatalogRepository(directory, object : HtmlSource {
        override suspend fun get(url: String): String {
            val id = url.substringAfterLast('/')
            requests.getOrPut(id) { AtomicInteger() }.incrementAndGet()
            val response = html(id)
            if (id == delayedId) { started.complete(Unit); withContext(NonCancellable) { gate.await() } }
            delay(40)
            return response
        }
        override suspend fun sidebar(feed: PublicFeed): String = error("No remote lists in fixture")
    }, responseCache = disk, cardCache = cards)

    suspend fun verifyLogout(entry: SeriesEntry) = withContext(Dispatchers.IO) {
        session.set(CacheScope(true, 1, "12")); disk.selectPrivateOwner("12"); cards.clearPrivate()
        repository.details(entry.copy(accountMark = AccountMark("7", "Смотрю")))
        check(cards.details(entry, disk.capture())?.entry?.accountMark != null)
        check(File(directory, "private/series-" + entry.id + ".html").isFile)
        delayedId = "32000"
        val pending = async { repository.description(entry.copy(id = "32000", url = "/32000")) }
        started.await()
        session.set(CacheScope(false, 2)); disk.clearPrivate(); cards.clearPrivate()
        gate.complete(Unit)
        check(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
        check(cards.description(entry.id) != null && cards.description("32000") == null)
        check(cards.details(entry, disk.capture()) == null && !File(directory, "private").exists())
    }

    suspend fun measureCapacity(): Long = withContext(Dispatchers.IO) {
        val runtime = Runtime.getRuntime()
        System.gc(); delay(100)
        val before = runtime.totalMemory() - runtime.freeMemory()
        for (id in 1000..(1000 + CacheSettings.CARD_DATA_CAPACITY + 100)) {
            val entry = SeriesEntry("$id", "/$id", "Сериал $id", 1, "", "")
            cards.load(entry, disk.capture(), false, { CachedHtml(html("$id", if (id == 2000) 1000 else 40), System.currentTimeMillis()) })
        }
        check(cards.size == CacheSettings.CARD_DATA_CAPACITY)
        System.gc(); delay(100)
        ((runtime.totalMemory() - runtime.freeMemory() - before).coerceAtLeast(0) / 1024)
    }

    suspend fun measureNativeMemory(): Map<String, Long> = withContext(Dispatchers.IO) {
        val state = AtomicReference(CacheScope(true, 0, "memory-fixture"))
        val store = HtmlResponseCache(File(directory, "memory"), state::get, state::get)
        store.selectPrivateOwner("memory-fixture")
        suspend fun fill(cache: CardDataCache, count: Int) {
            for (id in 1..count) {
                val entry = SeriesEntry("$id", "https://seasonvar.ru/serial-$id-Test.html", "Сериал $id", 1, "", "",
                    accountMark = AccountMark("12 из 40 — $id", "Смотрю"),
                    accountProgress = AccountProgress(ProgressCounter(1, 3), SeasonEpisodeProgress("$id", ProgressCounter(12, 40))))
                val episodeCount = if (id == 1000) 1000 else 40
                val data = cache.load(entry, store.capture(), false, {
                    CachedHtml(html("$id", episodeCount, rich = true), System.currentTimeMillis())
                })
                val details = checkNotNull(data.details)
                check(details.translations.singleOrNull()?.episodes?.size == episodeCount)
                check(details.actors.size == 5 && data.description.paragraphs.joinToString().length >= 500)
            }
        }
        suspend fun warm() { fill(CardDataCache(store, capacity = 4), 16) }
        suspend fun used(): Long {
            var lowest = Long.MAX_VALUE
            repeat(3) {
                System.gc(); System.runFinalization(); delay(150)
                val runtime = Runtime.getRuntime()
                lowest = minOf(lowest, runtime.totalMemory() - runtime.freeMemory())
            }
            return lowest
        }
        warm()
        val measured = CardDataCache(store)
        val before = used()
        fill(measured, CacheSettings.CARD_DATA_CAPACITY)
        check(measured.size == CacheSettings.CARD_DATA_CAPACITY)
        val filled = used()
        measured.clearPrivate()
        val common = used()
        check(measured.size == CacheSettings.CARD_DATA_CAPACITY && measured.description("1") != null)
        mapOf("cache_records" to measured.size.toLong(), "native_total_kib" to ((filled - before).coerceAtLeast(0) / 1024),
            "native_common_kib" to ((common - before).coerceAtLeast(0) / 1024),
            "native_private_kib" to ((filled - common).coerceAtLeast(0) / 1024))
    }

    fun close() { directory.deleteRecursively() }

    private fun html(id: String, count: Int = 0, rich: Boolean = false): String {
        val episodes = if (count == 0) "" else {
            val values = (1..count).joinToString(",") { number ->
                "\"$number\":{\"n\":\"$number\"" + (if (number < count) ",\"next\":\"${number + 1}\"" else "") + "}"
            }
            "<ul class='pgs-trans'><li data-translate='0'>Стандартный</li></ul>" +
                "<script>var arEpisodes={\"0\":{\"\":{\"next\":\"1\"},$values}};</script>"
        }
        val more = if (rich) " Описание сезона, героев и основных событий сериала.".repeat(10) else ""
        val actors = if (rich) "<div class='pgs-sinfo-actor'>" + (1..5).joinToString("") { "<a>Актёр $it</a>" } + "</div>" else ""
        return "<div class='pgs-sinfo' data-id-season='$id' data-id-serial='99'>" +
            "<div class='pgs-sinfo-info'><p>Описание: Сериал " + id.padStart(5, '0') + more + "</p></div>" +
            "<div class='pgs-sinfo_list'>Год:<span>2026</span></div>" + actors + "</div>" + episodes
    }
}
