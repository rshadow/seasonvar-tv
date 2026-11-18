package ru.seasonvar.tv.ui.catalog

import androidx.compose.runtime.staticCompositionLocalOf
import ru.seasonvar.tv.BuildConfig

internal val LocalThumbnailObserver = staticCompositionLocalOf<((String, Boolean) -> Unit)?> { null }

internal object CatalogNavigationDiagnostics {
    interface Observer {
        fun focused(key: String, title: String, atNanos: Long)
        fun drawn(key: String, atNanos: Long)
    }
    @Volatile var observer: Observer? = null
    fun current(): Observer? = if (BuildConfig.BUILD_TYPE == "profile") observer else null
}
