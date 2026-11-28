package ru.seasonvar.tv.data.cache

import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.*

data class CardMarkChange(val entry: SeriesEntry, val snapshot: PlaybackMarkSnapshot, val revision: Long,
    val scope: CacheScope? = null, val episodeTotal: Int? = null) {
    val status get() = AccountListStatus.from(snapshot)

    fun apply(source: SeriesEntry, details: SeriesDetails? = null): SeriesEntry {
        if (source.id != entry.id) return source
        val previous = source.accountProgress?.episodes?.takeIf { it.seasonId == source.id }?.counter
        val episodes = details?.translations?.firstOrNull { it.id == snapshot.mark?.translationId }?.episodes
            ?: details?.translations?.firstOrNull { it.id == "0" }?.episodes
        val total = episodes?.size?.takeIf { it > 0 } ?: episodeTotal ?: previous?.total
        val marked = when {
            status == AccountListStatus.WATCHED -> total
            snapshot.mark != null -> snapshot.mark.episode.toIntOrNull()?.takeIf { total != null && it in 0..total }
            else -> null
        }
        val progress = if (marked != null && total != null) AccountProgress(
            seasons = source.accountProgress?.seasons,
            episodes = SeasonEpisodeProgress(source.id, ProgressCounter(marked, total))) else null
        val selection = if (source.sourceKey?.startsWith("resume:") == true || source.sourceKey?.startsWith("local-mark:") == true)
            snapshot.mark?.let { EntrySelection(episodeLabel = it.episode, translationId = it.translationId,
                positionMillis = it.positionSeconds * 1000) } ?: EntrySelection() else source.initialSelection
        return source.copy(accountMark = null, accountProgress = progress, initialSelection = selection,
            accountListMark = status?.let { AccountListMark(source.id, source.url) },
            isWatching = status == AccountListStatus.WATCHING)
    }

    fun belongsTo(feed: PersonalFeed, source: SeriesEntry): Boolean? = when (feed) {
        PersonalFeed.HISTORY -> true
        PersonalFeed.WANT -> status == AccountListStatus.WANT_TO_WATCH
        PersonalFeed.WATCHED -> status == AccountListStatus.WATCHED
        PersonalFeed.RESUME -> status == AccountListStatus.WATCHING
        PersonalFeed.UPDATES, PersonalFeed.NO_UPDATES -> if (status != AccountListStatus.WATCHING) false else
            apply(source).accountProgress?.episodes?.counter?.let {
                (it.marked < it.total) == (feed == PersonalFeed.UPDATES)
            }
    }

    fun continuation(source: SeriesEntry, details: SeriesDetails?): SeriesEntry? {
        val point = snapshot.mark ?: return null
        val episodes = details?.translations?.firstOrNull { it.id == point.translationId }?.episodes
        val label = if (point.positionSeconds > 0) point.episode else {
            val index = episodes?.indexOfFirst { it.label == point.episode } ?: -1
            if (index < 0) return null
            episodes?.getOrNull(index + 1)?.label ?: return null
        }
        return apply(source, details).copy(
            initialSelection = EntrySelection(episodeLabel = label, translationId = point.translationId,
                positionMillis = point.positionSeconds * 1000),
            update = listOfNotNull(source.season?.let { "Сезон $it" }, "Серия $label").joinToString(" · "))
    }
}
