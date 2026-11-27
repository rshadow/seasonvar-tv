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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.seasonvar.tv.R
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun WinterGarland(modifier: Modifier, sideMargin: Dp, bottomMargin: Dp) {
    val amber = colorResource(R.color.seasonvar_winter_garland_amber)
    val warmWhite = colorResource(R.color.seasonvar_winter_garland_warm_white)
    val cranberry = colorResource(R.color.seasonvar_winter_cranberry)
    val ice = colorResource(R.color.seasonvar_winter_ice)
    val cordColor = colorResource(R.color.seasonvar_winter_garland_cord)
    val frontColor = colorResource(R.color.seasonvar_winter_garland_cord_front)
    val highlightColor = colorResource(R.color.seasonvar_winter_garland_cord_highlight)
    val strandWidth = dimensionResource(R.dimen.seasonvar_winter_garland_strand_width)
    val twistRadius = dimensionResource(R.dimen.seasonvar_winter_garland_twist_radius)
    val twistPitch = dimensionResource(R.dimen.seasonvar_winter_garland_twist_pitch)
    val socketColor = colorResource(R.color.seasonvar_winter_garland_socket)
    val cycleMillis = integerResource(R.integer.seasonvar_winter_garland_cycle_ms)
    val phase = rememberInfiniteTransition(label = "winterGarland").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(cycleMillis, easing = LinearEasing)),
        label = "garlandLight",
    )

    Spacer(modifier.drawWithCache {
        val side = sideMargin.toPx()
        val bottom = bottomMargin.toPx()
        val cord = garlandCord(size.width, size.height, side, bottom)
        val measure = PathMeasure().apply { setPath(cord.path, forceClosed = false) }
        val pathLength = measure.length
        val turn = (2 * PI).toFloat()
        val sockets = listOf(
            0.052f to warmWhite, 0.133f to cranberry, 0.210f to amber, 0.296f to warmWhite,
            0.367f to ice, 0.438f to amber, 0.523f to cranberry, 0.607f to warmWhite,
            0.682f to amber, 0.756f to warmWhite, 0.830f to amber, 0.884f to ice, 0.945f to warmWhite,
        )
        val glowRadius = 20.dp.toPx()
        val socketWidth = 3.2.dp.toPx()
        val socketLength = 3.dp.toPx()
        val glintRadius = 0.70.dp.toPx()
        val bulbLeans = listOf(0.44f, 0.78f, 0.18f, 0.57f, 0.32f)
        val lamps = sockets.mapIndexed { index, (progress, color) ->
            val distance = pathLength * progress
            val base = measure.getPosition(distance)
            val tangent = measure.getTangent(distance)
            val normal = Offset(-tangent.y, tangent.x)
            val facing = if (base.x < side * 0.37f) {
                if (normal.x >= 0f) 1f else -1f
            } else if (index % 7 in 2..4) -1f else 1f
            val lean = bulbLeans[index % bulbLeans.size]
            val direction = normal * facing + Offset(0f, lean)
            val axis = direction / direction.getDistance().coerceAtLeast(0.1f)
            val neck = base + axis * socketLength
            val length = (6.9f + 0.48f * (index % 4)).dp.toPx()
            val tip = neck + axis * length
            val glass = dropShape(neck, axis, length, (2.7f + 0.15f * (index % 3)).dp.toPx())
            val glow = Brush.radialGradient(
                0f to color,
                0.23f to color.copy(alpha = 0.34f),
                1f to color.copy(alpha = 0f),
                center = tip,
                radius = glowRadius,
            )
            val glassColor = Brush.linearGradient(listOf(color.copy(alpha = 0.60f), color, warmWhite, color), neck, tip)
            val phaseOffset = ((index * 5) % sockets.size) * turn / sockets.size
            GarlandLamp(base, neck, tip, phaseOffset, glass, glassColor, glow)
        }

        val pitch = twistPitch.toPx()
        val radius = twistRadius.toPx()
        val wire = twistedWire(measure, 0f, pathLength, pitch, radius)
        val loopWire = twistedWire(measure, cord.crossingStart, cord.crossingEnd, pitch, radius)
        val loopCenter = Path().apply {
            measure.getSegment(cord.crossingStart, cord.crossingEnd, this, startWithMoveTo = true)
        }
        val width = strandWidth.toPx()
        val outline = Stroke(width + 0.65.dp.toPx(), cap = StrokeCap.Round)
        val strand = Stroke(width, cap = StrokeCap.Round)
        val specular = Stroke(width * 0.27f, cap = StrokeCap.Round)
        val highlightOffset = width * 0.18f
        val loopShadow = Stroke(width + 2 * radius + 0.7.dp.toPx(), cap = StrokeCap.Round)

        fun DrawScope.drawWire(wire: TwistedWire) {
            drawPath(wire.strands, Color.Black, alpha = 0.59f, style = outline)
            drawPath(wire.strands, cordColor, style = strand)
            drawPath(wire.front, Color.Black, alpha = 0.55f, style = outline)
            drawPath(wire.front, frontColor, style = strand)
            translate(-highlightOffset, -highlightOffset) {
                drawPath(wire.front, highlightColor, alpha = 0.58f, style = specular)
            }
        }
        val reflectedLightCenter = Offset(size.width * 0.54f, size.height + bottom * 0.16f)
        val reflectedLightRadius = bottom * 2f
        val reflectedLight = Brush.radialGradient(listOf(amber, amber.copy(alpha = 0f)),
            reflectedLightCenter, reflectedLightRadius)

        onDrawBehind {
            drawCircle(reflectedLight, reflectedLightRadius, reflectedLightCenter, alpha = 0.085f)
            drawWire(wire)
            drawPath(loopCenter, Color.Black, alpha = 0.72f, style = loopShadow)
            drawWire(loopWire)

            val time = phase.value * turn
            for (lamp in lamps) {
                val brightness = 0.5f + 0.5f * cos(time + lamp.phaseOffset)
                drawCircle(lamp.glow, glowRadius, lamp.tip, alpha = 0.20f + 0.49f * brightness)
                drawLine(socketColor, lamp.base, lamp.neck, socketWidth, StrokeCap.Round)
                drawPath(lamp.glass, lamp.glassColor, alpha = 0.63f + 0.37f * brightness)
                drawCircle(Color.White, glintRadius, lamp.neck + (lamp.tip - lamp.neck) * 0.59f,
                    alpha = 0.23f + 0.40f * brightness)
            }
        }
    })
}

