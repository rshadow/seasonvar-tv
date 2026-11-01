package ru.seasonvar.tv.data.network

import java.io.IOException

object ClientHelloStrategy {
    fun fragment(data: ByteArray, host: String): ByteArray {
        fun number(pos: Int): Int {
            if (pos < 0 || pos + 2 > data.size) throw IOException("Truncated ClientHello")
            return ((data[pos].toInt() and 255) shl 8) or (data[pos + 1].toInt() and 255)
        }
        val end = 5 + number(3)
        if (data.size < 44 || data[0].toInt() != 22 || data[5].toInt() != 1 || end > data.size) {
            throw IOException("Expected a complete ClientHello")
        }
        var pos = 43
        pos += 1 + (data[pos].toInt() and 255)
        pos += 2 + number(pos)
        if (pos >= end) throw IOException("Invalid ClientHello")
        pos += 1 + (data[pos].toInt() and 255)
        val extensionsEnd = pos + 2 + number(pos)
        pos += 2
        if (extensionsEnd > end) throw IOException("Invalid extensions")
        while (pos + 4 <= extensionsEnd) {
            val type = number(pos)
            val length = number(pos + 2)
            pos += 4
            if (pos + length > extensionsEnd) throw IOException("Invalid extension")
            if (type == 0) {
                val namesEnd = pos + 2 + number(pos)
                if (namesEnd != pos + length) throw IOException("Invalid SNI")
                var name = pos + 2
                while (name + 3 <= namesEnd) {
                    val size = number(name + 1)
                    val start = name + 3
                    val stop = start + size
                    if (stop > namesEnd) throw IOException("Invalid hostname")
                    if (data[name].toInt() == 0 && data.copyOfRange(start, stop)
                            .contentEquals(host.toByteArray(Charsets.US_ASCII))
                    ) {
                        val cut = stop - 5
                        if (cut <= start) throw IOException("Hostname too short")
                        fun record(from: Int, to: Int): ByteArray {
                            val sizeBytes = byteArrayOf(((to - from) shr 8).toByte(), (to - from).toByte())
                            return data.copyOfRange(0, 3) + sizeBytes + data.copyOfRange(from, to)
                        }
                        return record(5, cut) + record(cut, end) + data.copyOfRange(end, data.size)
                    }
                    name = stop
                }
            }
            pos += length
        }
        throw IOException("Expected SNI not found")
    }
}
