package ru.seasonvar.tv.data.network

import okhttp3.CertificatePinner
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.settings.TlsPinSettings
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

class TlsPinningTest {
    @Test fun configured_primary_and_backup_keys_match_public_certificates() {
        val groups = listOf(
            listOf("seasonvar.ru", "www.seasonvar.ru", "cdn.svstat.net", "svstat.net",
                "objects.githubusercontent.com", "release-assets.githubusercontent.com") to
                listOf("isrg-root-yr", "isrg-root-ye"),
            listOf("github.com", "api.github.com") to listOf("sectigo-root-e46", "sectigo-root-r46"),
            listOf("dns.quad9.net") to listOf("digicert-global-root-g3", "digicert-global-root-g2"),
            listOf("cloudflare-dns.com") to listOf("sslcom-root-ecc", "sslcom-root-rsa"),
        )
        for ((hosts, certificates) in groups) {
            for (host in hosts) for (name in certificates) {
                TlsPinSettings.certificatePinner.check(host, listOf(certificate(name)))
            }
            for (host in hosts) {
                val other = if (host == "github.com" || host == "api.github.com") "isrg-root-yr" else "sectigo-root-e46"
                assertThrows(SSLPeerUnverifiedException::class.java) {
                    TlsPinSettings.certificatePinner.check(host, listOf(certificate(other)))
                }
            }
        }
    }

    @Test fun production_client_rejects_a_trusted_certificate_with_correct_hostname_but_wrong_pin() {
        withTlsServer { client, url, certificate ->
            assertThrows(SSLPeerUnverifiedException::class.java) {
                client.newCall(Request.Builder().url(url).build()).execute().close()
            }
            assertNotEquals(TlsPinSettings.ISRG_ROOT_YR, CertificatePinner.pin(certificate))
        }
    }

    @Test fun matching_pin_allows_valid_tls_and_does_not_bypass_hostname_verification() {
        withTlsServer { client, url, certificate ->
            val pinned = client.newBuilder().certificatePinner(CertificatePinner.Builder()
                .add("seasonvar.ru", CertificatePinner.pin(certificate))
                .add("github.com", CertificatePinner.pin(certificate)).build()).build()
            pinned.newCall(Request.Builder().url(url).build()).execute().use {
                assertEquals(200, it.code)
                assertEquals("ok", it.body.string())
            }
            assertThrows(SSLPeerUnverifiedException::class.java) {
                pinned.newCall(Request.Builder().url(url.replace("seasonvar.ru", "github.com")).build())
                    .execute().close()
            }
        }
    }

    @Test fun matching_pin_does_not_make_an_untrusted_certificate_trusted() {
        withTlsServer { client, url, certificate ->
            val untrusted = NetworkClients().forConfiguration(NetworkConfiguration(false, DnsProvider.SYSTEM))
                .newBuilder().dns(client.dns).callTimeout(5, TimeUnit.SECONDS)
                .certificatePinner(CertificatePinner.Builder()
                    .add("seasonvar.ru", CertificatePinner.pin(certificate)).build()).build()
            try {
                assertThrows(SSLHandshakeException::class.java) {
                    untrusted.newCall(Request.Builder().url(url).build()).execute().close()
                }
            } finally {
                untrusted.connectionPool.evictAll()
                untrusted.dispatcher.executorService.shutdown()
            }
        }
    }

    private fun certificate(name: String): X509Certificate =
        requireNotNull(javaClass.getResourceAsStream("/tls/$name.pem")).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    private fun withTlsServer(block: (OkHttpClient, String, X509Certificate) -> Unit) {
        val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(1L) }
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256, random) }.generateKeyPair()
        val held = HeldCertificate.Builder().keyPair(keys).commonName("seasonvar.ru")
            .addSubjectAlternativeName("seasonvar.ru").serialNumber(1)
            .validityInterval(1_577_836_800_000L, 4_102_444_800_000L).build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(held).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = serverTls.sslContext().serverSocketFactory.createServerSocket(0, 8, loopback) as SSLServerSocket
        val executor = Executors.newSingleThreadExecutor()
        val worker = executor.submit {
            while (!server.isClosed) {
                try {
                    (server.accept() as SSLSocket).use { socket ->
                        socket.soTimeout = 3_000
                        socket.startHandshake()
                        val input = socket.inputStream.bufferedReader(Charsets.US_ASCII)
                        while (true) { if (input.readLine().isNullOrEmpty()) break }
                        socket.outputStream.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                            .toByteArray(Charsets.US_ASCII))
                    }
                } catch (_: java.io.IOException) {
                    if (server.isClosed) break
                }
            }
        }
        val client = NetworkClients().forConfiguration(NetworkConfiguration(false, DnsProvider.SYSTEM))
            .newBuilder().dns(Dns { listOf(loopback) })
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .callTimeout(5, TimeUnit.SECONDS).build()
        try {
            block(client, "https://seasonvar.ru:${server.localPort}/", held.certificate)
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.close()
            try { worker.get(5, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
        }
    }
}
