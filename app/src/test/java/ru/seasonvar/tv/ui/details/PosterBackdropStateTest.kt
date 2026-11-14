package ru.seasonvar.tv.ui.details

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.*
import org.junit.Test

class PosterBackdropStateTest {
    @Test fun an_old_item_cannot_release_the_new_selection() {
        val state = PosterBackdropState()
        val first = PosterBackdropSource("shared-poster", mutableStateOf(null))
        val second = first.copy()
        val firstHandle = state.attach(first)
        val secondHandle = state.attach(second)
        firstHandle.dispose()
        assertEquals(second, state.value)
        secondHandle.dispose()
        assertNull(state.value)
        state.attach(first)
        firstHandle.dispose()
        secondHandle.dispose()
        assertSame(first, state.value)
    }
}
