package ru.seasonvar.tv.ui.navigation

import org.junit.Assert.*
import org.junit.Test

class BackNavigationTest {
    @Test fun first_press_never_exits_even_just_after_device_start() {
        assertFalse(BackNavigation().shouldExit(0))
        assertFalse(BackNavigation().shouldExit(100))
    }

    @Test fun second_press_within_window_exits_and_clears_sequence() {
        val navigation = BackNavigation()
        assertFalse(navigation.shouldExit(1000))
        assertTrue(navigation.shouldExit(1500))
        assertFalse(navigation.shouldExit(1510))
    }

    @Test fun slow_press_starts_a_new_sequence() {
        val navigation = BackNavigation()
        assertFalse(navigation.shouldExit(1000))
        assertFalse(navigation.shouldExit(1501))
        assertTrue(navigation.shouldExit(1600))
    }

    @Test fun other_navigation_cancels_pending_exit() {
        val navigation = BackNavigation()
        assertFalse(navigation.shouldExit(1000))
        navigation.reset()
        assertFalse(navigation.shouldExit(1100))
    }

    @Test fun an_earlier_clock_value_cannot_exit() {
        val navigation = BackNavigation()
        assertFalse(navigation.shouldExit(1000))
        assertFalse(navigation.shouldExit(999))
    }
}
