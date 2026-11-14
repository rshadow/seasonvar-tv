package ru.seasonvar.tv.ui.settings

import org.junit.Assert.*
import org.junit.Test

class DetailsAppearanceTest {
    @Test fun existing_installations_and_unknown_options_keep_the_plain_background() {
        assertEquals(DetailsAppearance.PLAIN, DetailsAppearance.fromStorage(null))
        assertEquals(DetailsAppearance.PLAIN, DetailsAppearance.fromStorage("future-option"))
        DetailsAppearance.entries.forEach { assertEquals(it, DetailsAppearance.fromStorage(it.storageId)) }
    }
}
