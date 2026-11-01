package ru.seasonvar.tv.data.network

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext

class ClientHelloStrategyTest {
    private fun hello(): ByteArray {
        val engine = SSLContext.getDefault().createSSLEngine("seasonvar.ru", 443)
        engine.useClientMode = true
        engine.sslParameters = engine.sslParameters.apply {
            serverNames = listOf(SNIHostName("seasonvar.ru"))
        }
        engine.beginHandshake()
        val output = ByteBuffer.allocate(65536)
        engine.wrap(ByteBuffer.allocate(0), output)
        output.flip()
        return ByteArray(output.remaining()).also { output.get(it) }
    }

    private fun records(data: ByteArray): List<ByteArray> {
        val records = mutableListOf<ByteArray>()
        var pos = 0
        while (pos < data.size) {
            val size = ((data[pos + 3].toInt() and 255) shl 8) or
                (data[pos + 4].toInt() and 255)
            records.add(data.copyOfRange(pos + 5, pos + 5 + size))
            pos += 5 + size
        }
        assertEquals(data.size, pos)
        return records
    }

    @Test fun handshake_payload_and_following_records_are_preserved() {
        val original = hello()
        val tail = byteArrayOf(23, 3, 3, 0, 3, 97, 98, 99)
        val before = records(original + tail)
        val after = records(ClientHelloStrategy.fragment(original + tail, "seasonvar.ru"))
        assertEquals(before.size + 1, after.size)
        assertArrayEquals(before.first(), after[0] + after[1])
        before.drop(1).zip(after.drop(2)).forEach { (a, b) -> assertArrayEquals(a, b) }
    }

    @Test fun split_is_five_bytes_before_end_of_sni() {
        val changed = records(ClientHelloStrategy.fragment(hello(), "seasonvar.ru"))
        assertEquals("ar.ru", changed[1].copyOfRange(0, 5).toString(Charsets.US_ASCII))
        assertTrue(changed[0].toString(Charsets.ISO_8859_1).endsWith("seasonv"))
    }

    @Test fun hostname_in_random_field_does_not_change_the_split() {
        val original = hello()
        val altered = original.copyOf()
        "seasonvar.ru".toByteArray().copyInto(altered, 11)
        val before = records(ClientHelloStrategy.fragment(original, "seasonvar.ru"))
        val after = records(ClientHelloStrategy.fragment(altered, "seasonvar.ru"))
        assertEquals(before[0].size, after[0].size)
    }

    @Test fun wrong_sni_and_every_truncated_record_are_rejected() {
        val original = hello()
        assertThrows(IOException::class.java) {
            ClientHelloStrategy.fragment(original, "example.org")
        }
        for (size in 0 until original.size) {
            assertThrows("truncated at " + size, IOException::class.java) {
                ClientHelloStrategy.fragment(original.copyOf(size), "seasonvar.ru")
            }
        }
    }
}
