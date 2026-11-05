package ru.seasonvar.tv.ui.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.MaterialTheme
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import kotlin.math.roundToInt

internal data class CatalogCardMetrics(
    val columns: Int,
    val width: Dp,
    val height: Dp,
    val posterHeight: Dp,
    val titleHeight: Dp,
    val subtitleHeight: Dp,
    val rowHeight: Dp,
)

internal fun calculateCatalogCardMetrics(
    availableWidth: Dp, minimumWidth: Dp, padding: Dp, gap: Dp, posterAspectRatio: Float,
    titleHeight: Dp, subtitleHeight: Dp, density: Density,
): CatalogCardMetrics = with(density) {
    require(minimumWidth.value > 0 && posterAspectRatio.isFinite() && posterAspectRatio > 0)
    require(padding.value >= 0 && gap.value >= 0 && titleHeight.value >= 0 && subtitleHeight.value >= 0)
    val inset = padding.roundToPx()
    val spacing = gap.roundToPx()
    val usable = (availableWidth.roundToPx() - 2 * inset).coerceAtLeast(1)
    val columns = ((usable + spacing) / (minimumWidth.roundToPx().coerceAtLeast(1) + spacing)).coerceAtLeast(1)
    val width = ((usable - (columns - 1) * spacing) / columns).coerceAtLeast(1)
    val poster = ((width - 2 * inset).coerceAtLeast(1) / posterAspectRatio).roundToInt().coerceAtLeast(1)
    val height = 2 * inset + poster + spacing + titleHeight.roundToPx() + subtitleHeight.roundToPx()
    CatalogCardMetrics(columns, width.toDp(), height.toDp(), poster.toDp(), titleHeight, subtitleHeight,
        (height + 2 * inset).toDp())
}

@Composable
internal fun rememberCatalogCardMetrics(availableWidth: Dp): CatalogCardMetrics {
    val d = SeasonvarTheme.dimensions
    val density = LocalDensity.current
    val title = MaterialTheme.typography.labelMedium
    val subtitle = MaterialTheme.typography.labelSmall
    val textMeasurer = rememberTextMeasurer()
    return remember(availableWidth, d, density.density, density.fontScale, title, subtitle, textMeasurer) {
        with(density) {
            calculateCatalogCardMetrics(
                availableWidth, d.catalogMinCardWidth, d.cardPadding, d.spacingSmall, d.posterWidth / d.posterHeight,
                textMeasurer.measure("M\nM", style = title, maxLines = 2).size.height.toDp(),
                textMeasurer.measure("M", style = subtitle, maxLines = 1).size.height.toDp(), density,
            )
        }
    }
}
