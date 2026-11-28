package ru.seasonvar.tv.probe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Debug
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.*
import org.json.JSONObject
import ru.seasonvar.tv.data.SeasonvarParser
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.SeriesDescription
import ru.seasonvar.tv.data.cache.*
import ru.seasonvar.tv.data.work.BackgroundCpu
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.io.File
import kotlin.coroutines.CoroutineContext

internal class ContentionWorkload(private val context: Context) {
    private val disk = HtmlResponseCache(File(context.cacheDir, "contention-fixture"))
    val cache = CardDataCache(disk)
    private var subscriptions = 0
    private var cancellations = 0
    private var subscriptionCpu = 0L
    private val observer = CardCacheDiagnostics.Observer { opened, cpu ->
        if (opened) subscriptions++ else cancellations++
        subscriptionCpu += cpu
    }
    private var beforeCache = intArrayOf()

    suspend fun description(entry: SeriesEntry): SeriesDescription = cache.description(entry.id) ?: cache.load(entry,
        disk.capture(), false, {
            CachedHtml("""<html><div class="pgs-sinfo" data-id-season="${entry.id}" data-id-serial="1"><h1 class="pgs-sinfo-title">Карточка</h1><div class="pgs-sinfo-info"><p>Описание для проверки общего кеша.</p></div></div></html>""", System.currentTimeMillis())
        }).description
    private val statistics = Statistics()
    private val dispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val queued = System.nanoTime()
            BackgroundCpu.dispatcher.dispatch(context) {
                val start = Debug.threadCpuTimeNanos()
                statistics.enter(System.nanoTime() - queued)
                try { block.run() }
                finally { statistics.leave(Debug.threadCpuTimeNanos() - start) }
            }
        }
    }
    private val loader = ImageLoader.Builder(context).memoryCache(null).diskCache(null)
        .decoderDispatcher(dispatcher).build()
    private fun image(width: Int, height: Int) = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        repeat(64) { index ->
            paint.color = android.graphics.Color.rgb(index * 3, 80, 255 - index * 3)
            canvas.drawRect(0f, index * height / 64f, width.toFloat(), (index + 1) * height / 64f, paint)
        }
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
        bitmap.recycle()
        output.toByteArray()
    }
    private val stressImage = image(1080, 1600)
    private val thumbnail = image(200, 300)
    private val html = buildString {
        for (index in 1..250) append("""<a class="pst rside-p" href="/serial-$index-Fixture.html"><span class="rside-t">Карточка $index</span><span class="rside-t_en">Fixture $index</span><span class="rside-ss">Сезон 1 <span>1 серия</span></span></a>""")
    }
    private var jobs = emptyList<Job>()
    private var parses = 0
    private var images = 0
    private val batches = mutableListOf<Double>()
    private var finite = false
    private var batchStart = 0L
    private var unfinished = 0

    fun start(scope: CoroutineScope, scenario: String) {
        check(jobs.isEmpty())
        parses = 0; images = 0; statistics.reset()
        subscriptions = 0; cancellations = 0; subscriptionCpu = 0
        beforeCache = cache.diagnosticCounts()
        CardCacheDiagnostics.observer = observer
        batches.clear()
        finite = scenario == "burst_mixed"
        if (!finite) launchWork(scope, scenario)
    }

    fun tick(scope: CoroutineScope, index: Int) {
        if (finite && index == 0) launchWork(scope, "busy_mixed")
    }

    private fun launchWork(scope: CoroutineScope, scenario: String) {
        batchStart = System.nanoTime()
        unfinished = (if (scenario in setOf("busy_parse", "busy_mixed")) 3 else 0) +
            (if (scenario in setOf("busy_images", "busy_mixed")) 3 else 0)
        jobs = buildList {
            if (scenario in setOf("busy_parse", "busy_mixed")) repeat(3) {
                add(scope.launch {
                    var count = 0
                    while (isActive && (!finite || count < 4)) {
                        withContext(dispatcher) {
                            val page = SeasonvarParser.sidebar(html, LocalDate.of(2026, 11, 17), windowed = true)
                            check(page.entries.size == 250)
                        }
                        parses++; count++
                        yield()
                    }
                    completedWorker()
                })
            }
            if (scenario in setOf("busy_images", "busy_mixed")) repeat(3) {
                add(scope.launch {
                    var count = 0
                    while (isActive && (!finite || count < 8)) {
                        val result = loader.execute(ImageRequest.Builder(context).data(if (finite) thumbnail else stressImage)
                            .size(if (finite) 256 else 600, if (finite) 384 else 900).scale(coil.size.Scale.FILL)
                            .allowHardware(false).memoryCachePolicy(CachePolicy.DISABLED).build())
                        check(result is SuccessResult)
                        images++; count++
                        yield()
                    }
                    completedWorker()
                })
            }
        }
    }

    private fun completedWorker() {
        if (--unfinished == 0 && finite) batches += (System.nanoTime() - batchStart) / 1_000_000.0
    }

    suspend fun stop(): JSONObject {
        jobs.forEach(Job::cancel)
        jobs.joinAll()
        jobs = emptyList()
        CardCacheDiagnostics.observer = null
        return statistics.json().put("lists_parsed", parses).put("images_decoded", images)
            .put("mark_subscriptions", subscriptions).put("mark_cancellations", cancellations)
            .put("mark_subscription_cpu_ms", subscriptionCpu / 1_000_000.0)
            .put("cache_before", org.json.JSONArray(beforeCache.toList()))
            .put("cache_after", org.json.JSONArray(cache.diagnosticCounts().toList()))
            .put("completed_batch_ms", org.json.JSONArray(batches))
    }

    fun close() { jobs.forEach(Job::cancel); loader.shutdown(); CardCacheDiagnostics.observer = null }

    private class Statistics {
        private var active = 0
        private var maximum = 0
        private var slices = 0
        private var cpu = 0L
        private var wait = 0L
        private var maxWait = 0L
        @Synchronized fun enter(queued: Long) {
            active++; maximum = maxOf(maximum, active); slices++
            wait += queued; maxWait = maxOf(maxWait, queued)
        }
        @Synchronized fun leave(used: Long) { active--; cpu += used }
        @Synchronized fun reset() { check(active == 0); maximum = 0; slices = 0; cpu = 0; wait = 0; maxWait = 0 }
        @Synchronized fun json() = JSONObject().put("peak_cpu_workers", maximum).put("cpu_slices", slices)
            .put("worker_cpu_ms", cpu / 1_000_000.0).put("queue_wait_total_ms", wait / 1_000_000.0)
            .put("queue_wait_max_ms", maxWait / 1_000_000.0)
    }
}
