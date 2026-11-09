package ru.seasonvar.tv.data.images

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Window updates and completion callbacks run on the supplied scope's single dispatcher. */
internal class WindowPrefetcher<T>(
    private val scope: CoroutineScope,
    private val key: (T) -> String,
    private val load: suspend (T) -> Unit,
    private val parallelism: Int = LIST_RESOURCE_CONCURRENCY,
    private val permits: Semaphore = Semaphore(parallelism),
) {
    private var window = linkedMapOf<String, T>()
    private val active = mutableMapOf<String, Job>()
    private val attempted = mutableSetOf<String>()

    fun update(items: List<T>) {
        window = items.associateByTo(linkedMapOf(), key)
        attempted.retainAll(window.keys)
        for (id in active.keys.toList()) if (id !in window) active.remove(id)?.cancel()
        pump()
    }

    fun clear() = update(emptyList())

    private fun pump() {
        for ((id, item) in window) {
            if (active.size >= parallelism) break
            if (id in active || id in attempted) continue
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    permits.withPermit { load(item) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // A failed load must not affect the rest of the window.
                } finally {
                    if (active[id] === currentCoroutineContext()[Job]) {
                        active.remove(id)
                        attempted.add(id)
                        yield()
                        pump()
                    }
                }
            }
            active[id] = job
            job.start()
        }
    }
}
