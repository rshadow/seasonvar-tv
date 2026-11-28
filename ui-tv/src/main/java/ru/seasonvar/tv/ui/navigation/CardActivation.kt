package ru.seasonvar.tv.ui.navigation

/** One gesture produces either an open or a context action, never both. */
internal class CardActivation {
    private var pressed = false
    private var held = false
    fun press(): Boolean {
        if (pressed) return false
        pressed = true
        held = false
        return true
    }
    fun hold(): Boolean {
        if (!pressed || held) return false
        held = true
        return true
    }
    fun release(): Boolean {
        val open = pressed && !held
        cancel()
        return open
    }
    fun cancel() { pressed = false; held = false }
}
