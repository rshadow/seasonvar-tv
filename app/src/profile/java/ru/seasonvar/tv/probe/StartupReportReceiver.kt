package ru.seasonvar.tv.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
import ru.seasonvar.tv.diagnostics.StartupDiagnostics
import java.io.File

class StartupReportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val run = requireNotNull(intent.getStringExtra("run"))
        require(run.matches(Regex("[a-z0-9-]{1,80}")))
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val completed = withTimeoutOrNull(20_000) { StartupDiagnostics.ready.await(); true } ?: false
                val report = StartupDiagnostics.snapshot(!completed)
                val directory = File(checkNotNull(context.getExternalFilesDir(null)), "startup-profile/$run")
                directory.mkdirs()
                File(directory, "metrics.json").writeText(report.toString(2))
            } finally { pending.finish() }
        }
    }
}
