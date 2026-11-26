package ru.seasonvar.tv.probe

import android.app.Instrumentation
import ru.seasonvar.tv.ui.settings.InterfaceTheme

object UiScreensLayoutProbe {
    internal fun run(probe: Instrumentation, dialogsOnly: Boolean = false, theme: InterfaceTheme = InterfaceTheme.CLASSIC) {
        val ui = UiScreensProbeSession(probe, theme)
        ui.execute { fixtures ->
            val screens = UiScreenChecks(ui, fixtures)
            screens.sessionStorageChoice()
            screens.seasonComments()
            screens.writeSeasonComments()
            screens.detailsNavigation()
            if (!dialogsOnly) {
                screens.catalogsAndSearch()
                screens.accountStates()
            }
            screens.settingsDialogs()
            screens.settingsNavigation()
            screens.aboutAndUpdate()
            screens.filterDialogs()
            screens.detailsDialogs()
            with(ui.result) {
                putBoolean("screens_verified", !dialogsOnly); putBoolean("dialogs_verified", true)
                putBoolean("text_centered", true); putBoolean("fixture_only", true); putBoolean("script_success", true)
            }
        }
    }
}
