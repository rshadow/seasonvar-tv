package ru.seasonvar.tv.data.network.pac

import android.content.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import ru.seasonvar.tv.data.settings.PacSettings
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebViewPacEvaluator(context: Context) : PacEvaluator {
    private val context = context.applicationContext
    private companion object { val evaluation = Mutex() }

    override suspend fun evaluate(file: File, queries: List<PacQuery>): Map<String, List<PacRoute>> = evaluation.withLock {
        evaluateIsolated(file, queries)
    }

    private suspend fun evaluateIsolated(file: File, queries: List<PacQuery>): Map<String, List<PacRoute>> = withContext(Dispatchers.Main) {
        if (queries.size !in 1..PacSettings.MAX_BATCH_HOSTS || file.length() !in 1..PacSettings.MAX_BYTES.toLong())
            throw PacException(PacFailure.FORMAT)
        var connection: ServiceConnection? = null
        var bound = false
        val dead = CompletableDeferred<Unit>()
        var connected = false
        try {
            withTimeout(PacSettings.EVALUATION_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine { continuation ->
                    val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
                        if (continuation.isActive) {
                            try {
                                val failure = message.data.getString("failure")
                                if (failure != null) throw PacException(PacFailure.valueOf(failure))
                                val data = JSONObject(checkNotNull(message.data.getString("routes")))
                                val values = queries.associate { it.host to PacRoute.parse(data.getString(it.host)) }
                                if (data.length() != values.size) throw PacException(PacFailure.FORMAT)
                                continuation.resume(values)
                            } catch (error: Exception) { continuation.resumeWithException(error) }
                        }
                        true
                    })
                    val service = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            if (!continuation.isActive) return
                            try {
                                connected = true
                                binder.linkToDeath({ dead.complete(Unit) }, 0)
                                val dns = JSONObject().apply { queries.forEach { put(it.host, it.ipv4 ?: JSONObject.NULL) } }
                                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                                    Messenger(binder).send(Message.obtain(null, 1).apply {
                                        replyTo = replies
                                        data = Bundle().apply { putParcelable("file", descriptor); putString("dns", dns.toString()) }
                                    })
                                }
                            } catch (_: Exception) { continuation.resumeWithException(PacException(PacFailure.WEBVIEW)) }
                        }
                        override fun onServiceDisconnected(name: ComponentName) {
                            if (continuation.isActive) continuation.resumeWithException(PacException(PacFailure.WEBVIEW))
                        }
                        override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
                        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
                    }
                    connection = service
                    bound = context.bindService(Intent(context, PacParserService::class.java), service, Context.BIND_AUTO_CREATE)
                    if (!bound && continuation.isActive) continuation.resumeWithException(PacException(PacFailure.WEBVIEW))
                }
            }
        } catch (_: TimeoutCancellationException) { throw PacException(PacFailure.TIMEOUT) }
        finally {
            if (bound) connection?.let(context::unbindService)
            if (connected) withContext(NonCancellable) { withTimeoutOrNull(1500) { dead.await() } }
        }
    }
}
