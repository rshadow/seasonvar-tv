package ru.seasonvar.tv.probe

import android.os.Debug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.seasonvar.tv.data.ListParsingWindow
import ru.seasonvar.tv.data.SeriesEntry

internal object CatalogLookupBenchmark {
    @Volatile private var checksum = 0
    private var retainedKeys: List<String>? = null
    private var retainedIndex: Map<String, Int>? = null

    suspend fun run(samples: Int, rounds: Int): JSONObject = withContext(Dispatchers.Default) {
        val source = ListParsingWindow.open((1..34000).asSequence().map { index ->
            SeriesEntry(index.toString(), "https://seasonvar.ru/serial-$index-Serial_dlya_proverki-1-season.html",
                "Карточка $index", 1, "", "https://cdn.svstat.net/oblojka/$index.jpg")
        })
        val window = checkNotNull(source.window)
        val results = JSONArray()
        val construction = JSONArray()
        try {
            for ((size, position) in listOf(250 to 0, 250 to 200, 1000 to 900, 10000 to 9900, 33000 to 32900)) {
                while (window.state.value.entries.size < size) window.advance(window.state.value.entries.size)
                val rows = window.state.value.entries
                check(rows.size == size && window.state.value.hasMore)
                val keysAllocated = allocated()
                val keysCpu = Debug.threadCpuTimeNanos()
                val keys = rows.map { it.key }
                val keyCpuMs = (Debug.threadCpuTimeNanos() - keysCpu) / 1_000_000.0
                val keyBytes = allocated() - keysAllocated
                val indexAllocated = allocated()
                val indexCpu = Debug.threadCpuTimeNanos()
                val index = HashMap<String, Int>(size * 4 / 3 + 1)
                keys.forEachIndexed { i, key -> index.putIfAbsent(key, i) }
                construction.put(JSONObject().put("size", size).put("keys_cpu_ms", keyCpuMs).put("keys_allocated", keyBytes)
                    .put("map_cpu_ms", (Debug.threadCpuTimeNanos() - indexCpu) / 1_000_000.0)
                    .put("map_allocated", allocated() - indexAllocated))
                val query = arrayOf(rows[position].key, rows[position + 1].key)
                val variants = listOf("current", "cached_keys", "cached_one_scan", "index", "known_position")
                for (variant in variants) repeat(4) { checksum = lookup(variant, rows, keys, index, query[it % 2], position + it % 2) }
                repeat(rounds) { round ->
                    val order = variants.drop(round % variants.size) + variants.take(round % variants.size)
                    for (variant in order) {
                        val cpuValues = mutableListOf<Double>()
                        val wallValues = mutableListOf<Double>()
                        val iterations = when (variant) { "current" -> 1; "index", "known_position" -> 4096; else -> 16 }
                        val bytes = allocated()
                        val collections = statistic("art.gc.gc-count")
                        repeat(samples) { sample ->
                            val start = System.nanoTime()
                            val cpu = Debug.threadCpuTimeNanos()
                            var sum = 0
                            repeat(iterations) { offset ->
                                sum += lookup(variant, rows, keys, index, query[(sample + offset) % 2], position + (sample + offset) % 2)
                            }
                            cpuValues += (Debug.threadCpuTimeNanos() - cpu) / 1_000_000.0 / iterations
                            wallValues += (System.nanoTime() - start) / 1_000_000.0 / iterations
                            checksum = sum
                        }
                        results.put(JSONObject().put("size", size).put("position", position).put("variant", variant)
                            .put("round", round).put("queries", samples * iterations)
                            .put("cpu_samples_ms", JSONArray(cpuValues)).put("wall_samples_ms", JSONArray(wallValues))
                            .put("allocated_bytes", allocated() - bytes)
                            .put("gc_count", statistic("art.gc.gc-count") - collections))
                    }
                }
                val expected = 3 * position + rows[position].id.toInt()
                variants.forEach { check(lookup(it, rows, keys, index, query[0], position) == expected) }
                variants.forEach { check(lookup(it, rows, keys, index, "missing", position) == -1) }
                val reversed = rows.reversed()
                val moved = rows.size - 1 - position
                check(lookup("known_position", reversed, emptyList(), emptyMap(), query[0], position) ==
                    3 * moved + rows[position].id.toInt())
                val removed = rows.filterIndexed { i, _ -> i != position }
                check(lookup("known_position", removed, emptyList(), emptyMap(), query[0], position) == -1)
            }
            JSONObject().put("lookup", true).put("debuggable", false).put("fixture_only", true)
                .put("results", results).put("construction", construction)
                .put("retained_memory", retainedMemory(window.state.value.entries)).put("checksum", checksum)
        } finally { window.close() }
    }

    private fun retainedMemory(rows: List<SeriesEntry>): JSONArray = JSONArray().apply {
        repeat(3) {
            retainedKeys = null; retainedIndex = null
            Runtime.getRuntime().gc()
            val before = usedHeap()
            retainedKeys = rows.map { it.key }
            Runtime.getRuntime().gc()
            val keysHeap = usedHeap()
            retainedIndex = HashMap<String, Int>(rows.size * 4 / 3 + 1).apply {
                checkNotNull(retainedKeys).forEachIndexed { i, key -> putIfAbsent(key, i) }
            }
            Runtime.getRuntime().gc()
            val withIndex = usedHeap()
            put(JSONObject().put("entries", rows.size).put("keys_bytes", keysHeap - before)
                .put("index_bytes", withIndex - keysHeap).put("total_bytes", withIndex - before))
            checksum = checkNotNull(retainedIndex).size
        }
        retainedKeys = null; retainedIndex = null
    }

    private fun usedHeap() = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

    private fun lookup(variant: String, rows: List<SeriesEntry>, keys: List<String>, index: Map<String, Int>, key: String, hint: Int): Int {
        if (variant == "current") {
            val previous = rows.indexOfFirst { it.key == key }
            val absent = rows.none { it.key == key }
            val advance = rows.indexOfFirst { it.key == key }
            val selected = rows.firstOrNull { it.key == key }
            return if (absent || selected == null) -1 else previous + advance + previous + selected.id.toInt()
        }
        if (variant == "cached_keys") {
            val previous = keys.indexOfFirst { it == key }
            val absent = keys.none { it == key }
            val advance = keys.indexOfFirst { it == key }
            val selected = keys.indexOfFirst { it == key }
            return if (absent || selected < 0) -1 else previous + advance + previous + rows[selected].id.toInt()
        }
        val found = when (variant) {
            "index" -> index[key] ?: -1
            "known_position" -> if (rows.getOrNull(hint)?.key == key) hint else rows.indexOfFirst { it.key == key }
            else -> keys.indexOfFirst { it == key }
        }
        return if (found < 0) -1 else 3 * found + rows[found].id.toInt()
    }

    private fun allocated() = statistic("art.gc.bytes-allocated")
    private fun statistic(name: String) = checkNotNull(Debug.getRuntimeStat(name)?.toLongOrNull())
}
