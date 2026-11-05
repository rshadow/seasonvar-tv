package ru.seasonvar.tv.ui.catalog

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class CatalogCardMetricsTest {
    private fun calculate(width: Int, density: Density = Density(2f), title: Int = 30, subtitle: Int = 13) =
        calculateCatalogCardMetrics(width.dp, 128.dp, 6.dp, 8.dp, 2f / 3f, title.dp, subtitle.dp, density)

    @Test fun width_is_shared_with_native_fixed_size_cells_at_different_densities() {
        for (scale in listOf(1f, 1.5f, 2f, 2.625f, 3f)) {
            val density = Density(scale)
            for (viewport in listOf(80, 260, 496, 688, 800, 1200)) {
                val metrics = calculate(viewport, density)
                with(density) {
                    val usable = viewport.dp.roundToPx() - 2 * 6.dp.roundToPx()
                    val gap = 8.dp.roundToPx()
                    val cells = with(GridCells.FixedSize(metrics.width)) {
                        density.calculateCrossAxisCellSizes(usable, gap)
                    }
                    assertEquals("$viewport at $scale", metrics.columns, cells.size)
                    assertTrue(cells.all { it == metrics.width.roundToPx() })
                    val used = cells.sum() + (cells.size - 1) * 8.dp.roundToPx()
                    assertTrue(used <= usable)
                    assertTrue(usable - used < metrics.columns)
                }
            }
        }
    }

    @Test fun resizing_recalculates_columns_and_keeps_poster_ratio_with_pixel_rounding() {
        val wide = calculate(688)
        val narrow = calculate(496)
        assertEquals(5, wide.columns)
        assertEquals(3, narrow.columns)
        assertNotEquals(wide.width, narrow.width)
        with(Density(2f)) {
            for (metrics in listOf(wide, narrow)) {
                assertEquals((metrics.width - 12.dp).toPx() / (2f / 3f), metrics.posterHeight.toPx(), .51f)
                assertEquals(metrics.height + 12.dp, metrics.rowHeight)
            }
        }
    }

    @Test fun measured_text_heights_change_card_height_without_changing_poster_or_width() {
        val normal = calculate(688)
        val largerText = calculate(688, Density(2f, 1.5f), title = 45, subtitle = 20)
        assertEquals(normal.width, largerText.width)
        assertEquals(normal.posterHeight, largerText.posterHeight)
        assertEquals(normal.height + 22.dp, largerText.height)
        assertEquals(45.dp, largerText.titleHeight)
        assertEquals(20.dp, largerText.subtitleHeight)
    }

    @Test fun a_narrow_viewport_keeps_one_card_inside_the_available_width() {
        val metrics = calculate(80)
        assertEquals(1, metrics.columns)
        assertEquals(68.dp, metrics.width)
        assertTrue(metrics.height.value > 0)
    }
}
