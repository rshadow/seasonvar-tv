package ru.seasonvar.tv.data.network.pac

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import ru.seasonvar.tv.data.network.NetworkConfiguration
import java.io.File
import java.io.IOException

enum class PacFailure { DOWNLOAD, FORMAT, WEBVIEW, TIMEOUT, STORAGE }
class PacException(val failure: PacFailure) : IOException("PAC " + failure.name)
enum class PacRouteType { DIRECT, HTTP, HTTPS }
data class PacRoute(val type: PacRouteType, val host: String = "", val port: Int = 0) {
    companion object {
        fun parse(value: String): List<PacRoute> {
            if (value.length > 4096) throw PacException(PacFailure.FORMAT)
            val routes = value.split(';').mapNotNull { item ->
                val text = item.trim()
                if (text.isEmpty()) null
                else if (text == "DIRECT") PacRoute(PacRouteType.DIRECT)
                else {
                    val parts = text.split(Regex("\\s+"))
                    if (parts.size != 2) throw PacException(PacFailure.FORMAT)
                    val type = when (parts[0]) {
                        "PROXY", "HTTP" -> PacRouteType.HTTP
                        "HTTPS" -> PacRouteType.HTTPS
                        else -> throw PacException(PacFailure.FORMAT)
                    }
                    val url = ("http://" + parts[1]).toHttpUrlOrNull() ?: throw PacException(PacFailure.FORMAT)
                    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.encodedPath != "/" ||
                        url.query != null || url.fragment != null || !parts[1].contains(':')) throw PacException(PacFailure.FORMAT)
                    PacRoute(type, url.host, url.port)
                }
            }
            if (routes.isEmpty() || routes.size > 16) throw PacException(PacFailure.FORMAT)
            return routes
        }
    }
}

data class PacQuery(val host: String, val ipv4: String?)

interface PacEvaluator {
    suspend fun evaluate(file: File, queries: List<PacQuery>): Map<String, List<PacRoute>>
}

interface PacRouteProvider {
    val revision: Long
    fun routes(host: String, configuration: NetworkConfiguration): List<PacRoute>
    suspend fun prepare(configuration: NetworkConfiguration)
}
