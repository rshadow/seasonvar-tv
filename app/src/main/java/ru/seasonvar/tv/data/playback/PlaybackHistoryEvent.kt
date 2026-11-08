package ru.seasonvar.tv.data.playback

import ru.seasonvar.tv.data.SeasonvarParser
import java.net.URI

data class PlaybackHistoryEvent(
    val seasonId: String,
    val pageUrl: String,
    val serialId: String,
    val contentId: String,
    val translationId: String,
    val playlistId: String,
) {
    init {
        require(listOf(seasonId, serialId, contentId).all { it.matches(Regex("[1-9][0-9]*")) })
        require(SeasonvarParser.pageUrl(pageUrl) == pageUrl)
        require(Regex("/serial-([0-9]+)").find(URI(pageUrl).path)?.groupValues?.get(1) == seasonId)
        require(translationId.matches(Regex("[0-9]+")))
        require(playlistId.isNotBlank() && playlistId.length <= 80 && playlistId.none { it.isISOControl() })
    }

    fun form(cookieNameIndex: Int): Map<String, String> = linkedMapOf(
        "id_season" to seasonId, "file_id" to contentId, "serial_id" to serialId,
        "svid1" to cookieNameIndex.toString(), "idTrans" to translationId,
        "page" to "", "numFile" to playlistId,
    )
}

class PlaybackHistoryStart(var submitted: Boolean = false) {
    private var frameRendered = false
    fun resetSurface() { frameRendered = false }
    fun firstFrame(isPlaying: Boolean): Boolean { frameRendered = true; return playing(isPlaying) }
    fun playing(isPlaying: Boolean): Boolean {
        if (submitted || !frameRendered || !isPlaying) return false
        submitted = true
        return true
    }
}
