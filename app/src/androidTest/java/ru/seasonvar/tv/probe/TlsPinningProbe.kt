package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import okhttp3.CertificatePinner
import okhttp3.Request
import ru.seasonvar.tv.data.network.DnsProvider
import ru.seasonvar.tv.data.network.NetworkClients
import ru.seasonvar.tv.data.network.NetworkConfiguration
import ru.seasonvar.tv.data.settings.TlsPinSettings
import javax.net.ssl.SSLPeerUnverifiedException

object TlsPinningProbe {
    fun run(probe: Instrumentation) {
        val result = Bundle()
        var phase = "start"
        try {
            val clients = NetworkClients()
            val urls = listOf("https://seasonvar.ru/", "https://www.seasonvar.ru/",
                "https://cdn.svstat.net/", "https://github.com/rshadow/seasonvar-tv",
                "https://api.github.com/repos/rshadow/seasonvar-tv/releases/latest",
                "https://objects.githubusercontent.com/", "https://release-assets.githubusercontent.com/",
                "https://dns.quad9.net/dns-query")
            for (url in urls) {
                val request = Request.Builder().url(url).build()
                val host = request.url.host
                phase = host
                probe.sendStatus(Activity.RESULT_OK, Bundle().apply { putString("phase", phase) })
                val site = host.endsWith("seasonvar.ru") || host.endsWith("svstat.net")
                val client = clients.forConfiguration(NetworkConfiguration(site,
                    if (site) DnsProvider.QUAD9_DOH else DnsProvider.SYSTEM))
                client.newCall(request).execute().use { response ->
                    check(response.handshake != null)
                    if (host == "seasonvar.ru" || host == "api.github.com") check(response.isSuccessful)
                }
                val wrongPin = if (site) TlsPinSettings.SECTIGO_ROOT_E46 else TlsPinSettings.DIGICERT_GLOBAL_ROOT_G2
                val rejected = client.newBuilder().certificatePinner(CertificatePinner.Builder()
                    .add(host, wrongPin).build()).build()
                try {
                    rejected.newCall(request).execute().close()
                    error("Unexpected pin accepted")
                } catch (_: SSLPeerUnverifiedException) {
                    result.putBoolean(host.replace('.', '_') + "_verified", true)
                } finally {
                    rejected.connectionPool.evictAll()
                }
            }
            phase = "release_notes"
            check(kotlinx.coroutines.runBlocking {
                ru.seasonvar.tv.data.update.GitHubUpdateSource().releases(1)
            }.trimStart().startsWith("["))
            result.putBoolean("release_notes_verified", true)
            result.putBoolean("probe_success", true)
            probe.finish(Activity.RESULT_OK, result)
        } catch (error: Exception) {
            result.putBoolean("probe_success", false)
            result.putString("phase", phase)
            result.putString("failure_type", error.javaClass.simpleName)
            probe.finish(Activity.RESULT_CANCELED, result)
        }
    }
}
