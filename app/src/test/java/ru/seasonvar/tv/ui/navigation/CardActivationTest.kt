package ru.seasonvar.tv.ui.navigation

import org.junit.Assert.*
import org.junit.Test

class CardActivationTest {
    @Test fun short_press_opens_once_and_key_repeats_do_not_restart_it() {
        val activation = CardActivation()
        assertTrue(activation.press())
        assertFalse(activation.press())
        assertTrue(activation.release())
        assertFalse(activation.release())
    }
    @Test fun holding_opens_actions_once_and_release_does_not_open_the_series() {
        val activation = CardActivation()
        activation.press()
        assertTrue(activation.hold())
        assertFalse(activation.hold())
        assertFalse(activation.release())
        assertFalse(activation.hold())
    }
    @Test fun focus_loss_cancels_both_actions_and_next_press_still_works() {
        val activation = CardActivation()
        activation.press(); activation.cancel()
        assertFalse(activation.hold())
        assertFalse(activation.release())
        activation.press()
        assertTrue(activation.release())
    }
}
