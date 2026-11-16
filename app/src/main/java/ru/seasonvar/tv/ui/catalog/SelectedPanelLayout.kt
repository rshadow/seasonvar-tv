package ru.seasonvar.tv.ui.catalog

import android.os.Debug
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import kotlin.math.roundToInt

@Composable
internal fun SelectedPanelLayout(modifier: Modifier, text: @Composable () -> Unit, poster: @Composable () -> Unit) {
    val d = SeasonvarTheme.dimensions
    val ratio = d.previewPosterAspectRatio
    val widthFraction = d.previewPosterMaxWidthFraction
    val spacing = d.spacingMedium
    Layout(content = { text(); poster() }, modifier = modifier) { measurables, constraints ->
        require(constraints.hasBoundedWidth && constraints.hasBoundedHeight)
        val observer = SelectedPanelDiagnostics.current()
        val start = if (observer != null) Debug.threadCpuTimeNanos() else 0L
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val posterWidth = (height * ratio).coerceAtMost(width * widthFraction).roundToInt()
        val gap = spacing.roundToPx().coerceAtMost(width - posterWidth)
        val textWidth = width - posterWidth - gap
        val label = measurables[0].measure(Constraints(minWidth = textWidth, maxWidth = textWidth, maxHeight = height))
        val image = measurables[1].measure(Constraints.fixed(posterWidth, height))
        observer?.measured(Debug.threadCpuTimeNanos() - start)
        layout(width, height) {
            label.placeRelative(0, 0)
            image.placeRelative(width - posterWidth, 0)
        }
    }
}
