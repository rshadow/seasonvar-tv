package ru.seasonvar.tv.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.integerResource
import ru.seasonvar.tv.R
import kotlin.math.PI
import kotlin.math.cos

@Composable
internal fun WinterTreeLights(modifier: Modifier, backgroundOpacity: State<Float>) {
    val resources = LocalResources.current
    val positions = remember(resources) { resources.getIntArray(R.array.seasonvar_winter_tree_lights) }
    val amber = colorResource(R.color.seasonvar_winter_garland_amber)
    val warmWhite = colorResource(R.color.seasonvar_winter_garland_warm_white)
    val edgeFade = SeasonvarTheme.posterBackdropFadeEdgeFraction
    val cycle = integerResource(R.integer.seasonvar_winter_tree_lights_cycle_ms)
    val phase = rememberInfiniteTransition(label = "winterTree").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(cycle, easing = LinearEasing)),
        label = "treeLights",
    )

    Spacer(modifier.drawWithCache {
        // Match the center crop of the 16:9 artwork in PosterBackdropCache.
        val sceneAspect = 16f / 9f
        val sceneHeight = maxOf(size.height, size.width / sceneAspect)
        val sceneWidth = sceneHeight * sceneAspect
        val sceneOrigin = Offset((size.width - sceneWidth) / 2f, (size.height - sceneHeight) / 2f)
        val fadeWidth = (size.width * edgeFade).coerceAtLeast(1f)
        val fadeHeight = (size.height * edgeFade).coerceAtLeast(1f)
        val turn = (2 * PI).toFloat()
        val lights = (0 until positions.size step 2).mapIndexedNotNull { index, coordinate ->
            val center = sceneOrigin + Offset(
                sceneWidth * positions[coordinate] / 1000f,
                sceneHeight * positions[coordinate + 1] / 1000f,
            )
            val horizontal = (minOf(center.x, size.width - center.x) / fadeWidth).coerceIn(0f, 1f)
            val vertical = (minOf(center.y, size.height - center.y) / fadeHeight).coerceIn(0f, 1f)
            val visibility = horizontal * vertical
            if (visibility == 0f) return@mapIndexedNotNull null

            val radius = sceneHeight * (0.014f + 0.003f * (index % 4))
            val halo = Brush.radialGradient(
                0f to warmWhite.copy(alpha = 0.91f),
                0.22f to amber.copy(alpha = 0.48f),
                0.62f to amber.copy(alpha = 0.13f),
                1f to amber.copy(alpha = 0f),
                center = center,
                radius = radius,
            )
            val phaseOffset = ((index * 13) % (positions.size / 2)) * turn / (positions.size / 2)
            TreeLight(center, radius, halo, visibility, phaseOffset)
        }

        onDrawBehind {
            val opacity = backgroundOpacity.value
            val time = phase.value * turn
            for (light in lights) {
                val brightness = 0.5f + 0.5f * cos(time + light.phaseOffset)
                val alpha = (0.03f + 0.58f * brightness * brightness) * opacity * light.visibility
                drawCircle(light.halo, light.radius, light.center, alpha = alpha, blendMode = BlendMode.Plus)
            }
        }
    })
}

private data class TreeLight(
    val center: Offset,
    val radius: Float,
    val halo: Brush,
    val visibility: Float,
    val phaseOffset: Float,
)
