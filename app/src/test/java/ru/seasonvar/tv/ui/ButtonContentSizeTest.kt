package ru.seasonvar.tv.ui

import androidx.compose.ui.unit.Constraints
import org.junit.Assert.*
import org.junit.Test

class ButtonContentSizeTest {
    @Test fun fixed_button_does_not_query_text_intrinsics() {
        val fixed = Constraints.fixed(320, 56)
        assertEquals(fixed, buttonContentConstraints(fixed,
            { error("Fixed width must not measure text") }, { error("Fixed height must not measure text") }))
    }

    @Test fun compact_button_with_fixed_height_uses_full_text_width() {
        val actual = buttonContentConstraints(Constraints(maxWidth = 400, minHeight = 56, maxHeight = 56),
            { height -> assertEquals(56, height); 240 }, { error("Height is already fixed") })
        assertEquals(Constraints.fixed(240, 56), actual)
    }

    @Test fun fixed_width_measures_height_at_that_width() {
        val actual = buttonContentConstraints(Constraints(minWidth = 160, maxWidth = 160),
            { error("Width is already fixed") }, { width -> assertEquals(160, width); 48 })
        assertEquals(Constraints.fixed(160, 48), actual)
    }

    @Test fun natural_button_honors_parent_limits_and_clips_like_the_intrinsic_modifiers() {
        val actual = buttonContentConstraints(Constraints(minWidth = 80, maxWidth = 200, minHeight = 40, maxHeight = 64),
            { 280 }, { width -> assertEquals(200, width); 80 })
        assertEquals(Constraints.fixed(200, 64), actual)
    }

    @Test fun unbounded_button_keeps_its_natural_dimensions() {
        val actual = buttonContentConstraints(Constraints(),
            { height -> assertEquals(Constraints.Infinity, height); 240 },
            { width -> assertEquals(240, width); 48 })
        assertEquals(Constraints.fixed(240, 48), actual)
    }

    @Test fun empty_content_still_respects_minimum_button_dimensions() {
        assertEquals(Constraints.fixed(48, 40), buttonContentConstraints(
            Constraints(minWidth = 48, minHeight = 40), { -1 }, { -1 }))
    }
}
