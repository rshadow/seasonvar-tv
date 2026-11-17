package ru.seasonvar.tv.probe

import android.app.Instrumentation

object PosterBackdropProbe {
    /** Запускает полный прогон в эмуляторе и завершает Instrumentation с результатами для CLI. */
    fun run(probe: Instrumentation) {
        val session = BackdropProbeSession(probe)
        val themes = ThemeAppearanceChecks(session)
        val backdrop = PosterBackdropChecks(session)
        session.execute { fixtures ->
            themes.contract()
            backdrop.cache(fixtures)
            session.openActivity()
            themes.configuredColors()
            themes.focusDependencies()
            themes.parameterCache()
            themes.nativePlayerColors()
            backdrop.details(fixtures)
            ListBackdropChecks(session, fixtures.rows).run()
            backdrop.loading(fixtures)
            backdrop.menuFallback()
            themes.selection()
        }
    }
}
