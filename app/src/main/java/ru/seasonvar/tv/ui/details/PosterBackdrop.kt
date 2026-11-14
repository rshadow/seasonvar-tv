package ru.seasonvar.tv.ui.details

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import ru.seasonvar.tv.data.images.PosterBackdropSpec
import kotlin.math.roundToInt

internal data class PosterBackdropSource(val url: String, val bitmap: State<Bitmap?>)

internal typealias PosterBackdropPublisher = (PosterBackdropSource) -> DisposableHandle

internal class PosterBackdropState : State<PosterBackdropSource?> {
    private val current = mutableStateOf<PosterBackdropSource?>(null)
    private var owner: Any? = null
    override val value: PosterBackdropSource? get() = current.value

    fun attach(source: PosterBackdropSource): DisposableHandle {
        val token = Any()
        owner = token
        current.value = source
        return DisposableHandle { if (owner === token) { owner = null; current.value = null } }
    }
}

@Composable
internal fun PosterBackdropLayer(source: State<PosterBackdropSource?>, bounds: IntSize, menuEnd: Float = 0f) {
    val poster = source.value
    val empty = remember { mutableStateOf<Bitmap?>(null) }
    PosterBackdrop(poster?.url, poster?.bitmap ?: empty, bounds, menuEnd)
}

@Composable
internal fun PosterBackdrop(posterUrl: String?, source: State<Bitmap?>, bounds: IntSize, menuEnd: Float = 0f) {
    val cache = (LocalContext.current.applicationContext as SeasonvarApplication).posterBackdrops
    val input = source.value
    val background = MaterialTheme.colorScheme.background
    val dim = SeasonvarTheme.posterBackdropDimFraction
    val edge = SeasonvarTheme.posterBackdropEdgeFraction
    val fadeEdge = SeasonvarTheme.posterBackdropFadeEdgeFraction
    val spec = remember(bounds, background, dim, edge, fadeEdge, menuEnd) {
        bounds.takeIf { it.width > 0 && it.height > 0 }?.let { PosterBackdropSpec(it.width, it.height, background.toArgb(), dim, edge, fadeEdge, menuEnd) }
    }
    val bitmapState = remember(posterUrl, spec) {
        mutableStateOf(if (posterUrl != null && spec != null) cache.get(posterUrl, spec) else null)
    }
    LaunchedEffect(posterUrl, input, spec) {
        bitmapState.value = if (posterUrl != null && spec != null) cache.get(posterUrl, spec) else null
        if (posterUrl != null && input != null && spec != null && bitmapState.value == null) {
            try { bitmapState.value = cache.prepare(posterUrl, input, spec) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { bitmapState.value = null }
        }
    }
    val bitmap by bitmapState
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    val opacity = key(posterUrl) {
        animateFloatAsState(if (image == null) 0f else 1f,
            tween(SeasonvarTheme.posterBackdropFadeMillis), label = "posterBackdrop")
    }
    Box(Modifier.fillMaxSize().clearAndSetSemantics {}.drawWithCache {
        val target = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1))
        onDrawBehind {
            if (image != null) {
                drawImage(image, srcOffset = IntOffset.Zero, srcSize = IntSize(image.width, image.height),
                    dstSize = target, alpha = opacity.value, filterQuality = FilterQuality.Low)
            }
        }
    })
}
