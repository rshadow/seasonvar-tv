package ru.seasonvar.tv.ui.navigation

internal data class MenuOrder(val keys: List<String> = emptyList()) {
    fun groups(): List<Pair<MenuGroup, List<Section>>> {
        val ranks = keys.withIndex().associate { it.value to it.index }
        fun rank(key: String) = ranks[key] ?: Int.MAX_VALUE
        val movable = Section.defaultMenuGroups.filter { it.first != MenuGroup.SYSTEM }
            .sortedBy { rank(it.first.storageId) }
            .map { (group, sections) -> group to sections.sortedBy { rank(it.storageId) } }
        return movable + (MenuGroup.SYSTEM to Section.systemPages)
    }

    fun move(key: String, offset: Int): MenuOrder {
        require(offset == -1 || offset == 1)
        val groups = groups()
        val groupIndex = groups.indexOfFirst { it.first.storageId == key }
        if (groupIndex >= 0) {
            val target = groupIndex + offset
            if (groupIndex == groups.lastIndex || target !in 0 until groups.lastIndex) return this
            val updated = groups.toMutableList()
            updated.add(target, updated.removeAt(groupIndex))
            return fromGroups(updated)
        }
        val owner = groups.firstOrNull { (_, sections) -> sections.any { it.storageId == key } } ?: return this
        if (owner.first == MenuGroup.SYSTEM) return this
        val sections = owner.second.toMutableList()
        val index = sections.indexOfFirst { it.storageId == key }
        val target = index + offset
        if (target !in sections.indices) return this
        sections.add(target, sections.removeAt(index))
        return fromGroups(groups.map { if (it.first == owner.first) it.first to sections else it })
    }

    private fun fromGroups(groups: List<Pair<MenuGroup, List<Section>>>) =
        MenuOrder(groups.flatMap { (group, sections) -> listOf(group.storageId) + sections.map(Section::storageId) })
}
