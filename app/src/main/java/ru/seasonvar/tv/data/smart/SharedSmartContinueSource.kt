package ru.seasonvar.tv.data.smart

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.account.SessionExpiredException
import ru.seasonvar.tv.data.cache.SharedRequests
import java.util.concurrent.atomic.AtomicInteger

data class SmartContinueKey(val owner: String?, val session: Long, val marks: Long, val history: Long,
    val generation: Long, val voices: List<String>, val historyReset: Long = 0)

class SharedSmartContinueSource(
    private val key: () -> SmartContinueKey,
    private val repository: SmartContinueRepository,
    scope: CoroutineScope,
    changes: Flow<Unit> = emptyFlow(),
    private val prepare: suspend () -> Unit = {},
) {
    private val requests = SharedRequests<SmartContinueKey, SmartContinuePage>(scope)
    private val monitor = Any()
    private var current = key()
    private var ready: SmartContinuePage? = null
    private val foregroundReaders = AtomicInteger()
    val foreground get() = foregroundReaders.get() > 0

    init { scope.launch { changes.collect { invalidateIfChanged() } } }

    fun invalidateIfChanged() = synchronized(monitor) {
        val selected = key()
        if (selected != current) {
            current = selected
            ready = null
            requests.cancel()
        }
    }

    fun cached(owner: String): List<SeriesEntry>? = synchronized(monitor) {
        if (current == key() && current.owner == owner) ready?.entries else null
    }

    suspend fun load(foreground: Boolean = false): SmartContinuePage {
        val selected = synchronized(monitor) { invalidateIfChanged(); current }
        if (selected.owner == null) throw SessionExpiredException()
        if (foreground) foregroundReaders.incrementAndGet()
        try {
            return requests.get(selected) {
                if (key() != selected) throw CancellationException("Continue source changed")
                prepare()
                if (key() != selected) throw CancellationException("Continue source changed")
                val result = repository.load()
                synchronized(monitor) {
                    if (key() != selected) throw CancellationException("Continue source changed")
                    if (!result.incomplete) ready = result
                }
                result
            }.also { if (key() != selected) throw CancellationException("Continue source changed") }
        } finally { if (foreground) foregroundReaders.decrementAndGet() }
    }

}
