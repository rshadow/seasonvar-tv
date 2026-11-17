package ru.seasonvar.tv.data.cache

import ru.seasonvar.tv.BuildConfig

internal object CardCacheDiagnostics {
    fun interface Observer { fun subscription(opened: Boolean, cpuNanos: Long) }
    @Volatile var observer: Observer? = null
    fun current(): Observer? = if (BuildConfig.BUILD_TYPE == "profile") observer else null
}
