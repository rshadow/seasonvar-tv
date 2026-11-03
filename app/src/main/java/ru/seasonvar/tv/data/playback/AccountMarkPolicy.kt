package ru.seasonvar.tv.data.playback

import ru.seasonvar.tv.data.account.PlaybackMark

enum class MarkEvent { SAMPLE, PAUSE, STOP, ENDED }

data class PlaybackSample(
    val positionMillis: Long,
    val durationMillis: Long,
    val playing: Boolean,
    val nowMillis: Long,
)

/** Generates account updates; it never controls playback or pauses for networking. */
class AccountMarkPolicy(
    private val request: PlaybackRequest,
    private val intervalMillis: Long = 60_000,
    private val minimumPlayedMillis: Long = 30_000,
    private val watchedPercent: Int = 90,
) {
    private var previousTime: Long? = null
    private var previouslyPlaying = false
    private var playedMillis = 0L
    private var lastSentAt: Long? = null
    private var completed = false
    private var lastMark: PlaybackMark? = null

    fun sample(value: PlaybackSample, event: MarkEvent = MarkEvent.SAMPLE): PlaybackMark? {
        val elapsed = previousTime?.let { (value.nowMillis - it).coerceAtLeast(0) } ?: 0
        if (previouslyPlaying) playedMillis += elapsed
        previousTime = value.nowMillis
        previouslyPlaying = value.playing
        if (lastSentAt == null) lastSentAt = value.nowMillis
        if (completed || request.translationId == "68" || value.positionMillis <= 0) return null
        val minimum = if (value.durationMillis > 0) {
            minOf(minimumPlayedMillis, value.durationMillis / 100 * watchedPercent)
        } else minimumPlayedMillis
        if (playedMillis < minimum) return null
        val forced = event in setOf(MarkEvent.PAUSE, MarkEvent.STOP)
        val watched = event == MarkEvent.ENDED || ((value.playing || forced) && value.durationMillis > 0 &&
            value.positionMillis.toDouble() / value.durationMillis >= watchedPercent / 100.0)
        if (!watched && !forced && (!value.playing || value.nowMillis - lastSentAt!! < intervalMillis)) return null
        val seconds = if (watched) 0 else value.positionMillis / 1000
        if (!watched && seconds <= 0) return null
        val mark = runCatching { PlaybackMark(request.seasonId, request.pageUrl, request.episodeLabel,
            request.translationId, seconds) }.getOrNull() ?: return null
        if (mark == lastMark) return null
        if (watched) completed = true
        lastSentAt = value.nowMillis
        lastMark = mark
        return mark
    }
}
