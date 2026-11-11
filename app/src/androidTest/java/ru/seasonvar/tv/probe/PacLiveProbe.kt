package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import kotlinx.coroutines.*
import okhttp3.Request
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.data.network.*
import ru.seasonvar.tv.data.network.pac.*

object PacLiveProbe {
    fun run(probe: Instrumentation) = with(probe) {
        val result = Bundle()
        var phase = "prepare"
        try {
            check(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.FINGERPRINT.contains("generic"))
            val app = targetContext.applicationContext as SeasonvarApplication
            val configuration = app.networkSettings.configuration.value.copy(proxy = ProxyConfiguration.AntiZapret)
            runBlocking {
                withTimeout(60_000) {
                    app.pac.manager(configuration.proxy).refresh(configuration)
                    app.pac.activate(configuration)
                    val manager = app.pac.manager(configuration.proxy)
                    check(manager.state.value.fetchedAt > 0 && manager.state.value.failure == null)
                    check(app.pac.routes("seasonvar.ru", configuration).any { it.type != PacRouteType.DIRECT })
                    result.putBoolean("original_pac_prepared", true)
                    result.putString("first_route_type", app.pac.routes("seasonvar.ru", configuration).first().type.name)
                    phase = "content"
                    app.pac.prepare(configuration)
                    ConnectionChecker(pac = app.pac).check(configuration) { target, response ->
                        result.putBoolean("live_" + target.name.lowercase(), response.status == ConnectionStatus.AVAILABLE)
                        if (response.failure != null) result.putString("failure_" + target.name.lowercase(), response.failure.name)
                    }
                    phase = "normal_video"
                    NetworkClients(pac = app.pac).use { normal ->
                        ConnectionChecker(clientFactory = { normal.forConfiguration(it) }).check(configuration) { target, response ->
                            if (target == ConnectionTarget.VIDEO) result.putBoolean("normal_video", response.status == ConnectionStatus.AVAILABLE)
                        }
                    }
                    phase = "github"
                    NetworkClients(pac = app.pac).use { transports ->
                        withContext(Dispatchers.IO) {
                            phase = "https_proxy"
                            val base = transports.forConfiguration(configuration)
                            check(app.pac.routes("seasonvar.ru", configuration).first().type == PacRouteType.HTTPS)
                            val preferred = base.proxySelector.select(java.net.URI("https://seasonvar.ru/")).first()
                            val connected = java.util.concurrent.atomic.AtomicReference<java.net.Proxy>()
                            val failures = java.util.concurrent.CopyOnWriteArrayList<String>()
                            base.newBuilder().eventListener(object : okhttp3.EventListener() {
                                override fun connectEnd(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress,
                                    proxy: java.net.Proxy, protocol: okhttp3.Protocol?) { connected.set(proxy) }
                                override fun connectFailed(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress,
                                    proxy: java.net.Proxy, protocol: okhttp3.Protocol?, ioe: java.io.IOException) {
                                    failures += ioe.javaClass.simpleName + ":" + (ioe.cause?.javaClass?.simpleName ?: "none")
                                    result.putString("failure_transport_detail", generateSequence<Throwable>(ioe) { it.cause }
                                        .joinToString("_") { it.message.orEmpty().take(200) }.take(600)
                                        .replace(Regex("[^A-Za-z0-9_./:-]"), "_"))
                                }
                            }).build().newCall(Request.Builder().url("https://seasonvar.ru/").build()).execute().use {
                                result.putString("https_status", it.code.toString())
                                result.putBoolean("preferred_connected", connected.get() == preferred)
                                result.putString("failure_transport", failures.joinToString("_"))
                                check(it.isSuccessful && connected.get() == preferred)
                                result.putBoolean("live_https_proxy", true)
                            }
                            base.newCall(Request.Builder()
                                .url("https://api.github.com/repos/rshadow/seasonvar-tv/releases/latest")
                                .header("User-Agent", "Seasonvar-TV-PAC-probe").build()).execute().use {
                                check(it.isSuccessful); result.putBoolean("live_github", true)
                            }
                        }
                    }
                }
            }
            result.putBoolean("read_only", true)
            result.putBoolean("script_success", true)
        } catch (error: Throwable) {
            result.putString("failure_type", error.javaClass.simpleName)
            result.putString("failure_phase", phase)
            result.putBoolean("script_success", false)
        }
        finish(Activity.RESULT_OK, result)
    }
}
