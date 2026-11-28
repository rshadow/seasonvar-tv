package ru.seasonvar.tv.ui.catalog

import ru.seasonvar.tv.BuildConfig

internal object SeriesListPlanningDiagnostics {
    interface Observer {
        fun update(superseded: Boolean)
        fun planned(cpuNanos: Long, wallNanos: Long, slots: Int, images: Int)
        fun applied(cpuNanos: Long)
    }

    @Volatile var observer: Observer? = null

    fun current(): Observer? = if (BuildConfig.BUILD_TYPE == "profile") observer else null
}
