package ru.seasonvar.tv.data.account

import ru.seasonvar.tv.data.*

data class AccountProgressSnapshot(
    val markedSeasonIds: Set<String>,
    val markedSeasonNumbers: Map<String, Int>,
    val completedSeasonIds: Set<String>,
    val episodeCounters: Map<String, ProgressCounter>,
)

object AccountProgressParser {
    private val counter = Regex("""^(?:Отмечено\s*:?[\s]*)?([0-9]+)\s+из\s+([0-9]+)$""", RegexOption.IGNORE_CASE)
    fun episodes(text: String): ProgressCounter? {
        val match = counter.matchEntire(text.trim()) ?: return null
        val marked = match.groupValues[1].toIntOrNull() ?: return null
        val total = match.groupValues[2].toIntOrNull() ?: return null
        return if (total > 0 && marked in 0..total) ProgressCounter(marked, total) else null
    }
    fun snapshot(html: String): AccountProgressSnapshot {
        val entries = AccountParser.personalList(html, PersonalFeed.RESUME).entries +
            AccountParser.personalList(html, PersonalFeed.WATCHED).entries
        val counters = entries.groupBy { it.id }.mapNotNull { (id, rows) ->
            rows.mapNotNull { it.accountProgress?.episodes?.counter }.distinct().singleOrNull()?.let { id to it }
        }.toMap()
        val seasonNumbers = entries.groupBy { it.id }.mapNotNull { (id, rows) ->
            rows.mapNotNull { it.season }.distinct().singleOrNull()?.let { id to it }
        }.toMap()
        return AccountProgressSnapshot(entries.map { it.id }.toSet(), seasonNumbers,
            entries.filter { it.accountMark?.completedSeason == true }.map { it.id }.toSet(), counters)
    }
    fun forDetails(details: SeriesDetails, snapshot: AccountProgressSnapshot): AccountProgress {
        val seasonIds = details.seasons.map { it.id }.distinct()
        val selectedId = details.entry.id
        val seasons = snapshot.markedSeasonNumbers[selectedId]?.let { markedSeason ->
            seasonIds.takeIf { selectedId in it }?.let { ProgressCounter(markedSeason, it.size) }
        }
        val direct = snapshot.episodeCounters[selectedId]
        val available = if (details.playbackUnavailableReason != null) null else (details.translations.firstOrNull { it.id == "0" }
            ?: details.translations.firstOrNull { it.id == details.defaultTranslationId }
            ?: details.translations.firstOrNull())?.episodes?.size?.takeIf { it > 0 }
        val episodes = direct ?: when {
            available == null -> null
            selectedId in snapshot.completedSeasonIds -> ProgressCounter(available, available)
            selectedId !in snapshot.markedSeasonIds -> ProgressCounter(0, available)
            else -> null
        }
        return AccountProgress(seasons, episodes?.let { SeasonEpisodeProgress(selectedId, it) })
    }
}
