package ru.seasonvar.tv.ui.catalog

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.seasonvar.tv.ui.navigation.Section

internal class CatalogLayoutPreferences(read: (String) -> String?, private val write: (String, String) -> Unit) {
    private val sections = Section.entries.filter { it == Section.SEARCH || it == Section.SMART_CONTINUE || it.publicFeed != null || it.personalFeed != null }
    private fun key(section: Section) = "catalog_layout." + section.storageId
    private val mutableLayouts = MutableStateFlow(sections.associateWith { CatalogLayout.fromStorage(read(key(it))) })
    val layouts = mutableLayouts.asStateFlow()
    private val mutableActorLayout = MutableStateFlow(CatalogLayout.fromStorage(read(ACTOR_LAYOUT_KEY)))
    val actorLayout = mutableActorLayout.asStateFlow()

    fun set(section: Section, layout: CatalogLayout) {
        require(section in sections)
        write(key(section), layout.storageId)
        mutableLayouts.value = mutableLayouts.value + (section to layout)
    }

    fun setActorLayout(layout: CatalogLayout) {
        write(ACTOR_LAYOUT_KEY, layout.storageId)
        mutableActorLayout.value = layout
    }

    private companion object {
        const val ACTOR_LAYOUT_KEY = "catalog_layout.actor"
    }
}
