package ru.seasonvar.tv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.util.Locale

interface SearchHistoryStore {
    suspend fun read(): List<String>
    suspend fun write(queries: List<String>)
}

object SearchHistoryQueries {
    const val LIMIT = 20
    private const val MAX_LENGTH = 200
    fun normalize(query: String): String? = query.trim().replace(Regex("\\s+"), " ")
        .takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH }
    fun ordered(queries: List<String>): List<String> = queries.mapNotNull(::normalize)
        .distinctBy { it.lowercase(Locale.ROOT) }.take(LIMIT)
    fun remember(queries: List<String>, query: String): List<String> =
        normalize(query)?.let { ordered(listOf(it) + queries) } ?: queries
    fun decode(encoded: String?): List<String> = try {
        val values = JSONArray(encoded ?: "[]")
        ordered((0 until values.length()).mapNotNull { values.opt(it) as? String })
    } catch (_: Exception) { emptyList() }
    fun encode(queries: List<String>): String = JSONArray(ordered(queries)).toString()
}

class PreferencesSearchHistoryStore(context: Context) : SearchHistoryStore {
    private val context = context.applicationContext
    override suspend fun read(): List<String> = withContext(Dispatchers.IO) {
        SearchHistoryQueries.decode(context.getSharedPreferences("search-history", Context.MODE_PRIVATE).getString("queries", null))
    }
    override suspend fun write(queries: List<String>): Unit = withContext(Dispatchers.IO) {
        if (!context.getSharedPreferences("search-history", Context.MODE_PRIVATE).edit()
                .putString("queries", SearchHistoryQueries.encode(queries)).commit()) throw IOException("Search history was not saved")
    }
}

class SearchHistory(private val store: SearchHistoryStore) {
    private val mutex = Mutex()
    private var loaded = false
    private val mutableQueries = MutableStateFlow<List<String>>(emptyList())
    val queries = mutableQueries.asStateFlow()
    private suspend fun loadLocked() {
        if (!loaded) { mutableQueries.value = SearchHistoryQueries.ordered(store.read()); loaded = true }
    }
    suspend fun load() = mutex.withLock { loadLocked() }
    suspend fun remember(query: String) = mutex.withLock {
        loadLocked()
        val updated = SearchHistoryQueries.remember(queries.value, query)
        if (updated != queries.value) { store.write(updated); mutableQueries.value = updated }
    }
    suspend fun clear() = mutex.withLock {
        store.write(emptyList()); loaded = true; mutableQueries.value = emptyList()
    }
}
