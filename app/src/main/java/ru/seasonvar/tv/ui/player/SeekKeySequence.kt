package ru.seasonvar.tv.ui.player

internal fun acceleratedSeekStep(heldMs: Long, initialStepMs: Int, doublingIntervalMs: Int, maxStepMs: Int): Long {
    var step = initialStepMs.toLong().coerceAtMost(maxStepMs.toLong())
    var doublings = heldMs.coerceAtLeast(0) / doublingIntervalMs
    while (doublings > 0 && step < maxStepMs) {
        step = (step * 2).coerceAtMost(maxStepMs.toLong())
        doublings--
    }
    return step
}

internal class SeekKeySequence(private val pulseGapMs: Long, private val pulseDurationMs: Long) {
    private var key: Int? = null
    private var startedAt = 0L
    private var lastDownAt = 0L
    private var releasedAt: Long? = null
    private var repeated = false

    fun down(code: Int, time: Long, repeatCount: Int): Long {
        val release = releasedAt
        if (key != code || time < lastDownAt || (release != null && time - release !in 0..pulseGapMs)) {
            reset()
            startedAt = time
        }
        repeated = repeated || repeatCount > 0 || (key == code && releasedAt == null)
        key = code
        lastDownAt = time
        releasedAt = null
        return time - startedAt
    }

    fun up(code: Int, time: Long, canceled: Boolean) {
        if (key != code) return
        // Эмулятор передаёт автоповтор клавиатуры парами DOWN/UP длительностью меньше миллисекунды.
        if (canceled || repeated || time - lastDownAt !in 0..pulseDurationMs) reset()
        else releasedAt = time
    }

    fun reset() {
        key = null
        releasedAt = null
        repeated = false
    }
}
