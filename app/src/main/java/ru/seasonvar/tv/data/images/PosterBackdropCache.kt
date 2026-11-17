package ru.seasonvar.tv.data.images

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.util.LruCache
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal data class PosterBackdropSpec(val width: Int, val height: Int, val background: Int,
    val dim: Float, val edge: Float, val fadeEdge: Float, val menuEnd: Float = 0f, val blurRadius: Int = 0,
    val imageScale: Float = 1f)

internal class PosterBackdropCache {
    private data class Key(val poster: String, val spec: PosterBackdropSpec)
    private val images = LruCache<Key, Bitmap>(MAX_ENTRIES)
    private val preparation = Mutex()

    fun get(poster: String, spec: PosterBackdropSpec): Bitmap? = images.get(Key(poster, spec))
    val cachedBytes: Int get() = images.snapshot().values.sumOf { it.allocationByteCount }

    suspend fun prepareResource(context: Context, resource: Int, spec: PosterBackdropSpec): Bitmap = withContext(ru.seasonvar.tv.data.work.BackgroundCpu.dispatcher) {
        val key = resourceKey(context, resource)
        get(key, spec)?.let { return@withContext it }
        val drawable = checkNotNull(context.getDrawable(resource))
        val scale = MAX_EDGE.toFloat() / maxOf(drawable.intrinsicWidth, drawable.intrinsicHeight, 1)
        val image = Bitmap.createBitmap((drawable.intrinsicWidth * scale).roundToInt().coerceAtLeast(1),
            (drawable.intrinsicHeight * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(0, 0, image.width, image.height)
            drawable.draw(Canvas(image))
            prepare(key, image, spec)
        } finally { image.recycle() }
    }

    suspend fun prepare(poster: String, input: Bitmap, spec: PosterBackdropSpec): Bitmap = withContext(ru.seasonvar.tv.data.work.BackgroundCpu.dispatcher) {
        require(spec.width > 0 && spec.height > 0 && spec.dim in 0f..1f && spec.edge in 0f..1f && spec.fadeEdge in 0f..0.5f && spec.menuEnd in 0f..0.6f && spec.blurRadius >= 0)
        require(spec.imageScale > 0f && spec.imageScale <= 1f)
        preparation.withLock {
            val key = Key(poster, spec)
            images.get(key)?.let { return@withLock it }
            ensureActive()
            val source = if (input.config == Bitmap.Config.HARDWARE) checkNotNull(input.copy(Bitmap.Config.ARGB_8888, false)) else input
            val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(source.width, source.height))
            val width = (source.width * scale).roundToInt().coerceAtLeast(1)
            val height = (source.height * scale).roundToInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(source, width, height, true)
            val pixels = IntArray(width * height)
            small.getPixels(pixels, 0, width, 0, 0, width, height)
            val blurred = if (spec.blurRadius == 0) pixels else blurPosterPixels(pixels, width, height, spec.blurRadius, PASSES)
            val blurredBitmap = Bitmap.createBitmap(blurred, width, height, Bitmap.Config.ARGB_8888)
            val outputScale = minOf(1f, MAX_EDGE.toFloat() / maxOf(spec.width, spec.height))
            val outputWidth = (spec.width * outputScale).roundToInt().coerceAtLeast(1)
            val outputHeight = (spec.height * outputScale).roundToInt().coerceAtLeast(1)
            val result = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            val fit = maxOf(outputWidth.toFloat() / width, outputHeight.toFloat() / height)
            val cropWidth = (outputWidth / fit).roundToInt().coerceIn(1, width)
            val cropHeight = (outputHeight / fit).roundToInt().coerceIn(1, height)
            val left = (width - cropWidth) / 2
            val top = (height - cropHeight) / 2
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            canvas.drawColor(spec.background)
            if (spec.imageScale == 1f) {
                canvas.drawBitmap(blurredBitmap, Rect(left, top, left + cropWidth, top + cropHeight), Rect(0, 0, outputWidth, outputHeight), paint)
            } else {
                val drawWidth = (width * fit * spec.imageScale).roundToInt().coerceAtLeast(1)
                val drawHeight = (height * fit * spec.imageScale).roundToInt().coerceAtLeast(1)
                val x = (outputWidth - drawWidth) / 2
                val y = (outputHeight - drawHeight) / 2
                canvas.drawBitmap(blurredBitmap, null, Rect(x, y, x + drawWidth, y + drawHeight), paint)
            }
            fun alpha(value: Float) = (spec.background and 0x00ffffff) or ((value * 255).roundToInt() shl 24)
            canvas.drawColor(alpha(spec.dim))
            val positions = floatArrayOf(0f, spec.fadeEdge, 1f - spec.fadeEdge, 1f)
            val horizontalPositions = floatArrayOf(0f, minOf(1f - spec.fadeEdge, spec.menuEnd + spec.fadeEdge), 1f - spec.fadeEdge, 1f)
            paint.shader = LinearGradient(0f, 0f, outputWidth.toFloat(), 0f,
                intArrayOf(spec.background, alpha(spec.edge), alpha(0f), spec.background), horizontalPositions, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, outputWidth.toFloat(), outputHeight.toFloat(), paint)
            paint.shader = LinearGradient(0f, 0f, 0f, outputHeight.toFloat(),
                intArrayOf(spec.background, alpha(0f), alpha(0f), spec.background), positions, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, outputWidth.toFloat(), outputHeight.toFloat(), paint)
            ensureActive()
            images.put(key, result)
            result
        }
    }

    companion object {
        fun resourceKey(context: Context, resource: Int) = "android.resource://${context.packageName}/$resource"
        const val MAX_EDGE = 256
        const val MAX_ENTRIES = 8
        private const val PASSES = 2
    }
}
