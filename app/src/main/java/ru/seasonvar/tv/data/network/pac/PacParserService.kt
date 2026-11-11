package ru.seasonvar.tv.data.network.pac

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.webkit.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONTokener
import ru.seasonvar.tv.data.settings.PacSettings
import kotlin.coroutines.resume

class PacParserService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var view: WebView? = null
    private var rendererGone: (() -> Unit)? = null
    private var busy = false
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        val reply = message.replyTo
        @Suppress("DEPRECATION")
        val descriptor = message.data.getParcelable<ParcelFileDescriptor>("file")
        val dns = message.data.getString("dns")
        if (message.what == 1 && reply != null && descriptor != null && dns != null && !busy) {
            busy = true
            scope.launch { parse(descriptor, dns, reply) }
        } else descriptor?.close()
        true
    })

    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onUnbind(intent: Intent): Boolean {
        // Процесс живёт ровно одно обращение; его смерть освобождает и старые однопроцессные WebView.
        android.os.Process.killProcess(android.os.Process.myPid())
        return false
    }

    private suspend fun parse(descriptor: ParcelFileDescriptor, dns: String, reply: Messenger) {
        val result = Bundle()
        try {
            withTimeout(PacSettings.EVALUATION_TIMEOUT_MILLIS - 1000) {
                val script = withContext(Dispatchers.IO) {
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        val bytes = input.readBytesBounded(PacSettings.MAX_BYTES)
                        bytes.toString(Charsets.UTF_8)
                    }
                }
                val hosts = JSONObject(dns)
                if (hosts.length() !in 1..PacSettings.MAX_BATCH_HOSTS) throw PacException(PacFailure.FORMAT)
                prepare()
                val helpers = "var __pacDns=" + hosts + ";" + """
                    function dnsResolve(h) { return Object.prototype.hasOwnProperty.call(__pacDns,h) ? __pacDns[h] : null; }
                    function isInNet(ip, pattern, mask) {
                        function n(s) { var p=s.split('.'); return ((+p[0]<<24)|(+p[1]<<16)|(+p[2]<<8)|(+p[3])); }
                        if (!ip) return false;
                        return (n(ip)&n(mask))===(n(pattern)&n(mask));
                    }
                    function isPlainHostName(h) { return h.indexOf('.')===-1; }
                    function dnsDomainIs(h,d) { return h.slice(-d.length)===d; }
                    function localHostOrDomainIs(h,d) { return h===d || (isPlainHostName(h) && d.indexOf(h+'.')===0); }
                    function isResolvable(h) { return dnsResolve(h)!==null; }
                    function dnsDomainLevels(h) { return h.split('.').length-1; }
                    function shExpMatch(s,p) { return new RegExp('^'+p.replace(/[.+^${'$'}{}()|[\]\\]/g,'\\${'$'}&').replace(/\*/g,'.*').replace(/\?/g,'.')+'${'$'}').test(s); }
                """.trimIndent()
                val loaded = evaluate("(function(){try{(0,eval)(" + JSONObject.quote(helpers + "\n" + script) + ");return typeof FindProxyForURL==='function'?'ok':'error';}catch(e){return 'error';}})()")
                if (loaded != "ok") throw PacException(PacFailure.FORMAT)
                val routes = JSONObject()
                for (host in hosts.keys()) {
                    val value = evaluate("FindProxyForURL(" + JSONObject.quote("https://$host/") + "," + JSONObject.quote(host) + ")")
                    PacRoute.parse(value)
                    routes.put(host, value)
                }
                result.putString("routes", routes.toString())
            }
        } catch (_: TimeoutCancellationException) { result.putString("failure", PacFailure.TIMEOUT.name) }
        catch (error: Exception) { result.putString("failure", ((error as? PacException)?.failure ?: PacFailure.WEBVIEW).name) }
        finally { descriptor.close(); closeView() }
        try { reply.send(Message.obtain(null, 1).apply { data = result }) } catch (_: RemoteException) { stopSelf() }
    }

    private suspend fun prepare() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_WEBVIEW) || WebView.getCurrentWebViewPackage() == null)
            throw PacException(PacFailure.WEBVIEW)
        suspendCancellableCoroutine<Unit> { continuation ->
            val web = WebView(this)
            view = web
            // PAC требует JavaScript; область принятого риска описана в ADR-0148.
            web.settings.javaScriptEnabled = true
            restrictPacWebView(web)
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) { if (continuation.isActive) continuation.resume(Unit) }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    rendererGone?.invoke()
                    return true
                }
            }
            web.loadDataWithBaseURL("https://pac.invalid/", "<html></html>", "text/html", "UTF-8", null)
        }
        if (evaluate("1+1") != "2") throw PacException(PacFailure.WEBVIEW)
    }

    private suspend fun evaluate(code: String): String = suspendCancellableCoroutine { continuation ->
        val web = checkNotNull(view)
        restrictPacWebView(web)
        web.evaluateJavascript(code) { value ->
            if (continuation.isActive) continuation.resume(JSONTokener(value).nextValue().toString())
        }
    }

    private suspend fun closeView() = withContext(NonCancellable) {
        val web = view
        if (Build.VERSION.SDK_INT >= 29) web?.webViewRenderProcess?.let { renderer ->
            withTimeoutOrNull(1000) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    rendererGone = { if (continuation.isActive) continuation.resume(Unit) }
                    if (!renderer.terminate() && continuation.isActive) continuation.resume(Unit)
                }
            }
        }
        rendererGone = null
        web?.destroy()
        view = null
    }
}

internal fun restrictPacWebView(web: WebView) {
    web.settings.blockNetworkLoads = true
    web.settings.allowFileAccess = false
    web.settings.allowContentAccess = false
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        if (output.size() + count > limit) throw PacException(PacFailure.FORMAT)
        output.write(buffer, 0, count)
    }
}
