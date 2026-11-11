package ru.seasonvar.tv.data.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress

enum class ProxyProtocol(val storageId: String) {
    HTTP_CONNECT("http_connect"), SOCKS5("socks5")
}

sealed interface ProxyConfiguration {
    data object Disabled : ProxyConfiguration
    data object Unavailable : ProxyConfiguration
    data object AntiZapret : ProxyConfiguration
    data class Pac(val url: String) : ProxyConfiguration {
        init {
            val parsed = url.toHttpUrlOrNull()
            require(parsed != null && parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.fragment == null)
        }
        override fun toString(): String = "ProxyConfiguration.Pac(url=redacted)"
    }

    data class Server(val host: String, val port: Int, val username: String = "", val password: String = "",
        val protocol: ProxyProtocol = ProxyProtocol.HTTP_CONNECT) : ProxyConfiguration {
        init {
            require(port in 1..65535)
            require(host.isNotBlank() && host == canonicalHost(host))
            require(username.none { it.isISOControl() } && password.none { it.isISOControl() })
            if (protocol == ProxyProtocol.HTTP_CONNECT) require(':' !in username)
            if (protocol == ProxyProtocol.SOCKS5 && username.isNotEmpty()) {
                require(username.toByteArray(Charsets.UTF_8).size in 1..255)
                require(password.toByteArray(Charsets.UTF_8).size in 1..255)
            }
            require(username.isNotEmpty() || password.isEmpty())
        }
        val authenticated: Boolean get() = username.isNotEmpty()
        fun address(): InetSocketAddress = if (':' in host || host.all { it.isDigit() || it == '.' })
            InetSocketAddress(InetAddress.getByName(host), port) else InetSocketAddress.createUnresolved(host, port)
        override fun toString(): String = "ProxyConfiguration.Server(credentials=redacted)"
    }
}

internal fun canonicalHost(value: String): String = HttpUrl.Builder().scheme("http").host(value.trim()).build().host

internal object ProxyCodec {
    fun encode(value: ProxyConfiguration.Server): String = JSONObject().put("version", 1)
        .put("type", value.protocol.storageId).put("host", value.host).put("port", value.port)
        .put("username", value.username).put("password", value.password).toString()

    fun decode(value: String): ProxyConfiguration.Server {
        val data = JSONObject(value)
        require(data.getInt("version") == 1)
        val protocol = requireNotNull(ProxyProtocol.entries.firstOrNull { it.storageId == data.getString("type") })
        return ProxyConfiguration.Server(canonicalHost(data.getString("host")), data.getInt("port"),
            data.getString("username"), data.getString("password"), protocol)
    }
}