private data class GarlandLamp(
    val base: Offset,
    val neck: Offset,
    val tip: Offset,
    val phaseOffset: Float,
    val glass: Path,
    val glassColor: Brush,
    val glow: Brush,
)

private fun dropShape(neck: Offset, axis: Offset, length: Float, radius: Float): Path {
    val lateral = Offset(-axis.y, axis.x)
    fun point(along: Float, across: Float) = neck + axis * (length * along) + lateral * (radius * across)
    val left = point(0f, -0.42f)
    val tip = point(1f, 0f)
    val right = point(0f, 0.42f)
    val nearLeft = point(0.24f, -0.65f)
    val nearRight = point(0.24f, 0.65f)
    val farLeft = point(0.98f, -1.35f)
    val farRight = point(0.98f, 1.35f)
    return Path().apply {
        moveTo(left.x, left.y)
        cubicTo(nearLeft.x, nearLeft.y, farLeft.x, farLeft.y, tip.x, tip.y)
        cubicTo(farRight.x, farRight.y, nearRight.x, nearRight.y, right.x, right.y)
        close()
    }
}

private data class TwistedWire(val strands: Path, val front: Path)

private fun twistedWire(
    center: PathMeasure,
    start: Float,
    end: Float,
    pitch: Float,
    radius: Float,
): TwistedWire {
    val first = Path()
    val second = Path()
    val front = Path()
    val steps = ceil((end - start) / (pitch / 16f)).toInt().coerceAtLeast(1)
    val turn = (2 * PI).toFloat()
    var firstWasInFront = false

    for (step in 0..steps) {
        val distance = start + (end - start) * step / steps
        val position = center.getPosition(distance)
        val tangent = center.getTangent(distance)
        val angle = distance * turn / pitch
        val shift = Offset(-tangent.y, tangent.x) * (radius * sin(angle))
        val firstPosition = position + shift
        val secondPosition = position - shift
        if (step == 0) {
            first.moveTo(firstPosition.x, firstPosition.y)
            second.moveTo(secondPosition.x, secondPosition.y)
        } else {
            first.lineTo(firstPosition.x, firstPosition.y)
            second.lineTo(secondPosition.x, secondPosition.y)
        }

        // The strands are furthest apart when they change which one is in front.
        val firstIsInFront = cos(angle) >= 0f
        val foreground = if (firstIsInFront) firstPosition else secondPosition
        if (step == 0 || firstIsInFront != firstWasInFront) {
            front.moveTo(foreground.x, foreground.y)
        } else {
            front.lineTo(foreground.x, foreground.y)
        }
        firstWasInFront = firstIsInFront
    }
    first.addPath(second)
    return TwistedWire(first, front)
}

