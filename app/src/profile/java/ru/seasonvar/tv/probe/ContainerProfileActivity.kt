@file:OptIn(androidx.compose.runtime.InternalComposeTracingApi::class)

package ru.seasonvar.tv.probe

import android.content.pm.ApplicationInfo
import android.net.LocalServerSocket
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.tv.material3.Text
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import coil.Coil
import coil.ImageLoader
import coil.intercept.Interceptor
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import ru.seasonvar.tv.data.PublicFeed
import ru.seasonvar.tv.data.SeriesDescription
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.account.AccountSession
import ru.seasonvar.tv.data.account.AccountUser
import ru.seasonvar.tv.data.account.PersonalFeed
import ru.seasonvar.tv.ui.SeasonvarApp
import ru.seasonvar.tv.ui.catalog.CatalogLayout
import ru.seasonvar.tv.ui.catalog.CatalogUiState
import ru.seasonvar.tv.ui.catalog.SeriesListPlanningDiagnostics
import ru.seasonvar.tv.ui.navigation.Section
import ru.seasonvar.tv.ui.settings.InterfaceTheme
import java.io.File
import java.time.LocalDate
import kotlin.coroutines.resume

class ContainerProfileActivity : ComponentActivity() {
    private var plannerMode = false
    private var prefetch = true
    private var layout = CatalogLayout.STRIP
    private val planning = PlanningStatistics()
    private val rows by lazy { (1..250).map { index ->
        SeriesEntry(index.toString(), "https://seasonvar.ru/serial-$index-fixture.html",
            "Карточка $index", 1, "", if (plannerMode) "https://cdn.svstat.net/oblojka/$index.jpg"
                else "android.resource://ru.seasonvar.tv/drawable/" +
                    if (index % 2 == 0) "actor_placeholder" else "poster_placeholder",
            previewDescription = "Синтетическое описание карточки для измерения интерфейса.")
    } }
    private val ready by lazy { CatalogUiState(date = LocalDate.of(2026, 11, 15), entries = rows, loading = false, loaded = true) }
    private val changed by lazy { ready.copy(entries = rows.map { it.copy(previewDescription = it.previewDescription + " Обновлено.") }) }
    private val public by lazy { PublicFeed.entries.associateWith { MutableStateFlow(ready) } }
    private val personal by lazy { PersonalFeed.entries.associateWith { MutableStateFlow(ready) } }
    private val session = AccountSession(AccountUser("fixture", "Проверка", null), 1)
    private val tracer = ContainerCompositionTracer()
    private val frames = mutableListOf<LongArray>()
    private lateinit var frameThread: HandlerThread
    private lateinit var frameHandler: Handler
    private var asynchronous = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(android.os.Build.MODEL.contains("sdk", true))
        check(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        plannerMode = intent.getBooleanExtra("planner", false)
        prefetch = intent.getBooleanExtra("prefetch", true)
        layout = CatalogLayout.fromStorage(intent.getStringExtra("layout"))
        Coil.setImageLoader(ImageLoader.Builder(this).components {
            add(Interceptor { chain ->
                val source = chain.request.data.toString()
                if (source.startsWith("https://cdn.svstat.net/oblojka/")) {
                    val index = source.substringAfterLast('/').substringBefore('.').toInt()
                    val resource = if (index % 2 == 0) "actor_placeholder" else "poster_placeholder"
                    chain.proceed(chain.request.newBuilder().data("android.resource://ru.seasonvar.tv/drawable/$resource")
                        .memoryCacheKey(source).diskCacheKey(source).build())
                } else chain.proceed(chain.request)
            })
        }.build())
        val theme = when (intent.getStringExtra("theme")) {
            "plain" -> InterfaceTheme.CLASSIC
            "glass" -> InterfaceTheme.GLASS
            else -> error("Unknown theme")
        }
        val tracing = intent.getBooleanExtra("trace", false)
        asynchronous = intent.getBooleanExtra("asynchronous", false)
        val cases = if (plannerMode) listOf("idle", "hold_100ms", "hold_50ms")
            else if (asynchronous) listOf("navigation", "hidden_async_navigation") else scenarios
        val samples = intent.getIntExtra("samples", 48).also { require(it in 12..240) }
        require(!plannerMode || layout != CatalogLayout.GRID || samples <= 96)
        val rounds = intent.getIntExtra("rounds", 3).also { require(it in 1..5) }
        val run = requireNotNull(intent.getStringExtra("run")).also { require(it.matches(Regex("[a-z0-9-]{1,80}"))) }
        val completion = CompletableDeferred<String>()
        val server = LocalServerSocket("container-profile-$run")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = server.accept()
                try { client.outputStream.bufferedWriter().use { it.write(completion.await()); it.newLine() } }
                finally { client.close() }
            } finally { server.close() }
        }
        frameThread = HandlerThread("container-frame-metrics").also { it.start() }
        frameHandler = Handler(frameThread.looper)
        window.addOnFrameMetricsAvailableListener({ _, metrics, dropped ->
            synchronized(frames) { frames += longArrayOf(metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
                metrics.getMetric(FrameMetrics.TOTAL_DURATION), metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                metrics.getMetric(FrameMetrics.DRAW_DURATION), dropped.toLong()) }
        }, frameHandler)
        val lookupMode = intent.getBooleanExtra("lookup", false)
        setContent {
            if (lookupMode) ru.seasonvar.tv.ui.theme.SeasonvarTheme(theme) { Text("Замер поиска по каталогу…") }
            else Fixture(theme)
        }
        lifecycleScope.launch {
            val directory = File(checkNotNull(getExternalFilesDir(null)), "container-profile/$run")
            try {
                settle(12)
                if (lookupMode) {
                    val report = CatalogLookupBenchmark.run(samples, rounds)
                    directory.mkdirs()
                    File(directory, "metrics.json").writeText(report.toString(2))
                    completion.complete("complete $run")
                    return@launch
                }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                settle(12)
                for (scenario in cases) measure(scenario, 12)
                if (tracing && !plannerMode) Composer.setTracer(tracer)
                if (tracing && plannerMode) SeriesListPlanningDiagnostics.observer = planning
                val results = JSONArray()
                repeat(rounds) { round ->
                    val order = cases.drop(round % cases.size) + cases.take(round % cases.size)
                    for (scenario in order) {
                        public.values.forEach { it.value = ready }
                        personal.values.forEach { it.value = ready }
                        settle(8)
                        tracer.reset()
                        planning.reset()
                        val result = measure(scenario, samples)
                        drainFrames()
                        results.put(result.put("round", round).put("scenario", scenario)
                            .put("compositions", tracer.json()).put("planning", planning.json()))
                    }
                }
                Composer.setTracer(null)
                SeriesListPlanningDiagnostics.observer = null
                if (tracing && !plannerMode) check(tracer.observedProduction) { "No production Compose trace events" }
                if (tracing && plannerMode) check(planning.observed) { "No planning events" }
                directory.mkdirs()
                File(directory, "metrics.json").writeText(JSONObject().put("theme", theme.storageId)
                    .put("tracing", tracing).put("debuggable", false).put("fixture_only", true)
                    .put("cpu_window", if (asynchronous || plannerMode) "whole_tick" else "first_two_frames")
                    .put("planner", plannerMode).put("prefetch", prefetch).put("layout", layout.storageId)
                    .put("samples", samples).put("rounds", rounds).put("results", results).toString(2))
                completion.complete("complete $run")
            } catch (error: Throwable) {
                directory.mkdirs()
                File(directory, "failure.json").writeText(JSONObject().put("type", error.javaClass.simpleName)
                    .put("message", error.message).put("stack", error.stackTraceToString()).toString(2))
                completion.complete("failed $run")
            } finally {
                Composer.setTracer(null)
                SeriesListPlanningDiagnostics.observer = null
            }
        }
    }

    @Composable
    private fun Fixture(theme: InterfaceTheme) {
        // Повторяет границу подписок MainActivity; модели и сеть заменены готовыми состояниями.
        val states = public.mapValues { (_, flow) -> flow.collectAsStateWithLifecycle().value }
        val personalStates = personal.mapValues { (_, flow) -> flow.collectAsStateWithLifecycle().value }
        SeasonvarApp(states, {}, startPage = Section.TOP, interfaceTheme = theme,
            catalogLayouts = mapOf(Section.TOP to layout), accountSession = session,
            personalStates = personalStates, imagePrefetchEnabled = prefetch,
            loadDescription = { SeriesDescription(listOf(it.previewDescription.orEmpty())) })
    }

    private suspend fun measure(scenario: String, samples: Int): JSONObject {
        val cpu = mutableListOf<Double>()
        val runtimeBefore = runtimeStatistics()
        val start = System.nanoTime()
        var heldCode: Int? = null
        var downTime = 0L
        repeat(samples) { index ->
            val tick = System.nanoTime()
            val cpuStart = Debug.threadCpuTimeNanos()
            if (scenario.startsWith("hold_")) {
                val forward = index < samples / 2
                val code = if (layout == CatalogLayout.GRID) {
                    if (forward) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                } else if (forward) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                if (heldCode != code) {
                    heldCode?.let { heldKey(downTime, it, KeyEvent.ACTION_UP, 0) }
                    heldCode = code
                    downTime = android.os.SystemClock.uptimeMillis()
                }
                heldKey(downTime, code, KeyEvent.ACTION_DOWN, index % (samples / 2))
            }
            if (scenario in setOf("hidden", "hidden_navigation")) {
                val value = if (index % 2 == 0) changed else ready
                public.getValue(PublicFeed.NEWEST).value = value
                personal.getValue(PersonalFeed.WANT).value = value
            }
            if (scenario == "visible") public.getValue(PublicFeed.TOP).value = if (index % 2 == 0) changed else ready
            val delayed = if (scenario == "hidden_async_navigation") lifecycleScope.launch {
                delay(50)
                val value = if (index % 2 == 0) changed else ready
                public.getValue(PublicFeed.NEWEST).value = value
                personal.getValue(PersonalFeed.WANT).value = value
            } else null
            if (scenario in setOf("navigation", "hidden_navigation", "hidden_async_navigation"))
                key(if (index % 2 == 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
            settle(2)
            if (!asynchronous && !plannerMode) cpu += (Debug.threadCpuTimeNanos() - cpuStart) / 1_000_000.0
            val interval = if (scenario == "hold_50ms") 50L else 100L
            delay((interval - (System.nanoTime() - tick) / 1_000_000).coerceAtLeast(0))
            delayed?.join()
            if (asynchronous || plannerMode) cpu += (Debug.threadCpuTimeNanos() - cpuStart) / 1_000_000.0
        }
        heldCode?.let { heldKey(downTime, it, KeyEvent.ACTION_UP, 0) }
        val end = System.nanoTime()
        val runtimeAfter = runtimeStatistics()
        drainFrames()
        val selected = synchronized(frames) { frames.filter { it[0] in start..end } }
        return JSONObject().put("main_cpu_ms_per_tick", distribution(cpu))
            .put("runtime_delta", JSONObject().apply {
                runtimeBefore.forEach { (name, value) -> put(name, runtimeAfter.getValue(name) - value) }
            })
            .put("main_cpu_samples_ms", JSONArray(cpu))
            .put("window_ms", (end - start) / 1_000_000.0)
            .put("frames", selected.size)
            .put("frame_ms", distribution(selected.map { it[1] / 1_000_000.0 }))
            .put("frame_samples_ms", JSONArray(selected.map { it[1] / 1_000_000.0 }))
            .put("layout_ms", distribution(selected.map { it[2] / 1_000_000.0 }))
            .put("draw_ms", distribution(selected.map { it[3] / 1_000_000.0 }))
            .put("frames_over_16_7ms", selected.count { it[1] > 16_700_000 })
            .put("frames_over_50ms", selected.count { it[1] > 50_000_000 })
            .put("dropped_metric_reports", selected.sumOf { it[4] })
    }

    private fun key(code: Int) {
        window.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        window.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun heldKey(downTime: Long, code: Int, action: Int, repeat: Int) {
        window.decorView.dispatchKeyEvent(KeyEvent(downTime, android.os.SystemClock.uptimeMillis(), action, code, repeat))
    }

    private fun runtimeStatistics() = listOf("art.gc.bytes-allocated", "art.gc.gc-count", "art.gc.gc-time",
        "art.gc.blocking-gc-count", "art.gc.blocking-gc-time").associateWith {
        checkNotNull(Debug.getRuntimeStat(it)?.toLongOrNull()) { "Missing ART statistic $it" }
    }

    private suspend fun settle(count: Int) {
        repeat(count) {
            suspendCancellableCoroutine { continuation ->
                val choreographer = Choreographer.getInstance()
                val callback = Choreographer.FrameCallback { continuation.resume(Unit) }
                choreographer.postFrameCallback(callback)
                continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
            }
        }
    }

    private suspend fun drainFrames() = suspendCancellableCoroutine { continuation ->
        frameHandler.post { continuation.resume(Unit) }
    }

    override fun onDestroy() {
        Composer.setTracer(null)
        SeriesListPlanningDiagnostics.observer = null
        if (::frameThread.isInitialized) frameThread.quitSafely()
        super.onDestroy()
    }

    private companion object {
        val scenarios = listOf("idle", "hidden", "visible", "navigation", "hidden_navigation")
    }
}

private fun distribution(values: List<Double>): JSONObject {
    val sorted = values.sorted()
    fun percentile(fraction: Double): Any = if (sorted.isEmpty()) JSONObject.NULL
        else sorted[((sorted.size - 1) * fraction).toInt()]
    return JSONObject().put("count", sorted.size).put("median", percentile(0.5))
        .put("p95", percentile(0.95)).put("max", percentile(1.0)).put("sum", sorted.sum())
}

private class PlanningStatistics : SeriesListPlanningDiagnostics.Observer {
    private var updates = 0
    private var superseded = 0
    private val cpu = mutableListOf<Double>()
    private val wall = mutableListOf<Double>()
    private val applyCpu = mutableListOf<Double>()
    private val slots = mutableListOf<Double>()
    private val images = mutableListOf<Double>()
    var observed = false
        private set

    @Synchronized override fun update(superseded: Boolean) {
        updates++
        if (superseded) this.superseded++
    }

    @Synchronized override fun planned(cpuNanos: Long, wallNanos: Long, slots: Int, images: Int) {
        observed = true
        cpu += cpuNanos / 1_000_000.0
        wall += wallNanos / 1_000_000.0
        this.slots += slots.toDouble()
        this.images += images.toDouble()
    }

    @Synchronized override fun applied(cpuNanos: Long) { applyCpu += cpuNanos / 1_000_000.0 }

    @Synchronized fun reset() {
        updates = 0; superseded = 0
        cpu.clear(); wall.clear(); applyCpu.clear(); slots.clear(); images.clear()
    }

    @Synchronized fun json() = JSONObject().put("updates", updates).put("superseded_active_jobs", superseded)
        .put("plan_cpu_ms", distribution(cpu)).put("plan_wall_ms", distribution(wall))
        .put("apply_cpu_ms", distribution(applyCpu)).put("slots", distribution(slots)).put("images", distribution(images))
        .put("plan_cpu_samples_ms", JSONArray(cpu)).put("apply_cpu_samples_ms", JSONArray(applyCpu))
}

private class ContainerCompositionTracer : CompositionTracer {
    private data class Statistic(val name: String, var calls: Int = 0, var nanos: Long = 0, var selfNanos: Long = 0)
    private val statistics = mutableMapOf<Int, Statistic?>()
    private val slots = arrayOfNulls<Statistic>(512)
    private val starts = LongArray(512)
    private val children = LongArray(512)
    private var depth = 0
    var observedProduction = false
        private set

    override fun isTraceInProgress() = true

    override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
        val statistic = if (statistics.containsKey(key)) statistics[key] else {
            val value = info.takeIf { it.startsWith("ru.seasonvar.tv.") }?.substringBefore(" (")?.let(::Statistic)
            statistics[key] = value
            value
        }
        check(depth < starts.size)
        slots[depth] = statistic
        children[depth] = 0
        starts[depth++] = System.nanoTime()
    }

    override fun traceEventEnd() {
        val elapsed = System.nanoTime() - starts[--depth]
        slots[depth]?.let {
            it.calls++
            it.nanos += elapsed
            it.selfNanos += elapsed - children[depth]
            if (it.name.startsWith("ru.seasonvar.tv.ui.")) observedProduction = true
        }
        if (depth > 0) children[depth - 1] += elapsed
    }

    fun reset() {
        check(depth == 0)
        statistics.values.filterNotNull().forEach { it.calls = 0; it.nanos = 0; it.selfNanos = 0 }
    }

    fun json() = JSONArray().apply {
        statistics.values.filterNotNull().filter { it.calls > 0 }.sortedByDescending { it.nanos }.forEach {
            put(JSONObject().put("name", it.name).put("calls", it.calls)
                .put("inclusive_ms", it.nanos / 1_000_000.0).put("self_ms", it.selfNanos / 1_000_000.0))
        }
    }
}
