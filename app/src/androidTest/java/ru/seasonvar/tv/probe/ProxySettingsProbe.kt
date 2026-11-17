package ru.seasonvar.tv.probe

import android.app.Instrumentation

object ProxySettingsProbe {
    fun run(probe: Instrumentation) {
        val ui = ProxySettingsProbeSession(probe)
        ui.execute { fixture ->
            ManualProxyChecks(ui, fixture).run()
            PacProxyChecks(ui, fixture).run()
            ui.pacPreview()
            with(ui.result) {
                putBoolean("pac_countdown", true)
                putBoolean("pac_same_layout", true)
                putBoolean("pac_modes", true)
                putBoolean("text_centered", true)
                putBoolean("fixture_only", true)
                putBoolean("script_success", true)
            }
        }
    }
}