private data class GarlandCord(val path: Path, val crossingStart: Float, val crossingEnd: Float)

private fun garlandCord(width: Float, height: Float, side: Float, bottom: Float): GarlandCord {
    val menuBottom = height - bottom
    fun belowMenu(fraction: Float) = menuBottom + bottom * fraction
    val cord = Path().apply {
        moveTo(-side * 0.49f, -side * 0.19f)
        cubicTo(side * 0.14f, -side * 0.04f, side * 0.83f, menuBottom * 0.048f, side * 0.57f, menuBottom * 0.126f)
        cubicTo(side * 0.24f, menuBottom * 0.22f, side * 0.66f, menuBottom * 0.245f, side * 0.69f, menuBottom * 0.31f)
        cubicTo(side * 0.74f, menuBottom * 0.40f, side * 0.19f, menuBottom * 0.47f, side * 0.28f, menuBottom * 0.56f)
        cubicTo(side * 0.34f, menuBottom * 0.625f, side * 0.64f, menuBottom * 0.67f, side * 0.62f, menuBottom * 0.72f)
        cubicTo(side * 0.60f, menuBottom * 0.78f, side * 0.34f, menuBottom * 0.82f, side * 0.40f, menuBottom * 0.88f)
        cubicTo(side * 0.46f, menuBottom * 0.96f, side * 0.63f, belowMenu(0.43f), width * 0.26f, belowMenu(0.67f))
    }
    cord.cubicTo(width * 0.38f, belowMenu(0.87f), width * 0.59f, belowMenu(0.53f), width * 0.68f, belowMenu(0.29f))
    cord.cubicTo(width * 0.72f, belowMenu(0.16f), width * 0.65f, belowMenu(0.10f), width * 0.57f, belowMenu(0.19f))
    val measure = PathMeasure().apply { setPath(cord, forceClosed = false) }
    val crossoverStart = measure.length
    cord.cubicTo(width * 0.46f, belowMenu(0.31f), width * 0.36f, belowMenu(0.55f), width * 0.49f, belowMenu(0.82f))
    measure.setPath(cord, forceClosed = false)
    val crossoverEnd = measure.length

    cord.cubicTo(width * 0.59f, belowMenu(1.03f), width * 0.74f, belowMenu(0.62f), width * 0.85f, belowMenu(0.77f))
    cord.cubicTo(width * 0.91f, belowMenu(0.86f), width * 0.94f, belowMenu(0.99f), width * 0.99f, belowMenu(1.22f))
    return GarlandCord(cord, crossoverStart, crossoverEnd)
}
