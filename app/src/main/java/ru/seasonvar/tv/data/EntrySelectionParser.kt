package ru.seasonvar.tv.data

import okhttp3.HttpUrl.Companion.toHttpUrl

object EntrySelectionParser {
    private val episode = Regex("""([0-9]+(?:\s*[-–]\s*[0-9]+)?)\s*сери[яиюй](?:\s*\(([^()]+)\))?""", RegexOption.IGNORE_CASE)
    fun text(value: String): EntrySelection {
        val matches = episode.findAll(value).toList()
        if (matches.size != 1) return EntrySelection()
        val match = matches.single()
        return EntrySelection(episodeLabel = match.groupValues[1].replace(Regex("\\s+"), ""),
            translationLabel = match.groupValues[2].trim().takeIf { it.isNotBlank() })
    }
    fun updates(values: List<String>): EntrySelection {
        if (values.isEmpty()) return EntrySelection()
        val hints = values.map(::text)
        return EntrySelection(
            episodeLabel = hints.map { it.episodeLabel }.distinct().singleOrNull(),
            translationLabel = hints.map { it.translationLabel }.distinct().singleOrNull(),
        )
    }
    fun markLink(raw: String): EntrySelection {
        val url = "https://seasonvar.ru/".toHttpUrl().resolve(raw) ?: return EntrySelection()
        if (url.scheme != "https" || url.host !in setOf("seasonvar.ru", "www.seasonvar.ru") || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return EntrySelection()
        val id = Regex("""^rewind=([0-9]+_seriya)(?:_|$)""").find(url.fragment.orEmpty())?.groupValues?.get(1)
        return EntrySelection(episodeId = id)
    }
}
