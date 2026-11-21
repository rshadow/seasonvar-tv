package ru.seasonvar.tv.probe

import android.app.Instrumentation
import ru.seasonvar.tv.ui.settings.InterfaceTheme

internal object ListRefreshProbe {
    fun run(probe: Instrumentation, theme: InterfaceTheme) {
        val ui = ListRefreshProbeSession(probe)
        ui.execute { fixture ->
            ListStartupChecks(ui, fixture, theme).run()
            val lists = ListRefreshChecks(ui, fixture, theme)
            lists.render()
            val reference = lists.selectStrip()
            lists.refreshAndReturn(reference)
            lists.menuNavigation()
            lists.gridReturn()
            lists.activityVisibility()
            lists.removeMarks()
            with(ui.result) {
                putBoolean("list_visibility", true)
                putBoolean("background_refresh_preserved", true)
                putBoolean("single_refresh", true)
                putBoolean("network_prepared_first", true)
                putInt("refresh_dispatches", fixture.sections.sumOf { fixture.counts.getValue(it) })
                putBoolean("geometry_stable", true); putBoolean("focus_preserved", true)
                putBoolean("fixture_only", true); putBoolean("script_success", true)
            }
        }
    }
}
