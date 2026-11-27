package ru.seasonvar.tv.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.seasonvar.tv.R
import kotlin.math.PI
import kotlin.math.cos

@Composable
internal fun WinterGarland(modifier: Modifier, sideMargin: Dp, bottomMargin: Dp) {
    val gold = colorResource(R.color.seasonvar_winter_gold)
    val cranberry = colorResource(R.color.seasonvar_winter_cranberry)
    val ice = colorResource(R.color.seasonvar_winter_ice)
    val mint = colorResource(R.color.seasonvar_winter_mint)
    val cordColor = colorResource(R.color.seasonvar_winter_garland_cord)
    val cycleMillis = integerResource(R.integer.seasonvar_winter_garland_cycle_ms)
    val phase = rememberInfiniteTransition(label = "winterGarland").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(cycleMillis, easing = LinearEasing)),
        label = "garlandLight",
    )

    Spacer(modifier.drawWithCache {
        val colors = listOf(gold, cranberry, ice, mint, gold, cranberry, ice, gold, mint, cranberry, gold, ice,
            mint, gold, cranberry, ice, mint)
        val turn = (2 * PI).toFloat()
        val glowRadius = 9.dp.toPx()
        val socketLength = 2.3.dp.toPx()
        val socketWidth = 1.8.dp.toPx()
        val bulbWidth = 3.5.dp.toPx()
        val bulbLength = 4.4.dp.toPx()
        val cord = garlandCord(size.width, size.height, sideMargin.toPx(), bottomMargin.toPx())
        val measure = PathMeasure().apply { setPath(cord, forceClosed = false) }
        val lamps = colors.mapIndexed { index, color ->
            val distance = measure.length * (index + 0.62f) / colors.size
            val base = measure.getPosition(distance)
            val tangent = measure.getTangent(distance)
            val side = if (index % 2 == 0) 1f else -1f
            val direction = Offset(-tangent.y * side, tangent.x * side)
            val neck = base + direction * socketLength
            val tip = base + direction * bulbLength
            val glow = Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), tip, glowRadius)
            GarlandLamp(base, neck, tip, color, glow)
        }
        val cordShadow = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Round)
        val cordStroke = Stroke(width = 0.9.dp.toPx(), cap = StrokeCap.Round)

        onDrawBehind {
            drawPath(cord, Color.Black, alpha = 0.55f, style = cordShadow)
            drawPath(cord, cordColor, alpha = 0.64f, style = cordStroke)
            val wave = phase.value * turn
            for ((index, lamp) in lamps.withIndex()) {
                val brightness = 0.5f + 0.5f * cos(wave - index * 0.72f)
                drawCircle(lamp.glow, glowRadius, lamp.tip, alpha = 0.15f + 0.49f * brightness)
                drawLine(cordColor, lamp.base, lamp.neck, socketWidth, StrokeCap.Round)
                drawLine(lamp.color, lamp.neck, lamp.tip, bulbWidth, StrokeCap.Round,
                    alpha = 0.55f + 0.45f * brightness)
                drawCircle(Color.White, bulbWidth * 0.22f, lamp.tip, alpha = 0.18f + 0.51f * brightness)
            }
        }
    })
}

private data class GarlandLamp(val base: Offset, val neck: Offset, val tip: Offset, val color: Color, val glow: Brush)

private fun garlandCord(width: Float, height: Float, side: Float, bottom: Float): Path {
    val menuBottom = height - bottom
    return Path().apply {
        moveTo(-side * 0.40f, -side * 0.16f)
        cubicTo(side * 0.18f, -side * 0.04f, side * 0.84f, menuBottom * 0.048f, side * 0.60f, menuBottom * 0.115f)
        cubicTo(side * 0.23f, menuBottom * 0.23f, side * 0.78f, menuBottom * 0.25f, side * 0.71f, menuBottom * 0.33f)
        cubicTo(side * 0.65f, menuBottom * 0.41f, side * 0.28f, menuBottom * 0.44f, side * 0.31f, menuBottom * 0.52f)
        cubicTo(side * 0.35f, menuBottom * 0.60f, side * 0.82f, menuBottom * 0.65f, side * 0.70f, menuBottom * 0.73f)
        cubicTo(side * 0.61f, menuBottom * 0.82f, side * 0.29f, menuBottom * 0.90f, side * 0.46f, menuBottom * 0.97f)

        // The first bend and the return cross once inside the margin below the menu.
        fun belowMenu(fraction: Float) = menuBottom + bottom * fraction
        cubicTo(side * 0.55f, belowMenu(0.27f), side * 0.73f, belowMenu(0.48f), width * 0.28f, belowMenu(0.56f))
        cubicTo(width * 0.42f, belowMenu(0.66f), width * 0.58f, belowMenu(0.46f), width * 0.67f, belowMenu(0.32f))
        cubicTo(width * 0.76f, belowMenu(0.18f), width * 0.73f, belowMenu(0.09f), width * 0.62f, belowMenu(0.11f))
        cubicTo(width * 0.44f, belowMenu(0.12f), width * 0.38f, belowMenu(0.46f), width * 0.49f, belowMenu(0.72f))
        cubicTo(width * 0.61f, belowMenu(1.00f), width * 0.73f, belowMenu(0.61f), width * 0.85f, belowMenu(0.70f))
        cubicTo(width * 0.91f, belowMenu(0.75f), width * 0.95f, belowMenu(0.97f), width * 0.99f, belowMenu(1.22f))
    }
}
