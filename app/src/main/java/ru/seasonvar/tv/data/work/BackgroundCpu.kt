package ru.seasonvar.tv.data.work

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import ru.seasonvar.tv.data.settings.CacheSettings
import ru.seasonvar.tv.BuildConfig

internal object BackgroundCpu {
    private val processors = Runtime.getRuntime().availableProcessors()
    val parallelism = CacheSettings.cpuParallelism(processors)
    private val adaptive = createDispatcher(processors)
    @Volatile var profileDispatcher: CoroutineDispatcher? = null
        set(value) { check(BuildConfig.BUILD_TYPE == "profile"); field = value }
    val dispatcher: CoroutineDispatcher get() = if (BuildConfig.BUILD_TYPE == "profile") profileDispatcher ?: adaptive else adaptive
    // Ограниченный IO-view оставляет логические рабочие места Default для видимого экрана.
    fun createDispatcher(processors: Int, base: CoroutineDispatcher = Dispatchers.IO): CoroutineDispatcher =
        base.limitedParallelism(CacheSettings.cpuParallelism(processors), "Content CPU")
    suspend fun <T> run(block: () -> T): T = withContext(dispatcher) { block() }
}
