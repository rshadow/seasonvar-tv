package ru.seasonvar.tv.ui.navigation

internal class BackNavigation {
    private var lastPress: Long? = null

    fun shouldExit(now: Long, fromMenu: Boolean): Boolean {
        if (!fromMenu) {
            reset()
            return false
        }
        val previous = lastPress
        if (previous != null && now - previous in 0..DOUBLE_PRESS_MILLIS) {
            reset()
            return true
        }
        lastPress = now
        return false
    }

    fun reset() {
        lastPress = null
    }

    companion object {
        const val DOUBLE_PRESS_MILLIS = 2000L
    }
}
