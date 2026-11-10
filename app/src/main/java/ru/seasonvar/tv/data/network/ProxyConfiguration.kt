package ru.seasonvar.tv.data.network

import okhttp3.HttpUrl
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress

sealed interface ProxyConfiguration {
    data object Disabled : ProxyConfiguration
    data object Unavailable : ProxyConfiguration

    data class Server(val host: String, val port: Int, val username: String = "", val password: String = "") : ProxyConfiguration {
        init {
            require(port in 1..65535)
            require(host.isNotBlank() && host == canonicalHost(host))
            require(':' !in username && username.none { it.isISOControl() } && password.none { it.isISOControl() })
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
        .put("type", "http_connect").put("host", value.host).put("port", value.port)
        .put("username", value.username).put("password", value.password).toString()

    fun decode(value: String): ProxyConfiguration.Server {
        val data = JSONObject(value)
        require(data.getInt("version") == 1 && data.getString("type") == "http_connect")
        return ProxyConfiguration.Server(canonicalHost(data.getString("host")), data.getInt("port"),
            data.getString("username"), data.getString("password"))
    }
}
