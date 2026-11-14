package ru.seasonvar.tv.data.images

internal fun blurPosterPixels(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int): IntArray {
    require(width > 0 && height > 0 && pixels.size == width * height && radius > 0 && passes > 0)
    val result = pixels.copyOf()
    val temporary = IntArray(pixels.size)
    fun pass(source: IntArray, target: IntArray, horizontal: Boolean) {
        val length = if (horizontal) width else height
        val lines = if (horizontal) height else width
        val step = if (horizontal) 1 else width
        val window = radius * 2 + 1
        for (line in 0 until lines) {
            val origin = if (horizontal) line * width else line
            var a = 0; var r = 0; var g = 0; var b = 0
            fun add(pixel: Int, sign: Int) {
                a += (pixel ushr 24) * sign
                r += (pixel ushr 16 and 255) * sign
                g += (pixel ushr 8 and 255) * sign
                b += (pixel and 255) * sign
            }
            for (offset in -radius..radius) add(source[origin + offset.coerceIn(0, length - 1) * step], 1)
            for (position in 0 until length) {
                target[origin + position * step] = (a / window shl 24) or (r / window shl 16) or
                    (g / window shl 8) or (b / window)
                add(source[origin + (position - radius).coerceIn(0, length - 1) * step], -1)
                add(source[origin + (position + radius + 1).coerceIn(0, length - 1) * step], 1)
            }
        }
    }
    repeat(passes) { pass(result, temporary, true); pass(temporary, result, false) }
    return result
}
