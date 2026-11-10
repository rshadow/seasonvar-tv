package ru.seasonvar.tv.data

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.seasonvar.tv.data.settings.CacheSettings

data class ListParseState(val entries: List<SeriesEntry>, val hasMore: Boolean, val loading: Boolean = false,
    val failed: Boolean = false)

class ListParsingWindow private constructor(
    private var cursor: Iterator<SeriesEntry>?, private var release: (() -> Unit)?,
) : Closeable {
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val excludedSeasons = HashSet<String>()
    private val mutableState = MutableStateFlow(ListParseState(emptyList(), true))
    val state = mutableState.asStateFlow()

    private fun batch(checkActive: () -> Unit = {}) {
        val iterator = cursor ?: return
        val next = ArrayList<SeriesEntry>(CacheSettings.LIST_PARSE_BATCH_SIZE)
        while (next.size < CacheSettings.LIST_PARSE_BATCH_SIZE && iterator.hasNext()) {
            checkActive()
            check(!closed.get())
            val entry = iterator.next()
            if (entry.id !in excludedSeasons) next.add(entry)
        }
        val more = iterator.hasNext()
        mutableState.value = ListParseState(state.value.entries + next, more)
        if (!more) dispose()
    }

    // Смена фокуса не прерывает уже начатую порцию и не теряет позицию курсора.
    suspend fun advance(selectedIndex: Int) = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            val current = state.value
            if (closed.get() || !current.hasMore || current.loading || current.failed ||
                selectedIndex + 1 < current.entries.size - CacheSettings.LIST_PARSE_AHEAD) return@withLock
            mutableState.value = current.copy(loading = true, failed = false)
            try {
                val context = currentCoroutineContext()
                batch { context.ensureActive() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                close(); throw e
            } catch (_: Exception) {
                mutableState.value = current.copy(hasMore = false, failed = true)
                dispose()
            } finally { if (closed.get()) dispose() }
        }
    }

    private fun dispose() { cursor = null; release?.invoke(); release = null }

    suspend fun excludeSeason(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            excludedSeasons.add(id)
            mutableState.value = state.value.copy(entries = state.value.entries.filterNot { it.id == id })
        }
    }

    internal fun prepend(entries: List<SeriesEntry>) {
        check(!mutex.isLocked)
        mutableState.value = state.value.copy(entries = entries + state.value.entries)
    }
    override fun close() {
        closed.set(true)
        if (mutex.tryLock()) try { dispose() } finally { mutex.unlock() }
    }

    companion object {
        fun open(entries: Sequence<SeriesEntry>, release: (() -> Unit)? = null): ParsedSeriesEntries {
            val window = ListParsingWindow(entries.iterator(), release)
            try { window.batch() } catch (error: Throwable) { window.close(); throw error }
            return ParsedSeriesEntries(window.state.value.entries, window.takeIf { it.state.value.hasMore })
        }
    }
}

class ParsedSeriesEntries internal constructor(private val initial: List<SeriesEntry>, val window: ListParsingWindow?) : AbstractList<SeriesEntry>() {
    override val size get() = initial.size
    override fun get(index: Int) = initial[index]

    fun prepend(entries: List<SeriesEntry>): List<SeriesEntry> {
        window?.prepend(entries)
        return ParsedSeriesEntries(entries + initial, window)
    }
}

internal fun Sequence<SeriesEntry>.parseEntries(windowed: Boolean): List<SeriesEntry> =
    if (windowed) ListParsingWindow.open(this) else toList()
