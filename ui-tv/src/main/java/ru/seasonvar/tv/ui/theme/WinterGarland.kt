package ru.seasonvar.tv.ui.theme

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path as AndroidPath

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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import ru.seasonvar.tv.R
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

@Composable
internal fun WinterGarland(modifier: Modifier, sideMargin: Dp, bottomMargin: Dp) {
    val amber = colorResource(R.color.seasonvar_winter_garland_amber)
    val warmWhite = colorResource(R.color.seasonvar_winter_garland_warm_white)
    val cranberry = colorResource(R.color.seasonvar_winter_cranberry)
    val ice = colorResource(R.color.seasonvar_winter_ice)
    val cordColor = colorResource(R.color.seasonvar_winter_garland_cord)
    val frontColor = colorResource(R.color.seasonvar_winter_garland_cord_front)
    val backgroundBlur = dimensionResource(R.dimen.seasonvar_winter_garland_background_blur)
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
        if (size.width == 0f || size.height == 0f) return@drawWithCache onDrawBehind {}
        val side = sideMargin.toPx()
        val bottom = bottomMargin.toPx()
        val cord = garlandCord(size.width, size.height, side, bottom)
        val measure = PathMeasure().apply { setPath(cord.path, forceClosed = false) }
        val pathLength = measure.length
        val turn = (2 * PI).toFloat()
        val sockets = listOf(
            0.052f to warmWhite, 0.100f to amber, 0.145f to warmWhite, 0.199f to cranberry,
            0.263f to amber, 0.322f to warmWhite, 0.388f to amber, 0.452f to cranberry,
            0.520f to ice, 0.578f to warmWhite, 0.637f to amber, 0.697f to warmWhite,
            0.780f to amber, 0.860f to warmWhite, 0.947f to amber,
        )
        val glowRadius = 22.dp.toPx()
        val socketLength = 3.dp.toPx()
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
                0f to warmWhite.copy(alpha = 0.88f),
                0.23f to color.copy(alpha = 0.43f),
                0.58f to color.copy(alpha = 0.11f),
                1f to color.copy(alpha = 0f),
                center = tip,
                radius = glowRadius,
            )
            val phaseOffset = ((index * 7) % sockets.size) * turn / sockets.size
            GarlandLamp(base, neck, tip, phaseOffset, glass, color, glow)
        }

        val pitch = twistPitch.toPx()
        val radius = twistRadius.toPx()
        val wire = twistedWire(measure, 0f, pathLength, pitch, radius)
        val loopWire = twistedWire(measure, cord.crossingStart, cord.crossingEnd, pitch, radius)
        val loopCenter = Path().apply {
            measure.getSegment(cord.crossingStart, cord.crossingEnd, this, startWithMoveTo = true)
        }
        val backdrop = renderGarlandBackdrop(
            size, wire, loopWire, loopCenter, lamps, cordColor, frontColor, socketColor,
            strandWidth.toPx(), radius, backgroundBlur.toPx(),
        )
        val destination = IntSize(size.width.roundToInt(), size.height.roundToInt())
        val reflectedLightCenter = Offset(size.width * 0.54f, size.height + bottom * 0.16f)
        val reflectedLightRadius = bottom * 2f
        val reflectedLight = Brush.radialGradient(listOf(amber, amber.copy(alpha = 0f)),
            reflectedLightCenter, reflectedLightRadius)

        onDrawBehind {
            drawCircle(reflectedLight, reflectedLightRadius, reflectedLightCenter, alpha = 0.085f)
            drawImage(backdrop, dstSize = destination, filterQuality = FilterQuality.Low)

            val time = phase.value * turn
            for (lamp in lamps) {
                val brightness = 0.5f + 0.5f * cos(time + lamp.phaseOffset)
                drawCircle(lamp.glow, glowRadius, lamp.tip, alpha = 0.07f + 0.48f * brightness,
                    blendMode = BlendMode.Plus)
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
    val color: Color,
    val glow: Brush,
)

/** Rasterize the soft, mostly static shapes once. Only the light halos are animated. */
private fun renderGarlandBackdrop(
    size: Size,
    wire: TwistedWire,
    loopWire: TwistedWire,
    loopCenter: Path,
    lamps: List<GarlandLamp>,
    cordColor: Color,
    frontColor: Color,
    socketColor: Color,
    strandWidth: Float,
    twistRadius: Float,
    blur: Float,
): ImageBitmap {
    val scale = 0.5f
    val bitmap = Bitmap.createBitmap((size.width * scale).roundToInt().coerceAtLeast(1),
        (size.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val matrix = Matrix().apply { setScale(scale, scale) }
    val scaledPath = AndroidPath()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    val cordBlur = BlurMaskFilter(blur * scale, BlurMaskFilter.Blur.NORMAL)
    val bulbBlur = BlurMaskFilter(blur * scale * 0.72f, BlurMaskFilter.Blur.NORMAL)

    fun draw(path: Path, color: Color, width: Float = 0f) {
        scaledPath.rewind()
        path.asAndroidPath().transform(matrix, scaledPath)
        paint.color = color.toArgb()
        paint.style = if (width == 0f) Paint.Style.FILL else Paint.Style.STROKE
        paint.strokeWidth = width * scale
        canvas.drawPath(scaledPath, paint)
    }

    fun drawCable(cable: TwistedWire) {
        draw(cable.strands, Color.Black.copy(alpha = 0.64f), strandWidth * 2.6f)
        draw(cable.strands, cordColor, strandWidth * 1.6f)
        draw(cable.front, frontColor.copy(alpha = 0.48f), strandWidth)
    }

    paint.maskFilter = cordBlur
    drawCable(wire)
    draw(loopCenter, Color.Black.copy(alpha = 0.78f), strandWidth * 2f + 2 * twistRadius)
    drawCable(loopWire)

    paint.maskFilter = bulbBlur
    val sockets = Path().apply {
        for (lamp in lamps) {
            moveTo(lamp.base.x, lamp.base.y)
            lineTo(lamp.neck.x, lamp.neck.y)
        }
    }
    draw(sockets, socketColor, strandWidth * 2.6f)
    for (lamp in lamps) {
        draw(lamp.glass, lamp.color.copy(alpha = 0.61f))
    }
    return bitmap.asImageBitmap()
}

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
        cubicTo(side * 0.68f, -side * 0.03f, side * 0.31f, menuBottom * 0.113f, width * 0.23f, menuBottom * 0.15f)
        cubicTo(width * 0.48f, menuBottom * 0.175f, width * 0.41f, menuBottom * 0.239f, width * 0.32f, menuBottom * 0.27f)
        cubicTo(width * 0.214f, menuBottom * 0.307f, side * 0.32f, menuBottom * 0.36f, side * 0.49f, menuBottom * 0.448f)
        cubicTo(side * 0.66f, menuBottom * 0.542f, width * 0.30f, menuBottom * 0.569f, width * 0.253f, menuBottom * 0.653f)
        cubicTo(width * 0.21f, menuBottom * 0.726f, side * 0.26f, menuBottom * 0.75f, side * 0.43f, menuBottom * 0.847f)
        cubicTo(side * 0.58f, menuBottom * 0.914f, side * 0.42f, belowMenu(0.43f), width * 0.26f, belowMenu(0.67f))
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
