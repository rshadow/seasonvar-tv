package ru.seasonvar.tv.ui.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.seasonvar.tv.ui.navigation.Section

internal class InterfaceSettings(context: Context) {
    private val preferences = context.getSharedPreferences("interface", Context.MODE_PRIVATE)
    private val mutableStartPage = MutableStateFlow(Section.fromStorage(preferences.getString("start_page", null)))
    val startPage = mutableStartPage.asStateFlow()

    fun setStartPage(section: Section) {
        require(section in Section.startPages(true))
        preferences.edit().putString("start_page", section.storageId).apply()
        mutableStartPage.value = section
    }
}
