package ru.seasonvar.tv.data.images

import org.junit.Assert.*
import org.junit.Test

class PosterBlurTest {
    @Test fun uniform_image_and_alpha_are_preserved() {
        val pixels = IntArray(20) { 0xff284860.toInt() }
        assertArrayEquals(pixels, blurPosterPixels(pixels, 4, 5, 8, 3))
        assertArrayEquals(intArrayOf(0x80505050.toInt()), blurPosterPixels(intArrayOf(0x80505050.toInt()), 1, 1, 8, 3))
    }

    @Test fun bright_detail_spreads_without_mutating_the_source() {
        val input = intArrayOf(0xff000000.toInt(), 0xffffffff.toInt(), 0xff000000.toInt())
        val original = input.copyOf()
        val result = blurPosterPixels(input, 3, 1, 1, 1)
        assertArrayEquals(IntArray(3) { 0xff555555.toInt() }, result)
        assertArrayEquals(original, input)
    }

    @Test fun horizontal_and_vertical_passes_are_symmetric() {
        val input = IntArray(9) { if (it == 4) 0xffffffff.toInt() else 0xff000000.toInt() }
        val result = blurPosterPixels(input, 3, 3, 1, 1)
        assertArrayEquals(IntArray(9) { 0xff1c1c1c.toInt() }, result)
    }

    @Test fun mismatched_image_dimensions_are_rejected() {
        assertThrows(IllegalArgumentException::class.java) { blurPosterPixels(IntArray(3), 2, 2, 1, 1) }
    }
}
