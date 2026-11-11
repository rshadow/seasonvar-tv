package ru.seasonvar.tv.data.network.pac

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.seasonvar.tv.data.network.NetworkConfiguration
import ru.seasonvar.tv.data.network.ProxyConfiguration
import ru.seasonvar.tv.data.settings.PacRefreshInterval
import ru.seasonvar.tv.data.settings.PacSettings
import java.util.LinkedHashMap

data class PacState(
    val fetchedAt: Long = 0,
    val downloading: Boolean = false,
    val preparing: Boolean = false,
    val failure: PacFailure? = null,
    val interval: PacRefreshInterval = PacRefreshInterval.DAY,
    val revision: Long = 0,
)

class PacDownloadGate {
    internal val operation = Mutex()
    @Volatile internal var blockedUntil = 0L
}

class PacManager(
    private val storage: PacStorage,
    private val evaluator: PacEvaluator,
    private val fetch: suspend (NetworkConfiguration) -> ByteArray,
    private val lookup: suspend (String, NetworkConfiguration) -> String?,
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val elapsedTime: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onUpdated: () -> Unit = {},
    private val downloadGate: PacDownloadGate = PacDownloadGate(),
    private val refreshCooldownMillis: Long = 0,
) : PacRouteProvider {
    private data class Key(val host: String, val configuration: NetworkConfiguration)
    private data class Record(val routes: List<PacRoute>, val at: Long)
    private val operation = Mutex()
    private val records = LinkedHashMap<Key, Record>(16, .75f, true)
    private val knownHosts = linkedSetOf<String>().apply { addAll(PacSettings.knownHosts) }
    private val mutableState = MutableStateFlow(PacState(storage.fetchedAt, interval = storage.interval))
    val state = mutableState.asStateFlow()
    init {
        if (refreshCooldownMillis > 0) {
            val restored = elapsedTime() + (storage.attemptedAt + refreshCooldownMillis - wallTime())
                .coerceIn(0, refreshCooldownMillis)
            synchronized(downloadGate) { downloadGate.blockedUntil = maxOf(downloadGate.blockedUntil, restored) }
        }
    }
    override val revision: Long get() = state.value.revision

    fun nextUpdateDelay(): Long = (maxOf(storage.fetchedAt, storage.attemptedAt) + storage.interval.millis - wallTime()).coerceAtLeast(1000)

    fun cooldownSeconds(): Int = if (refreshCooldownMillis == 0L) 0 else
        ((downloadGate.blockedUntil - elapsedTime()).coerceAtLeast(0) + 999).div(1000).toInt()

    suspend fun setInterval(interval: PacRefreshInterval) = withContext(Dispatchers.IO) {
        storage.interval = interval
        mutableState.update { it.copy(interval = interval) }
    }

    suspend fun activate(configuration: NetworkConfiguration) = withContext(Dispatchers.IO) {
        operation.withLock {
            val previous = revision
            if (!hasFile() || wallTime() - storage.fetchedAt >= storage.interval.millis) update(configuration)
            if (previous == revision) prepareHosts(configuration, PacSettings.knownHosts)
        }
    }

    override suspend fun prepare(configuration: NetworkConfiguration) = withContext(Dispatchers.IO) {
        operation.withLock {
            if (!hasFile()) update(configuration)
            val hosts = synchronized(records) {
                knownHosts.filter { host ->
                    val record = records[Key(host, configuration.copy(proxy = ProxyConfiguration.Disabled))]
                    record == null || elapsedTime() - record.at >= PacSettings.ROUTE_TTL_MILLIS
                }.take(PacSettings.MAX_BATCH_HOSTS)
            }
            if (hosts.isNotEmpty()) prepareHosts(configuration, hosts)
        }
    }

    suspend fun refresh(configuration: NetworkConfiguration): Boolean = withContext(Dispatchers.IO) {
        operation.withLock {
            if (cooldownSeconds() > 0) return@withLock false
            update(configuration)
            true
        }
    }

    suspend fun updateIfDue(configuration: NetworkConfiguration) {
        if (wallTime() - maxOf(storage.fetchedAt, storage.attemptedAt) >= storage.interval.millis) refresh(configuration)
    }

    fun invalidateRoutes() = synchronized(records) { records.clear() }

    override fun routes(host: String, configuration: NetworkConfiguration): List<PacRoute> {
        val key = Key(host, configuration.copy(proxy = ProxyConfiguration.Disabled))
        synchronized(records) {
            records[key]?.takeIf { elapsedTime() - it.at < PacSettings.ROUTE_TTL_MILLIS }?.let { return it.routes }
            knownHosts += host
            while (knownHosts.size > PacSettings.MAX_ROUTE_RECORDS) knownHosts.remove(knownHosts.first())
        }
        return runBlocking {
            withTimeout(PacSettings.FETCH_TIMEOUT_MILLIS + PacSettings.EVALUATION_TIMEOUT_MILLIS) {
                withContext(Dispatchers.IO) {
                    operation.withLock {
                        synchronized(records) {
                            records[key]?.takeIf { elapsedTime() - it.at < PacSettings.ROUTE_TTL_MILLIS }?.let { return@withLock it.routes }
                        }
                        if (!hasFile()) update(configuration)
                        val batch = synchronized(records) {
                            (listOf(host) + knownHosts.filter { name ->
                                val record = records[Key(name, key.configuration)]
                                record == null || elapsedTime() - record.at >= PacSettings.ROUTE_TTL_MILLIS
                            }).distinct().take(PacSettings.MAX_BATCH_HOSTS)
                        }
                        prepareHosts(configuration, batch)
                        synchronized(records) { records[key]?.routes ?: throw PacException(PacFailure.FORMAT) }
                    }
                }
            }
        }
    }

    private fun hasFile() = storage.fetchedAt > 0 && storage.file.isFile && storage.file.length() in 1..PacSettings.MAX_BYTES.toLong()

    private suspend fun update(configuration: NetworkConfiguration) = downloadGate.operation.withLock {
        if (cooldownSeconds() > 0) {
            if (!hasFile()) throw PacException(PacFailure.DOWNLOAD)
            return@withLock
        }
        storage.attemptedAt = wallTime()
        if (refreshCooldownMillis > 0) downloadGate.blockedUntil = elapsedTime() + refreshCooldownMillis
        mutableState.update { it.copy(downloading = true, failure = null) }
        try {
            val bytes = fetch(configuration)
            if (bytes.size !in 1..PacSettings.MAX_BYTES) throw PacException(PacFailure.FORMAT)
            storage.staging.writeBytes(bytes)
            val values = evaluate(storage.staging, configuration, PacSettings.knownHosts)
            storage.commit(wallTime())
            invalidateRoutes()
            saveRoutes(configuration, values)
            onUpdated()
            mutableState.update { it.copy(fetchedAt = storage.fetchedAt, revision = it.revision + 1, failure = null) }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            mutableState.update { it.copy(failure = (error as? PacException)?.failure ?: PacFailure.DOWNLOAD) }
            if (!hasFile()) throw error
        } finally { mutableState.update { it.copy(downloading = false, preparing = false) } }
    }

    private suspend fun prepareHosts(configuration: NetworkConfiguration, hosts: List<String>) {
        try {
            saveRoutes(configuration, evaluate(storage.file, configuration, hosts))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            mutableState.update { it.copy(failure = (error as? PacException)?.failure ?: PacFailure.WEBVIEW) }
            throw error
        } finally { mutableState.update { it.copy(preparing = false) } }
    }

    private suspend fun evaluate(file: java.io.File, configuration: NetworkConfiguration, hosts: List<String>): Map<String, List<PacRoute>> {
        mutableState.update { it.copy(preparing = true) }
        val queries = coroutineScope {
            hosts.map { host -> async {
                PacQuery(host, try { lookup(host, configuration) } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { null })
            } }.awaitAll()
        }
        val result = evaluator.evaluate(file, queries)
        if (result.keys != hosts.toSet()) throw PacException(PacFailure.FORMAT)
        return result
    }

    private fun saveRoutes(configuration: NetworkConfiguration, values: Map<String, List<PacRoute>>) = synchronized(records) {
        val selected = configuration.copy(proxy = ProxyConfiguration.Disabled)
        values.forEach { (host, routes) -> records[Key(host, selected)] = Record(routes, elapsedTime()) }
        while (records.size > PacSettings.MAX_ROUTE_RECORDS) records.remove(records.keys.first())
    }
}
