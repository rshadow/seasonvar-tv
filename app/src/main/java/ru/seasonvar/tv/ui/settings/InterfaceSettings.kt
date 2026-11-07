package ru.seasonvar.tv.ui.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.seasonvar.tv.ui.navigation.Section
import ru.seasonvar.tv.ui.catalog.CatalogLayout
import ru.seasonvar.tv.ui.catalog.CatalogLayoutPreferences
import ru.seasonvar.tv.ui.player.PlayerClockPosition

internal class InterfaceSettings(context: Context) {
    private val preferences = context.getSharedPreferences("interface", Context.MODE_PRIVATE)
    private val layoutPreferences = CatalogLayoutPreferences(
        read = { preferences.getString(it, null) },
        write = { key, value -> preferences.edit().putString(key, value).apply() },
    )
    val catalogLayouts = layoutPreferences.layouts

    fun setCatalogLayout(section: Section, layout: CatalogLayout) = layoutPreferences.set(section, layout)

    private val mutableStartPage = MutableStateFlow(Section.fromStorage(preferences.getString("start_page", null)))
    val startPage = mutableStartPage.asStateFlow()

    private val mutablePlayerClockPosition = MutableStateFlow(PlayerClockPosition.fromStorage(
        preferences.getString("player_clock_position", null)))
    val playerClockPosition = mutablePlayerClockPosition.asStateFlow()

    fun setPlayerClockPosition(position: PlayerClockPosition) {
        preferences.edit().putString("player_clock_position", position.storageId).apply()
        mutablePlayerClockPosition.value = position
    }

    private val menuVisibility = MenuVisibilityPreferences(
        read = { preferences.getStringSet("hidden_sections", null) },
        write = { preferences.edit().putStringSet("hidden_sections", it).apply() },
    )
    val hiddenSections = menuVisibility.hiddenSections

    fun setSectionVisible(section: Section, visible: Boolean) = menuVisibility.setVisible(section, visible)

    private val mutablePreferredTranslations = MutableStateFlow(ru.seasonvar.tv.data.TranslationPriorities.decode(
        preferences.getString("preferred_translations", null)))
    val preferredTranslations = mutablePreferredTranslations.asStateFlow()

    fun setPreferredTranslations(values: List<String>) {
        val selected = ru.seasonvar.tv.data.TranslationPriorities.clean(values)
        preferences.edit().putString("preferred_translations", ru.seasonvar.tv.data.TranslationPriorities.encode(selected)).apply()
        mutablePreferredTranslations.value = selected
    }

    private val mutableAutoNextEnabled = MutableStateFlow(preferences.getBoolean("auto_next_episode", true))
    val autoNextEnabled = mutableAutoNextEnabled.asStateFlow()

    fun setAutoNextEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("auto_next_episode", enabled).apply()
        mutableAutoNextEnabled.value = enabled
    }

    private val mutableAutoNextSeasonEnabled = MutableStateFlow(preferences.getBoolean("auto_next_season", true))
    val autoNextSeasonEnabled = mutableAutoNextSeasonEnabled.asStateFlow()

    fun setAutoNextSeasonEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("auto_next_season", enabled).apply()
        mutableAutoNextSeasonEnabled.value = enabled
    }

    private val mutableImagePrefetchEnabled = MutableStateFlow(preferences.getBoolean("image_prefetch", true))
    val imagePrefetchEnabled = mutableImagePrefetchEnabled.asStateFlow()

    fun setImagePrefetchEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("image_prefetch", enabled).apply()
        mutableImagePrefetchEnabled.value = enabled
    }

    fun setStartPage(section: Section) {
        require(section in Section.startPages(true))
        preferences.edit().putString("start_page", section.storageId).apply()
        mutableStartPage.value = section
    }
}
