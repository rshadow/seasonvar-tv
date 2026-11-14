package ru.seasonvar.tv.ui.details

import android.os.SystemClock
import android.util.Log
import ru.seasonvar.tv.BuildConfig
import ru.seasonvar.tv.ui.settings.DetailsAppearance

internal object DetailsPerformance {
    private var openedAt = 0L

    fun opened() {
        if (BuildConfig.BUILD_TYPE == "profile") openedAt = SystemClock.elapsedRealtimeNanos()
    }

    fun ready(appearance: DetailsAppearance) {
        if (BuildConfig.BUILD_TYPE != "profile" || openedAt == 0L) return
        Log.i("SeasonvarDetailsTiming", "ready_us=" + (SystemClock.elapsedRealtimeNanos() - openedAt) / 1000 + " appearance=" + appearance.storageId)
        openedAt = 0
    }
}
