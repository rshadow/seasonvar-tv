package ru.seasonvar.tv.ui.navigation

import org.junit.Assert.*
import org.junit.Test

class BackNavigationTest {
    @Test fun first_press_never_exits_even_just_after_device_start() {
        assertFalse(BackNavigation().shouldExit(0, fromMenu = true))
        assertFalse(BackNavigation().shouldExit(100, fromMenu = true))
    }

    @Test fun second_press_within_window_exits_and_clears_sequence() {
        val navigation = BackNavigation()
        assertFalse(navigation.shouldExit(1000, fromMenu = true))
        assertTrue(navigation.shouldExit(3000, fromMenu = true))
        assertFalse(navigation.shouldExit(3010, fromMenu = true))
    }

    @Test fun slow_press_starts_a_new_sequence() {
        val navigation = BackNavigation()
        assertFalse(navigation.shouldExit(1000, fromMenu = true))
        assertFalse(navigation.shouldExit(3001, fromMenu = true))
        assertTrue(navigation.shouldExit(3100, fromMenu = true))
    }

    @Test fun a_deliberate_second_press_does_not_require_fast_double_clicking() {
        val navigation = BackNavigation()
        assertFalse(navigation.shouldExit(1000, fromMenu = true))
        assertTrue(navigation.shouldExit(1800, fromMenu = true))
    }

    @Test fun other_navigation_cancels_pending_exit() {
        val navigation = BackNavigation()
        assertFalse(navigation.shouldExit(1000, fromMenu = true))
        navigation.reset()
        assertFalse(navigation.shouldExit(1100, fromMenu = true))
    }

    @Test fun an_earlier_clock_value_cannot_exit() {
        val navigation = BackNavigation()
        assertFalse(navigation.shouldExit(1000, fromMenu = true))
        assertFalse(navigation.shouldExit(999, fromMenu = true))
    }

    @Test fun returning_to_menu_does_not_count_towards_exit() {
        val navigation = BackNavigation()
        assertFalse(navigation.shouldExit(1000, fromMenu = false))
        assertFalse(navigation.shouldExit(1100, fromMenu = false))
        assertFalse(navigation.shouldExit(1200, fromMenu = true))
        assertTrue(navigation.shouldExit(1300, fromMenu = true))
    }

    @Test fun back_outside_menu_cancels_a_pending_exit() {
        val navigation = BackNavigation()
        assertFalse(navigation.shouldExit(1000, fromMenu = true))
        assertFalse(navigation.shouldExit(1100, fromMenu = false))
        assertFalse(navigation.shouldExit(1200, fromMenu = true))
        assertTrue(navigation.shouldExit(1300, fromMenu = true))
    }
}
