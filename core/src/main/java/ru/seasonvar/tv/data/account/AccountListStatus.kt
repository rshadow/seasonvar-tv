package ru.seasonvar.tv.data.account

import ru.seasonvar.tv.data.EntrySelection
import ru.seasonvar.tv.data.MarkupChangedException
import ru.seasonvar.tv.data.SeriesDetails

enum class AccountListStatus(val code: Int?, val action: String) {
    WANT_TO_WATCH(-1, "wanttosee"), WATCHING(null, "pauseadd"), WATCHED(-2, "watched"), NOT_WATCHING(-3, "notWatched");

    companion object {
        fun from(snapshot: PlaybackMarkSnapshot): AccountListStatus? =
            if (snapshot.mark != null) WATCHING else entries.firstOrNull { it.code != null && it.code == snapshot.status }
    }
}

data class AccountStatusChange(val target: AccountListMark, val status: AccountListStatus, val watchingMark: PlaybackMark? = null) {
    init {
        require((status == AccountListStatus.WATCHING) == (watchingMark != null))
        require(watchingMark == null || watchingMark.seasonId == target.seasonId && watchingMark.pageUrl == target.pageUrl)
    }
    fun form(): Map<String, String> = watchingMark?.form() ?: linkedMapOf(
        "id" to target.seasonId, "minute" to "0", "second" to "0", "seria" to checkNotNull(status.code).toString(), status.action to "true",
    )

    companion object {
        fun watchingMark(details: SeriesDetails, hint: EntrySelection): PlaybackMark {
            val available = details.translations.filter { it.id != "68" && !it.episodes.isNullOrEmpty() }
            val translation = available.firstOrNull { it.id == hint.translationId }
                ?: available.firstOrNull { it.id == "0" } ?: available.firstOrNull() ?: throw MarkupChangedException()
            val episodes = checkNotNull(translation.episodes)
            val episode = if (hint.hasEpisode) (episodes.firstOrNull { it.id == hint.episodeId }
                ?: episodes.firstOrNull { it.label == hint.episodeLabel }) ?: throw MarkupChangedException() else episodes.first()
            return PlaybackMark(details.entry.id, details.entry.url, episode.label, translation.id, 0)
        }
    }
}
