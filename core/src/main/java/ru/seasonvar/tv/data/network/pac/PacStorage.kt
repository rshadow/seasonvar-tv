package ru.seasonvar.tv.data.network.pac

import android.content.Context
import android.util.AtomicFile
import ru.seasonvar.tv.data.settings.PacRefreshInterval
import ru.seasonvar.tv.data.settings.PacSettings
import java.io.File

interface PacStorage {
    val file: File
    val staging: File
    var fetchedAt: Long
    var attemptedAt: Long
    var interval: PacRefreshInterval
    fun commit(at: Long)
}

class PreferencesPacStorage(context: Context, source: String, cooldownKey: String = source) : PacStorage {
    private val preferences = context.getSharedPreferences("pac-" + source, Context.MODE_PRIVATE)
    private val attempts = context.getSharedPreferences("pac-download-attempts", Context.MODE_PRIVATE)
    private val attemptKey = cooldownKey
    private val directory = File(context.filesDir, "network").apply { mkdirs() }
    override val file = File(directory, "pac-$source.js")
    override val staging = File(directory, "pac-$source-staging.js")
    override var fetchedAt: Long
        get() = preferences.getLong("fetched_at", 0)
        set(value) { if (!preferences.edit().putLong("fetched_at", value).commit()) throw PacException(PacFailure.STORAGE) }
    override var attemptedAt: Long
        get() = attempts.getLong(attemptKey, 0)
        set(value) { if (!attempts.edit().putLong(attemptKey, value).commit()) throw PacException(PacFailure.STORAGE) }
    override var interval: PacRefreshInterval
        get() = PacRefreshInterval.fromHours(preferences.getInt("interval_hours", PacRefreshInterval.DAY.hours))
        set(value) { if (!preferences.edit().putInt("interval_hours", value.hours).commit()) throw PacException(PacFailure.STORAGE) }

    override fun commit(at: Long) {
        if (staging.length() !in 1..PacSettings.MAX_BYTES.toLong()) throw PacException(PacFailure.FORMAT)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            staging.inputStream().use { it.copyTo(output) }
            atomic.finishWrite(output)
            fetchedAt = at
        } catch (error: Exception) { atomic.failWrite(output); throw error }
    }
}
