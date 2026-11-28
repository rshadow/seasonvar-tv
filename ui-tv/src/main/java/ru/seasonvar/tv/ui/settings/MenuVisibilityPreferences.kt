package ru.seasonvar.tv.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.seasonvar.tv.ui.navigation.Section

internal class MenuVisibilityPreferences(read: () -> Set<String>?, private val write: (Set<String>) -> Unit) {
    private val mutableHiddenSections = MutableStateFlow(
        read().orEmpty().let { stored -> Section.entries.filter { it.canHide && it.storageId in stored }.toSet() },
    )
    val hiddenSections = mutableHiddenSections.asStateFlow()

    fun setVisible(section: Section, visible: Boolean) {
        if (!section.canHide) return
        val updated = if (visible) mutableHiddenSections.value - section else mutableHiddenSections.value + section
        if (updated == mutableHiddenSections.value) return
        write(updated.map { it.storageId }.toSet())
        mutableHiddenSections.value = updated
    }
}
