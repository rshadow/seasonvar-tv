package ru.seasonvar.tv.ui.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.seasonvar.tv.ui.navigation.Section

internal class InterfaceSettings(context: Context) {
    private val preferences = context.getSharedPreferences("interface", Context.MODE_PRIVATE)
    private val mutableStartPage = MutableStateFlow(Section.fromStorage(preferences.getString("start_page", null)))
    val startPage = mutableStartPage.asStateFlow()

    private val mutablePreferredTranslations = MutableStateFlow(ru.seasonvar.tv.data.TranslationPriorities.decode(
        preferences.getString("preferred_translations", null)))
    val preferredTranslations = mutablePreferredTranslations.asStateFlow()

    fun setPreferredTranslations(values: List<String>) {
        val selected = ru.seasonvar.tv.data.TranslationPriorities.clean(values)
        preferences.edit().putString("preferred_translations", ru.seasonvar.tv.data.TranslationPriorities.encode(selected)).apply()
        mutablePreferredTranslations.value = selected
    }

    fun setStartPage(section: Section) {
        require(section in Section.startPages(true))
        preferences.edit().putString("start_page", section.storageId).apply()
        mutableStartPage.value = section
    }
}
