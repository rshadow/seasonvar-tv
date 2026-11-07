package ru.seasonvar.tv.data.home

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.*
import ru.seasonvar.tv.SeasonvarApplication

class HomeRefreshJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            val retry = try {
                val home = (application as SeasonvarApplication).homeChannels
                home.refresh()
                home.failure.value
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { true }
            jobFinished(params, retry)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
