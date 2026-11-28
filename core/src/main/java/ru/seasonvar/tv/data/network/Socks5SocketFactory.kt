package ru.seasonvar.tv.data.network

import okhttp3.Dns
import java.io.DataInputStream
import java.io.IOException
import java.io.FilterInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

internal class Socks5SocketFactory(private val proxy: ProxyConfiguration.Server, private val routes: Dns) : SocketFactory() {
    override fun createSocket(): Socket = Socks5Socket(proxy)
    private fun connected(host: String, port: Int, local: InetAddress? = null, localPort: Int = 0): Socket = createSocket().apply {
        try {
            if (local != null) bind(InetSocketAddress(local, localPort))
            connect(InetSocketAddress(routes.lookup(host).first(), port), 8000)
        } catch (error: IOException) { close(); throw error }
    }
    override fun createSocket(host: String, port: Int): Socket = connected(host, port)
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = connected(host, port, local, localPort)
    override fun createSocket(host: InetAddress, port: Int): Socket = connected(checkNotNull(host.hostAddress), port)
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = connected(checkNotNull(host.hostAddress), port, local, localPort)
}

// IP маршрута принадлежит прокси; сохранённое имя адреса обозначает целевой сервер для SOCKS CONNECT.
internal fun socks5Routes(proxy: ProxyConfiguration.Server, dns: Dns): Dns = Dns { target ->
    val endpoint = proxy.address()
    val addresses = endpoint.address?.let { listOf(it) } ?: dns.lookup(proxy.host)
    addresses.map { InetAddress.getByAddress(target, it.address) }
}

private class Socks5Socket(private val proxy: ProxyConfiguration.Server) : Socket(Proxy.NO_PROXY) {
    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val target = endpoint as? InetSocketAddress ?: throw SocketException("Invalid SOCKS target")
        val address = target.address ?: throw SocketException("Unresolved SOCKS route")
        val readTimeout = soTimeout
        try {
            super.connect(InetSocketAddress(address, proxy.port), timeout)
            negotiate(target.hostString, target.port, if (timeout > 0) timeout else 8000)
            soTimeout = readTimeout
        } catch (error: IOException) { close(); throw error }
    }

    private fun negotiate(host: String, port: Int, timeout: Int) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout.toLong())
        val input = DataInputStream(object : FilterInputStream(getInputStream()) {
            private fun prepare() {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw SocketTimeoutException("SOCKS handshake timed out")
                soTimeout = TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1).toInt()
            }
            override fun read(): Int { prepare(); return super.read() }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                prepare(); return `in`.read(bytes, offset, length)
            }
        })
        val output = getOutputStream()
        val method = if (proxy.authenticated) 2 else 0
        output.write(byteArrayOf(5, 1, method.toByte()))
        output.flush()
        if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != method) throw SocketException("SOCKS authentication method rejected")
        if (method == 2) {
            val username = proxy.username.toByteArray(Charsets.UTF_8)
            val password = proxy.password.toByteArray(Charsets.UTF_8)
            output.write(byteArrayOf(1, username.size.toByte()) + username + byteArrayOf(password.size.toByte()) + password)
            output.flush()
            if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0) throw SocketException("SOCKS authentication rejected")
        }
        val address = when {
            ':' in host -> byteArrayOf(4) + InetAddress.getByName(host).address
            host.all { it.isDigit() || it == '.' } -> byteArrayOf(1) + InetAddress.getByName(host).address
            else -> {
                val name = host.toByteArray(Charsets.US_ASCII)
                if (name.size !in 1..255) throw SocketException("Invalid SOCKS hostname length")
                byteArrayOf(3, name.size.toByte()) + name
            }
        }
        output.write(byteArrayOf(5, 1, 0) + address + byteArrayOf((port ushr 8).toByte(), port.toByte()))
        output.flush()
        if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 0 || input.readUnsignedByte() != 0) throw SocketException("SOCKS connection rejected")
        val length = when (input.readUnsignedByte()) {
            1 -> 4
            4 -> 16
            3 -> input.readUnsignedByte().also { if (it == 0) throw SocketException("Invalid SOCKS reply") }
            else -> throw SocketException("Invalid SOCKS reply")
        }
        input.readFully(ByteArray(length + 2))
    }
}
