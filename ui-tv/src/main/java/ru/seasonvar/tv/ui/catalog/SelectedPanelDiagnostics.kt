package ru.seasonvar.tv.ui.catalog

import ru.seasonvar.tv.BuildConfig

internal object SelectedPanelDiagnostics {
    interface Observer {
        fun measured(cpuNanos: Long)
        fun textPrepared(cpuNanos: Long)
        fun aspectChanged()
    }

    @Volatile var observer: Observer? = null

    fun current(): Observer? = if (BuildConfig.BUILD_TYPE == "profile") observer else null
}
