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
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import ru.seasonvar.tv.data.images.PosterBackdropSpec
import kotlin.math.roundToInt

@Composable
internal fun PosterBackdrop(posterUrl: String, source: State<Bitmap?>, bounds: IntSize) {
    val cache = (LocalContext.current.applicationContext as SeasonvarApplication).posterBackdrops
    val input = source.value
    val background = MaterialTheme.colorScheme.background
    val dim = SeasonvarTheme.posterBackdropDimFraction
    val edge = SeasonvarTheme.posterBackdropEdgeFraction
    val fadeEdge = SeasonvarTheme.posterBackdropFadeEdgeFraction
    val spec = remember(bounds, background, dim, edge, fadeEdge) {
        bounds.takeIf { it.width > 0 && it.height > 0 }?.let { PosterBackdropSpec(it.width, it.height, background.toArgb(), dim, edge, fadeEdge) }
    }
    val bitmap by produceState<Bitmap?>(spec?.let { cache.get(posterUrl, it) }, posterUrl, input, spec) {
        value = spec?.let { cache.get(posterUrl, it) }
        if (input != null && spec != null && value == null) {
            try { value = cache.prepare(posterUrl, input, spec) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { value = null }
        }
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    val opacity = animateFloatAsState(if (image == null) 0f else 1f,
        tween(SeasonvarTheme.posterBackdropFadeMillis), label = "posterBackdrop")
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
