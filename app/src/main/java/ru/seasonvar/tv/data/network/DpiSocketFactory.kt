package ru.seasonvar.tv.data.network

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import javax.net.ssl.*

class DpiSocketFactory(private val context: SSLContext) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = context.defaultSSLParameters.cipherSuites
    override fun getSupportedCipherSuites(): Array<String> = context.supportedSSLParameters.cipherSuites

    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        EngineSocket(socket, context.createSSLEngine(host, port), host, autoClose)

    override fun createSocket(host: String, port: Int): Socket =
        createSocket(Socket(host, port), host, port, true)

    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
        createSocket(Socket(host, port, local, localPort), host, port, true)

    override fun createSocket(host: InetAddress, port: Int): Socket = createSocket(host.hostName, port)

    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
        createSocket(host.hostName, port, local, localPort)
}

private class EngineSocket(
    private val wire: Socket,
    private val engine: SSLEngine,
    private val host: String,
    private val autoClose: Boolean,
) : SSLSocket() {
    private val encrypted = ByteBuffer.allocate(65536)
    private val decrypted = ByteBuffer.allocate(65536).apply { limit(0) }
    private val outgoing = ByteBuffer.allocate(65536)
    private val empty = ByteBuffer.allocate(0)
    private val listeners = mutableSetOf<HandshakeCompletedListener>()
    private var helloSent = false
    private var ready = false
    private var peerClosed = false
    @Volatile private var closed = false

    init {
        wire.tcpNoDelay = true
        engine.useClientMode = true
        engine.sslParameters = engine.sslParameters.apply {
            endpointIdentificationAlgorithm = "HTTPS"
            serverNames = listOf(SNIHostName(host))
        }
    }

    private fun tasks() {
        while (true) (engine.delegatedTask ?: break).run()
    }

    private fun wrap(source: ByteBuffer): SSLEngineResult {
        outgoing.clear()
        val result = engine.wrap(source, outgoing)
        if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) throw SSLException("TLS output overflow")
        outgoing.flip()
        if (outgoing.hasRemaining()) {
            var bytes = ByteArray(outgoing.remaining()).also { outgoing.get(it) }
            val output = wire.getOutputStream()
            if (!helloSent) {
                helloSent = true
                bytes = ClientHelloStrategy.fragment(bytes, host)
                output.write(bytes, 0, 1)
                wire.sendUrgentData('a'.code)
                Thread.sleep(10)
                output.write(bytes, 1, bytes.size - 1)
            } else {
                output.write(bytes)
            }
        }
        return result
    }

    private fun receive() {
        if (!encrypted.hasRemaining()) throw SSLException("TLS input overflow")
        val count = wire.getInputStream().read(
            encrypted.array(), encrypted.position(), encrypted.remaining()
        )
        if (count < 0) throw EOFException("TLS peer closed connection")
        encrypted.position(encrypted.position() + count)
    }

    private fun unwrap(): SSLEngineResult {
        check(!decrypted.hasRemaining())
        while (true) {
            encrypted.flip()
            decrypted.clear()
            val result = engine.unwrap(encrypted, decrypted)
            encrypted.compact()
            decrypted.flip()
            when (result.status) {
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> receive()
                SSLEngineResult.Status.BUFFER_OVERFLOW -> throw SSLException("TLS plaintext overflow")
                SSLEngineResult.Status.CLOSED -> {
                    peerClosed = true
                    return result
                }
                else -> return result
            }
        }
    }

    @Synchronized
    override fun startHandshake() {
        if (ready) return
        engine.beginHandshake()
        while (true) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrap(empty)
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    val result = unwrap()
                    if (result.status == SSLEngineResult.Status.CLOSED) throw SSLHandshakeException("TLS handshake closed")
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> tasks()
                SSLEngineResult.HandshakeStatus.FINISHED,
                SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> break
                else -> throw SSLException("Unsupported handshake state")
            }
        }
        ready = true
        val event = HandshakeCompletedEvent(this, engine.session)
        listeners.forEach { it.handshakeCompleted(event) }
    }

    private fun postHandshake() {
        while (true) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> tasks()
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrap(empty)
                else -> return
            }
        }
    }

    private val input = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = synchronized(this@EngineSocket) {
            if (length == 0) return@synchronized 0
            startHandshake()
            while (!decrypted.hasRemaining()) {
                if (peerClosed) return@synchronized -1
                unwrap()
                postHandshake()
            }
            val count = minOf(length, decrypted.remaining())
            decrypted.get(bytes, offset, count)
            count
        }

        override fun close() = this@EngineSocket.close()
    }

    private val output = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            synchronized(this@EngineSocket) {
                startHandshake()
                val source = ByteBuffer.wrap(bytes, offset, length)
                while (source.hasRemaining()) {
                    val result = wrap(source)
                    postHandshake()
                    if (result.bytesConsumed() == 0 && result.bytesProduced() == 0) {
                        throw SSLException("TLS write made no progress")
                    }
                }
            }
        }

        override fun close() = this@EngineSocket.close()
    }

    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = output
    override fun getSession(): SSLSession { startHandshake(); return engine.session }
    override fun getHandshakeSession(): SSLSession? = engine.handshakeSession
    override fun getSupportedCipherSuites(): Array<String> = engine.supportedCipherSuites
    override fun getEnabledCipherSuites(): Array<String> = engine.enabledCipherSuites
    override fun setEnabledCipherSuites(value: Array<String>) { engine.enabledCipherSuites = value }
    override fun getSupportedProtocols(): Array<String> = engine.supportedProtocols
    override fun getEnabledProtocols(): Array<String> = engine.enabledProtocols
    override fun setEnabledProtocols(value: Array<String>) { engine.enabledProtocols = value }
    override fun getSSLParameters(): SSLParameters = engine.sslParameters
    override fun setSSLParameters(value: SSLParameters) {
        value.endpointIdentificationAlgorithm = "HTTPS"
        engine.sslParameters = value
    }
    override fun getUseClientMode(): Boolean = engine.useClientMode
    override fun setUseClientMode(value: Boolean) { require(value); engine.useClientMode = value }
    override fun getNeedClientAuth(): Boolean = engine.needClientAuth
    override fun setNeedClientAuth(value: Boolean) { engine.needClientAuth = value }
    override fun getWantClientAuth(): Boolean = engine.wantClientAuth
    override fun setWantClientAuth(value: Boolean) { engine.wantClientAuth = value }
    override fun getEnableSessionCreation(): Boolean = engine.enableSessionCreation
    override fun setEnableSessionCreation(value: Boolean) { engine.enableSessionCreation = value }
    override fun addHandshakeCompletedListener(value: HandshakeCompletedListener) { listeners.add(value) }
    override fun removeHandshakeCompletedListener(value: HandshakeCompletedListener) { require(listeners.remove(value)) }
    override fun getSoTimeout(): Int = wire.soTimeout
    override fun setSoTimeout(value: Int) { wire.soTimeout = value }
    override fun isConnected(): Boolean = wire.isConnected
    override fun isClosed(): Boolean = closed || wire.isClosed
    override fun isInputShutdown(): Boolean = wire.isInputShutdown
    override fun isOutputShutdown(): Boolean = wire.isOutputShutdown
    override fun getInetAddress(): InetAddress = wire.inetAddress
    override fun getLocalAddress(): InetAddress = wire.localAddress
    override fun getPort(): Int = wire.port
    override fun getLocalPort(): Int = wire.localPort
    override fun close() {
        closed = true
        if (autoClose) wire.close()
        super.close()
    }
}
