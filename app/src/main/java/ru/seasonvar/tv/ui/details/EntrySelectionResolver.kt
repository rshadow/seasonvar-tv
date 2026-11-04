package ru.seasonvar.tv.ui.details

import ru.seasonvar.tv.data.*
import java.util.Locale

data class SelectionFailure(val requested: String)
data class ResolvedSelection(val translationId: String?, val episodeId: String?, val failure: SelectionFailure? = null)

internal object EntrySelectionResolver {
    private fun name(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    private fun episodeName(value: String): String {
        val text = name(value).replace(Regex("\\s*[-–]\\s*"), "-")
        return if (text.matches(Regex("[0-9]+"))) text.trimStart('0').ifEmpty { "0" } else text
    }
    private fun episode(translation: Translation, hint: EntrySelection): Episode? {
        hint.episodeId?.let { id -> translation.episodes?.singleOrNull { it.id == id }?.let { return it } }
        val label = hint.episodeLabel ?: return null
        return translation.episodes?.filter { episodeName(it.label) == episodeName(label) }?.singleOrNull()
    }
    fun validResume(point: ResumePoint?, details: SeriesDetails) = point?.takeIf { p ->
        p.seasonId == details.entry.id && p.positionMillis > 0 && details.translations.any { t ->
            t.id == p.translationId && t.episodes?.any { it.id == p.episodeId } == true
        }
    }
    fun standard(details: SeriesDetails): Translation? =
        details.translations.firstOrNull { it.id == "0" }
            ?: details.translations.firstOrNull { name(it.label) in setOf("стандартная", "стандартный", "по умолчанию") }
            ?: details.translations.firstOrNull { it.id == details.defaultTranslationId }
            ?: details.translations.firstOrNull()

    fun resolve(details: SeriesDetails, hint: EntrySelection, point: ResumePoint?, preferred: List<String> = emptyList()): ResolvedSelection {
        val translations = details.translations
        if (translations.isEmpty() || details.playbackUnavailableReason != null) return ResolvedSelection(null, null)
        val resume = validResume(point, details)
        val preferences = preferred.mapNotNull { value ->
            translations.filter { name(it.label) == TranslationPriorities.name(value) }.singleOrNull()
        }.distinctBy { it.id }
        val ordered = (listOfNotNull(translations.firstOrNull { it.id == resume?.translationId }) +
            preferences + listOfNotNull(standard(details), translations.firstOrNull { it.id == details.defaultTranslationId }) +
            translations).distinctBy { it.id }
        val explicit = if (hint.hasTranslation) {
            hint.translationId?.let { id -> translations.singleOrNull { it.id == id } }
                ?: hint.translationLabel?.let { label -> translations.filter { name(it.label) == name(label) }.singleOrNull() }
                ?: standard(details)
        } else null
        val translation = explicit ?: if (hint.hasEpisode) ordered.firstOrNull { episode(it, hint) != null } ?: ordered.first()
            else ordered.first()
        val selected = if (hint.hasEpisode) episode(translation, hint)
            else translation.episodes?.firstOrNull { translation.id == resume?.translationId && it.id == resume.episodeId }
                ?: translation.episodes?.firstOrNull()
        val failure = if (hint.hasEpisode && selected == null && translation.episodes != null) SelectionFailure(
            hint.episodeLabel ?: hint.episodeId.orEmpty()) else null
        return ResolvedSelection(translation.id, selected?.id, failure)
    }
}
