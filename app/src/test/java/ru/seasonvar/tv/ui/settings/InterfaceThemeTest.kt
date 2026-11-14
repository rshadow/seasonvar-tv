package ru.seasonvar.tv.ui.settings

import org.junit.Assert.*
import org.junit.Test

class InterfaceThemeTest {
    private val themes = InterfaceThemeCatalog(listOf(InterfaceTheme.CLASSIC, InterfaceTheme.GLASS), "plain")
    @Test fun existing_installations_and_unknown_options_keep_the_plain_background() {
        assertEquals(InterfaceTheme.CLASSIC, themes.restore(null))
        assertEquals(InterfaceTheme.CLASSIC, themes.restore("future-option"))
        assertEquals(InterfaceTheme.CLASSIC, themes.restore("plain"))
        assertEquals(InterfaceTheme.GLASS, themes.restore("blurred_poster"))
        assertEquals(InterfaceTheme.GLASS, themes.restore("glass"))
        themes.entries.forEach { assertEquals(it, themes.restore(it.storageId)) }
    }
    @Test fun a_configured_theme_restores_without_a_builtin_constant() {
        val custom = InterfaceTheme("custom", 101, 201)
        val catalog = InterfaceThemeCatalog(themes.entries + custom, "custom")
        assertEquals(custom, catalog.restore("custom"))
        assertEquals(custom, catalog.restore("unknown"))
        assertEquals(custom, catalog.restore(null))
        assertTrue(custom in catalog.entries)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicate_ids_are_rejected() {
        InterfaceThemeCatalog(listOf(InterfaceTheme.CLASSIC, InterfaceTheme.CLASSIC.copy(styleRes = 101)), "plain")
    }

    @Test(expected = IllegalArgumentException::class)
    fun missing_default_is_rejected() {
        InterfaceThemeCatalog(themes.entries, "missing")
    }

    @Test(expected = IllegalArgumentException::class)
    fun an_unlabelled_theme_is_rejected() {
        InterfaceThemeCatalog(listOf(InterfaceTheme.CLASSIC.copy(label = 0)), "plain")
    }

    @Test(expected = IllegalArgumentException::class)
    fun the_legacy_poster_id_remains_reserved_for_migration() {
        InterfaceThemeCatalog(listOf(InterfaceTheme.GLASS.copy(storageId = "blurred_poster")), "blurred_poster")
    }
}
