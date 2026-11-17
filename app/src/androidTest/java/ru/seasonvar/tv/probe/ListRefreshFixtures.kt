package ru.seasonvar.tv.probe

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.network.NetworkConfiguration
import ru.seasonvar.tv.ui.catalog.*
import ru.seasonvar.tv.ui.details.SeriesDetailsViewModel
import ru.seasonvar.tv.ui.navigation.Section
import java.util.concurrent.atomic.AtomicReference

/** Локальные выдачи и барьеры; attach и close вызываются на главном потоке. */
internal class ListRefreshFixtures : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = ViewModelStore()
    val rows = (1..24).map { SeriesEntry(it.toString(), "https://seasonvar.ru/serial-$it-fixture.html",
        "Карточка ${it.toString().padStart(2, '0')}", 1, "", "android.resource://ru.seasonvar.tv/drawable/poster_placeholder",
        previewDescription = "Синтетическое описание для проверки фонового обновления.") }
    val sections = Section.entries.filter { it.publicFeed != null || it.personalFeed != null ||
        it == Section.SEARCH || it == Section.SMART_CONTINUE || it == Section.CATALOG }
    val pages = sections.associateWith { MutableStateFlow(CatalogUiState(loading = false)) }
    val visited = linkedSetOf<Section>()
    val counts = sections.associateWith { 0 }.toMutableMap()
    private val jobs = mutableMapOf<Section, Job>()
    val shown = AtomicReference<Section?>()
    val grid = mutableStateOf(false)
    val prepareGate = CompletableDeferred<Unit>()
    val responseGate = CompletableDeferred<Unit>()
    fun visit(section: Section) {
        if (visited.add(section)) pages.getValue(section).value = CatalogUiState(entries = rows, loaded = true, loading = false)
    }
    val coordinator = ListRefreshCoordinator(scope, NetworkConfiguration(), { prepareGate.await() }, sections.associateWith { section ->
        ListRefreshCoordinator.Target({ jobs.remove(section)?.cancel() }, {
            if (section in visited) {
                counts[section] = counts.getValue(section) + 1
                val state = pages.getValue(section)
                state.value = state.value.copy(loading = true)
                jobs[section] = scope.launch {
                    responseGate.await()
                    state.value = state.value.copy(loading = false, entries = rows.map {
                        it.copy(previewDescription = "Обновлённое описание для проверки фонового обновления.") })
                }
            }
        })
    })
    private val details = SeriesDetails(rows.first(), "100", listOf("Синтетическое описание."), emptyList(), emptyList(),
        listOf(SeasonOption("1", rows.first().url, "Сезон 1", 1)),
        listOf(Translation("0", "Стандартный", listOf(Episode("1", "1")))), "0")
    val model = SeriesDetailsViewModel { entry -> details.copy(entry = entry) }

    fun attach() { store.put("details", model) }
    override fun close() { try { store.clear() } finally { scope.cancel() } }
}
