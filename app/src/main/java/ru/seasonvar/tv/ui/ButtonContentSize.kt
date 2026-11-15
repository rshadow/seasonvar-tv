package ru.seasonvar.tv.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth

internal fun Modifier.buttonContentSize(): Modifier = this then ButtonContentSizeElement

internal inline fun buttonContentConstraints(
    constraints: Constraints, maxIntrinsicWidth: (Int) -> Int, minIntrinsicHeight: (Int) -> Int,
): Constraints {
    val width = if (constraints.hasFixedWidth) constraints.maxWidth
        else constraints.constrainWidth(maxIntrinsicWidth(constraints.maxHeight).coerceAtLeast(0))
    val height = if (constraints.hasFixedHeight) constraints.maxHeight
        else constraints.constrainHeight(minIntrinsicHeight(width).coerceAtLeast(0))
    return Constraints.fixed(width, height)
}

private data object ButtonContentSizeElement : ModifierNodeElement<ButtonContentSizeNode>() {
    override fun create() = ButtonContentSizeNode()
    override fun update(node: ButtonContentSizeNode) = Unit
    override fun InspectorInfo.inspectableProperties() { name = "buttonContentSize" }
}

private class ButtonContentSizeNode : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val size = buttonContentConstraints(constraints,
            { measurable.maxIntrinsicWidth(it) }, { measurable.minIntrinsicHeight(it) })
        val placeable = measurable.measure(size)
        return layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) =
        measurable.maxIntrinsicWidth(height)
    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) =
        measurable.maxIntrinsicWidth(height)
    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) =
        measurable.minIntrinsicHeight(width)
    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) =
        measurable.minIntrinsicHeight(width)
}
