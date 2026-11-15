package ru.seasonvar.tv.diagnostics

import android.os.Debug
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import ru.seasonvar.tv.BuildConfig
import ru.seasonvar.tv.ui.catalog.CatalogUiState
import ru.seasonvar.tv.ui.navigation.Section

internal fun startupMark(name: String) {
    if (BuildConfig.BUILD_TYPE == "profile" && StartupDiagnostics.active) StartupDiagnostics.mark(name)
}

internal inline fun <T> startupMeasure(name: String, block: () -> T): T {
    if (BuildConfig.BUILD_TYPE != "profile" || !StartupDiagnostics.active) return block()
    val wall = SystemClock.elapsedRealtimeNanos()
    val cpu = Debug.threadCpuTimeNanos()
    return try { block() } finally {
        StartupDiagnostics.span(name, SystemClock.elapsedRealtimeNanos() - wall, Debug.threadCpuTimeNanos() - cpu)
    }
}

internal object StartupDiagnostics {
    @Volatile var active = true
        private set
    private val origin = android.os.Process.getStartElapsedRealtime() * 1_000_000L
    private val marks = linkedMapOf<String, Double>()
    private val spans = mutableListOf<Triple<String, Double, Double>>()
    private val requests = linkedMapOf<String, Int>()
    private var requested: String? = null
    private var resolved: String? = null
    private var listRequired = true
    private var focused = false
    private var accountReady = false
    private var dataLoaded = false
    private var failure = false
    private var data = JSONObject()
    private var finished = false
    val ready = CompletableDeferred<Unit>()

    @Synchronized fun configure(enabled: Boolean) {
        active = enabled
        if (!enabled) finished = true
    }

    @Synchronized fun mark(name: String) {
        if (!finished) marks.putIfAbsent(name, (SystemClock.elapsedRealtimeNanos() - origin) / 1_000_000.0)
    }

    @Synchronized fun span(name: String, wall: Long, cpu: Long) {
        if (!finished) spans += Triple(name, wall / 1_000_000.0, cpu / 1_000_000.0)
    }

    @Synchronized fun request(feed: String) {
        if (!finished) requests[feed] = (requests[feed] ?: 0) + 1
    }

    @Synchronized fun observe(initial: Section, target: Section?, section: Section, state: CatalogUiState, accountBusy: Boolean) {
        if (finished) return
        mark("composition_applied")
        accountReady = !accountBusy
        if (accountReady) mark("account_ready")
        requested = initial.storageId
        if (target != null) {
            resolved = target.storageId
            listRequired = target.publicFeed != null || target.personalFeed != null ||
                target == Section.CATALOG || target == Section.SMART_CONTINUE
        }
        if (section.storageId == resolved && listRequired) {
            dataLoaded = state.loaded
            failure = state.failure != null && !state.loaded
            data = JSONObject().put("loaded", state.loaded).put("cached", state.cached)
                .put("loading", state.loading).put("failed", state.failure != null).put("entries", state.entries.size)
            if (dataLoaded) mark("list_data_ready")
            if (failure) mark("list_failed")
        }
        completeIfReady()
    }

    @Synchronized fun targetFocused(section: Section) {
        if (section.storageId == resolved) {
            focused = true
            mark("target_focus")
            completeIfReady()
        }
    }

    private fun completeIfReady() {
        if (focused && accountReady && (!listRequired || dataLoaded || failure)) ready.complete(Unit)
    }

    @Synchronized fun snapshot(timedOut: Boolean): JSONObject {
        finished = true
        active = false
        return JSONObject().put("requested", requested).put("resolved", resolved)
            .put("timed_out", timedOut).put("list_required", listRequired).put("data", data)
            .put("marks_ms", JSONObject(marks.toMap())).put("public_loads", JSONObject(requests.toMap()))
            .put("spans", JSONArray().apply {
                spans.forEach { (name, wall, cpu) -> put(JSONObject().put("name", name).put("wall_ms", wall).put("cpu_ms", cpu)) }
            })
    }
}
