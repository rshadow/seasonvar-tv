package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.StrictMode
import okhttp3.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import ru.seasonvar.tv.data.network.*
import ru.seasonvar.tv.data.network.pac.*
import java.io.IOException
import java.net.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket

object PacCancellationProbe {
    fun run(probe: Instrumentation) = with(probe) {
        val result = Bundle()
        var phase = "start"
        try {
            check(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.FINGERPRINT.contains("generic"))
            val certificate = HeldCertificate.Builder().commonName("Cancellation fixture")
                .addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("origin.invalid")
                .validityInterval(946684800000L, 4102444800000L).build()
            val tls = HandshakeCertificates.Builder().heldCertificate(certificate)
                .addTrustedCertificate(certificate.certificate).build()
            for (bypass in listOf(false, true)) for (stage in listOf("connect", "headers", "body")) {
                phase = stage + if (bypass) "_bypass" else "_standard"
                ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { listener ->
                    listener.soTimeout = 5000
                    val ready = CountDownLatch(1)
                    val finished = CountDownLatch(1)
                    val pool = Executors.newSingleThreadExecutor()
                    val server = pool.submit {
                        listener.accept().use { raw ->
                            raw.soTimeout = 5000
                            (tls.sslSocketFactory().createSocket(raw, "127.0.0.1", listener.localPort, true) as SSLSocket).use { outer ->
                                outer.useClientMode = false
                                outer.startHandshake()
                                headers(outer)
                                fun awaitCancellation(socket: Socket) {
                                    ready.countDown()
                                    try { check(socket.getInputStream().read() == -1) }
                                    catch (_: javax.net.ssl.SSLException) { }
                                    catch (_: SocketException) { }
                                }
                                if (stage == "connect") awaitCancellation(outer)
                                else {
                                    outer.getOutputStream().apply {
                                        write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush()
                                    }
                                    (tls.sslSocketFactory().createSocket(outer, "origin.invalid", 443, true) as SSLSocket).use { inner ->
                                        inner.useClientMode = false
                                        inner.startHandshake()
                                        headers(inner)
                                        if (stage == "body") inner.getOutputStream().apply {
                                            write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush()
                                        }
                                        awaitCancellation(inner)
                                    }
                                }
                            }
                        }
                    }
                    val configuration = NetworkConfiguration(bypass, DnsProvider.SYSTEM, ProxyConfiguration.AntiZapret)
                    val provider = object : PacRouteProvider {
                        override val revision = 0L
                        override fun routes(host: String, configuration: NetworkConfiguration) =
                            listOf(PacRoute(PacRouteType.HTTPS, "127.0.0.1", listener.localPort))
                        override suspend fun prepare(configuration: NetworkConfiguration) = Unit
                    }
                    val client = OkHttpClient.Builder().sslSocketFactory(
                        if (bypass) DpiSocketFactory(tls.sslContext()) else tls.sslSocketFactory(), tls.trustManager)
                        .pacTransport(provider, configuration, Dns { error("Unexpected DNS") }, tls.sslSocketFactory(), CertificatePinner.DEFAULT)
                        .callTimeout(10, TimeUnit.SECONDS).build()
                    try {
                        val call = client.newCall(Request.Builder().url("https://origin.invalid/cancel").build())
                        val responseStarted = CountDownLatch(1)
                        call.enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) { finished.countDown() }
                            override fun onResponse(call: Call, response: Response) {
                                responseStarted.countDown()
                                try { response.use { it.body.byteStream().read() } }
                                catch (_: IOException) { }
                                finally { finished.countDown() }
                            }
                        })
                        check(ready.await(5, TimeUnit.SECONDS))
                        if (stage == "body") check(responseStarted.await(5, TimeUnit.SECONDS))
                        val failure = AtomicReference<Throwable>()
                        runOnMainSync {
                            val previous = StrictMode.getThreadPolicy()
                            try {
                                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(previous).detectNetwork().penaltyDeathOnNetwork().build())
                                call.cancel()
                                call.cancel()
                                client.connectionPool.evictAll()
                                client.socketFactory.createSocket().close()
                            } catch (error: Throwable) { failure.set(error) }
                            finally { StrictMode.setThreadPolicy(previous) }
                        }
                        failure.get()?.let { throw it }
                        check(finished.await(5, TimeUnit.SECONDS))
                        server.get(5, TimeUnit.SECONDS)
                    } finally {
                        client.dispatcher.cancelAll()
                        client.connectionPool.evictAll()
                        pool.shutdownNow()
                    }
                }
            }
            result.putBoolean("main_thread_cancellation", true)
            result.putBoolean("fixture_only", true)
            result.putBoolean("script_success", true)
        } catch (error: Throwable) {
            result.putString("failure_type", error.javaClass.simpleName)
            result.putString("failure_phase", phase)
            result.putBoolean("script_success", false)
        }
        finish(Activity.RESULT_OK, result)
    }

    private fun headers(socket: Socket) {
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        while (!input.readLine().isNullOrEmpty()) { }
    }
}
