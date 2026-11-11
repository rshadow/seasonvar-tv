package ru.seasonvar.tv.data.network.pac

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import ru.seasonvar.tv.data.network.*
import ru.seasonvar.tv.data.settings.PacSettings
import java.io.IOException
import java.net.Inet4Address
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PacCoordinator(context: Context, private val settings: NetworkSettings, private val scope: CoroutineScope) : PacRouteProvider {
    private val context = context.applicationContext
    private val bootstrap = NetworkClients(requestTimeoutMillis = PacSettings.FETCH_TIMEOUT_MILLIS)
    private val evaluator = WebViewPacEvaluator(context)
    private val managers = mutableMapOf<String, PacManager>()
    private val observers = mutableMapOf<String, Job>()
    private val downloadGates = mutableMapOf<String, PacDownloadGate>()
    private val schedule = MutableStateFlow(0L)
    private val mutableChanges = MutableStateFlow(0L)
    private val transportRevision = java.util.concurrent.atomic.AtomicLong()
    private var githubKey: Pair<NetworkConfiguration, Long>? = null
    private var githubTransport: OkHttpClient? = null
    val changes = mutableChanges.asStateFlow()
    override val revision: Long get() = transportRevision.get()

    fun manager(proxy: ProxyConfiguration): PacManager {
        val source = requireNotNull(proxy.pacSource())
        val key = source.key
        return synchronized(managers) { managers.getOrPut(key) {
            if (managers.size >= PacSettings.SOURCE_MEMORY_CAPACITY) managers.keys.firstOrNull { it != settings.configuration.value.proxy.pacSource()?.key }?.let { old ->
                managers.remove(old)
                observers.remove(old)?.cancel()
            }
            PacManager(pacStorage(context, proxy), evaluator,
                fetch = { fetch(source, it) }, lookup = { host, cfg ->
                    withContext(Dispatchers.IO) { bootstrap.forConfiguration(cfg.copy(proxy = ProxyConfiguration.Disabled))
                        .dns.lookup(host).filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress }
                }, onUpdated = { transportRevision.incrementAndGet() },
                downloadGate = downloadGates.getOrPut(source.key) { PacDownloadGate() },
                refreshCooldownMillis = source.refreshCooldownMillis).also { manager -> observers[key] = scope.launch {
                    var lastVersion = manager.revision
                    var downloading = false
                    manager.state.collect { state ->
                        if (settings.configuration.value.proxy.pacSource()?.key == key &&
                            (state.downloading != downloading || state.revision != lastVersion)) mutableChanges.update { it + 1 }
                        downloading = state.downloading
                        lastVersion = state.revision
                    }
                } }
        } }
    }

    override suspend fun prepare(configuration: NetworkConfiguration) {
        if (configuration.proxy.pacUrl() != null) manager(configuration.proxy).prepare(configuration)
    }
    override fun routes(host: String, configuration: NetworkConfiguration) = manager(configuration.proxy).routes(host, configuration)

    suspend fun activate(configuration: NetworkConfiguration) = manager(configuration.proxy).activate(configuration)
    fun reschedule() { schedule.update { it + 1 } }

    @Synchronized
    fun githubClient(base: OkHttpClient): OkHttpClient {
        val configuration = settings.configuration.value
        if (configuration.proxy.pacUrl() == null) return base
        val key = configuration to revision
        if (githubKey != key) {
            githubTransport?.connectionPool?.evictAll()
            val dns = bootstrap.forConfiguration(configuration.copy(proxy = ProxyConfiguration.Disabled)).dns
            githubTransport = base.newBuilder().pacTransport(this, configuration, dns)
                .connectionPool(ConnectionPool()).cookieJar(CookieJar.NO_COOKIES).build()
            githubKey = key
        }
        return checkNotNull(githubTransport)
    }

    fun start() {
        scope.launch {
            combine(settings.configuration, schedule) { configuration, _ -> configuration }.collectLatest { configuration ->
                if (configuration.proxy.pacUrl() == null) return@collectLatest
                val manager = manager(configuration.proxy)
                try { manager.activate(configuration) } catch (error: CancellationException) { throw error } catch (_: Exception) { }
                while (currentCoroutineContext().isActive) {
                    delay(manager.nextUpdateDelay())
                    try { manager.updateIfDue(configuration) } catch (error: CancellationException) { throw error } catch (_: Exception) { }
                }
            }
        }
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        connectivity.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) = invalidate()
            override fun onLost(network: android.net.Network) = invalidate()
            private fun invalidate() {
                synchronized(managers) { managers.values.forEach(PacManager::invalidateRoutes) }
                transportRevision.incrementAndGet()
                if (settings.configuration.value.proxy.pacUrl() != null) mutableChanges.update { it + 1 }
            }
        })
    }

    private suspend fun fetch(source: PacSource, configuration: NetworkConfiguration): ByteArray = suspendCancellableCoroutine { continuation ->
        val client = bootstrap.forConfiguration(configuration.copy(proxy = ProxyConfiguration.Disabled))
            .newBuilder().certificatePinner(source.downloadPins).build()
        val call = client.newCall(Request.Builder().url(source.url).header("User-Agent", source.userAgent)
            .header("Cache-Control", "no-cache, no-store").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(PacException(PacFailure.DOWNLOAD))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        if (!it.isSuccessful || it.body.contentLength() > PacSettings.MAX_BYTES) throw PacException(PacFailure.DOWNLOAD)
                        it.body.byteStream().use { input -> input.readBytesBounded(PacSettings.MAX_BYTES) }
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(PacException(PacFailure.DOWNLOAD))
                }
            }
        })
    }
}
