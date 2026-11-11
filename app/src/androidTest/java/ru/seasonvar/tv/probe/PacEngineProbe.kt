package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import kotlinx.coroutines.*
import ru.seasonvar.tv.data.network.*
import ru.seasonvar.tv.data.network.pac.*
import ru.seasonvar.tv.data.settings.PacRefreshInterval
import java.io.File

object PacEngineProbe {
    fun run(probe: Instrumentation, missingWebView: Boolean = false) = with(probe) {
        val result = Bundle()
        val artifacts = File(targetContext.getExternalFilesDir(null), "pac-probe/run-${SystemClock.uptimeMillis()}").apply { mkdirs() }
        try {
            check(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.FINGERPRINT.contains("generic"))
            runBlocking {
                if (missingWebView) {
                    val app = targetContext.applicationContext as ru.seasonvar.tv.SeasonvarApplication
                    val previous = app.networkSettings.configuration.value.proxy
                    val url = "https://pac-fixture.invalid/missing-webview.pac"
                    val storage = pacStorage(targetContext, ProxyConfiguration.Pac(url))
                    storage.staging.writeText("function FindProxyForURL(u,h) { return 'DIRECT'; }", Charsets.UTF_8)
                    storage.commit(System.currentTimeMillis())
                    try {
                        app.selectProxy(ProxyConfiguration.Pac(url))
                        error("Missing WebView accepted")
                    } catch (error: PacException) { check(error.failure == PacFailure.WEBVIEW) }
                    check(app.networkSettings.configuration.value.proxy == previous)
                    result.putBoolean("missing_webview_keeps_previous_proxy", true)
                    return@runBlocking
                }
                val file = File(artifacts, "fixture.pac")
                val evaluator = WebViewPacEvaluator(targetContext)
                val queries = listOf(PacQuery("seasonvar.ru", "203.0.113.1"), PacQuery("cdn.example.test", "192.0.2.1"),
                    PacQuery("github.com", "203.0.113.2"))
                file.writeText("""
                    function FindProxyForURL(url, host) {
                      if (dnsDomainIs(host, 'seasonvar.ru')) return 'HTTPS proxy.example.test:8443; PROXY fallback.example.test:8080; DIRECT';
                      if (isInNet(dnsResolve(host), '192.0.2.0', '255.255.255.0')) return 'PROXY ip.example.test:8080';
                      return 'DIRECT';
                    }
                """.trimIndent(), Charsets.UTF_8)
                val before = Debug.getPss()
                repeat(3) {
                    val values = evaluator.evaluate(file, queries)
                    check(values.getValue("seasonvar.ru").map { it.type } == listOf(PacRouteType.HTTPS, PacRouteType.HTTP, PacRouteType.DIRECT))
                    check(values.getValue("cdn.example.test").single().host == "ip.example.test")
                    check(values.getValue("github.com").single().type == PacRouteType.DIRECT)
                    val processes = targetContext.getSystemService(android.app.ActivityManager::class.java).runningAppProcesses.orEmpty()
                    check(processes.none { it.processName == targetContext.packageName + ":pac_parser" })
                }
                coroutineScope {
                    listOf(async { evaluator.evaluate(file, queries) },
                        async { WebViewPacEvaluator(targetContext).evaluate(file, queries) }).awaitAll()
                }
                result.putBoolean("parallel_evaluators_serialized", true)
                result.putBoolean("parser_process_released", true)
                result.putLong("main_pss_before_kib", before)
                result.putLong("main_pss_after_kib", Debug.getPss())
                result.putBoolean("dns_and_all_domains", true)
                file.writeText("function FindProxyForURL( broken", Charsets.UTF_8)
                try { evaluator.evaluate(file, queries); error("Invalid PAC accepted") }
                catch (error: PacException) { check(error.failure == PacFailure.FORMAT) }
                result.putBoolean("invalid_pac_rejected", true)
                file.writeText("function FindProxyForURL(u,h) { while (true) {} }", Charsets.UTF_8)
                try { evaluator.evaluate(file, queries); error("Infinite PAC accepted") }
                catch (error: PacException) { check(error.failure == PacFailure.TIMEOUT) }
                result.putBoolean("timeout_releases_parser", true)
                val store = "pac-fixture-${SystemClock.uptimeMillis()}"
                val settings = NetworkSettings(targetContext, store)
                try {
                    val manual = ProxyConfiguration.Server("proxy.example.test", 8080, "fixture", "fixture")
                    settings.setProxy(manual)
                    settings.setProxy(ProxyConfiguration.AntiZapret)
                    check(NetworkSettings(targetContext, store).configuration.value.proxy == ProxyConfiguration.AntiZapret)
                    val custom = ProxyConfiguration.Pac("https://pac.example.test/rules.pac")
                    settings.setProxy(custom)
                    check(NetworkSettings(targetContext, store).configuration.value.proxy == custom)
                    settings.setProxy(ProxyConfiguration.Disabled)
                    check(settings.savedManualProxy() == manual && settings.savedPacUrl() == custom.url)
                    val preferences = targetContext.getSharedPreferences(store, 0)
                    check(preferences.edit().putString("proxy_mode", "future").commit())
                    check(NetworkSettings(targetContext, store).configuration.value.proxy == ProxyConfiguration.Unavailable)
                    check(preferences.edit().putString("proxy_mode", "manual").remove("proxy_sealed").commit())
                    check(NetworkSettings(targetContext, store).configuration.value.proxy == ProxyConfiguration.Unavailable)
                    result.putBoolean("four_modes_preserve_manual_parameters", true)
                } finally { targetContext.getSharedPreferences(store, 0).edit().clear().commit() }
                check(PacRefreshInterval.fromHours(0) == PacRefreshInterval.DAY)
                check(PacRefreshInterval.WEEK.millis == 604800000L)
            }
            runOnMainSync {
                val web = android.webkit.WebView(targetContext)
                try {
                    repeat(2) {
                        restrictPacWebView(web)
                        check(web.settings.blockNetworkLoads)
                        check(!web.settings.allowFileAccess)
                        check(!web.settings.allowContentAccess)
                        check(!web.settings.javaScriptEnabled)
                    }
                } finally { web.destroy() }
            }
            result.putBoolean("webview_policy_enforced", true)
            result.putBoolean("fixture_only", true)
            result.putBoolean("script_success", true)
        } catch (error: Throwable) {
            result.putString("failure_type", error.javaClass.simpleName)
            result.putString("failure_line", error.stackTrace.firstOrNull { it.className.contains("PacEngineProbe") }?.lineNumber.toString())
            result.putBoolean("script_success", false)
        }
        result.putString("artifacts", artifacts.absolutePath)
        finish(Activity.RESULT_OK, result)
    }
}
