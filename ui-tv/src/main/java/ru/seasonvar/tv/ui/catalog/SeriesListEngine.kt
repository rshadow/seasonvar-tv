package ru.seasonvar.tv.ui.catalog

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.SeriesDescription
import ru.seasonvar.tv.data.SeriesListWindow
import ru.seasonvar.tv.data.seriesListWindow
import ru.seasonvar.tv.data.images.*

internal data class SeriesCardAppearance(val seasonId: String, val title: String, val subtitle: String, val posterUrl: String,
    val isNew: Boolean, val isWatching: Boolean)

internal data class SeriesCardContent(val entry: SeriesEntry, val subtitle: String) {
    val appearance = SeriesCardAppearance(entry.id, entry.title, subtitle, entry.posterUrl, entry.isNew, entry.isWatching)
}

internal data class SeriesListInput(
    val catalogKey: String,
    val entries: List<SeriesEntry>,
    val selectedKey: String?,
    val pageSize: Int,
    val visible: IntRange,
    val prefetchEnabled: Boolean,
    val thumbnailWidth: Int,
    val thumbnailHeight: Int,
    val selectedPosterVisible: Boolean,
    val descriptionsEnabled: Boolean = true,
    val resourceContext: Any? = null,
)

internal class SeriesListEngine(
    private val scope: CoroutineScope,
    private val prepare: suspend (SeriesEntry) -> SeriesCardContent,
    loadImage: suspend (PrefetchImage) -> Unit,
    permits: Semaphore,
    private val nextFrame: suspend () -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val loadDescription: (suspend (SeriesEntry) -> SeriesDescription)? = null,
    private val cachedDescription: (SeriesEntry) -> SeriesDescription? = { null },
    private val retainData: (Set<String>) -> Unit = {},
) {
    private data class Slot(val index: Int, val key: String, val entry: SeriesEntry)
    private data class Plan(val window: SeriesListWindow, val slots: List<Slot>, val images: List<PrefetchImage>)
    private data class Preparation(val token: Long, val slot: Slot)
    private data class Prepared(val source: SeriesEntry, val content: SeriesCardContent)
    private sealed interface Resource {
        val key: String
        data class Image(val image: PrefetchImage) : Resource { override val key = "image|" + image.key }
        data class Description(val token: Long, val source: String, val entry: SeriesEntry) : Resource {
            override val key = "description|" + token
        }
    }
    private val described = mutableStateMapOf<String, DescriptionState>()
    private var desiredDescriptions = emptyMap<String, Resource.Description>()
    private var selectedDescription: String? = null
    private val resources = WindowPrefetcher(scope, Resource::key, { task: Resource ->
        when (task) {
            is Resource.Image -> loadImage(task.image)
            is Resource.Description -> {
                val state = try {
                    DescriptionState(description = withContext(dispatcher) { checkNotNull(loadDescription)(task.entry) })
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { DescriptionState(failed = true) }
                currentCoroutineContext().ensureActive()
                if (desiredDescriptions[task.source]?.token == task.token) described[task.source] = state
            }
        }
    }, permits = permits)
    private val prepared = mutableStateMapOf<String, Prepared>()
    private var desired = emptyMap<String, Preparation>()
    private var nextToken = 0L
    private val cards = WindowPrefetcher(scope, { it: Preparation -> it.token.toString() }, { task ->
        val content = withContext(dispatcher) { prepare(task.slot.entry) }
        nextFrame()
        currentCoroutineContext().ensureActive()
        if (desired[task.slot.key]?.token == task.token) prepared[task.slot.key] = Prepared(task.slot.entry, content)
    }, parallelism = 1)
    private var knownIndices = emptyMap<String, Int>()
    private var catalogKey: String? = null
    private var resourceContext: Any? = null
    private var work: Job? = null
    private var generation = 0L
    internal var window = SeriesListWindow(IntRange.EMPTY, IntRange.EMPTY)
        private set
    internal val preparedCount get() = prepared.size
    internal val descriptionCount get() = described.size

    fun content(key: String, entry: SeriesEntry): SeriesCardContent? =
        prepared[key]?.takeIf { it.source == entry }?.content

    fun appearance(key: String): SeriesCardAppearance? = prepared[key]?.content?.appearance

    fun description(entry: SeriesEntry): DescriptionState =
        cachedDescription(entry)?.let { DescriptionState(entry.key, it) }
            ?: described[descriptionKey(entry)]?.copy(key = entry.key)
            ?: DescriptionState(key = entry.key, loading = loadDescription != null)

    private fun descriptionKey(entry: SeriesEntry) = entry.id + "|" + entry.url

    fun update(input: SeriesListInput) {
        val observer = SeriesListPlanningDiagnostics.current()
        observer?.update(work?.isActive == true)
        work?.cancel()
        val version = ++generation
        if (catalogKey != input.catalogKey) {
            cards.clear()
            desired = emptyMap()
            prepared.clear()
            knownIndices = emptyMap()
        }
        if (catalogKey != input.catalogKey || resourceContext != input.resourceContext) {
            clearDescriptions()
            resources.clear()
            catalogKey = input.catalogKey
            resourceContext = input.resourceContext
        }
        val known = knownIndices
        work = scope.launch {
            val plan = withContext(dispatcher) {
                if (observer == null) plan(input, known)
                else {
                    val cpu = android.os.Debug.threadCpuTimeNanos()
                    val wall = System.nanoTime()
                    plan(input, known).also {
                        val elapsed = System.nanoTime() - wall
                        val usedCpu = android.os.Debug.threadCpuTimeNanos() - cpu
                        observer.planned(usedCpu, elapsed, it.slots.size, it.images.size)
                    }
                }
            }
            ensureActive()
            if (version != generation) return@launch
            val applyCpu = if (observer != null) android.os.Debug.threadCpuTimeNanos() else 0L
            window = plan.window
            retainData(plan.slots.mapTo(hashSetOf()) { it.entry.id })
            knownIndices = plan.slots.associate { it.entry.key to it.index }
            val previous = desired
            desired = plan.slots.associate { slot ->
                slot.key to (previous[slot.key]?.takeIf { it.slot.entry == slot.entry }
                    ?: Preparation(++nextToken, slot))
            }
            prepared.keys.toList().forEach { key -> if (key !in desired) prepared.remove(key) }
            updateResources(plan, input.selectedKey, input.descriptionsEnabled)
            cards.update(desired.values.filter { prepared[it.slot.key]?.source != it.slot.entry })
            observer?.applied(android.os.Debug.threadCpuTimeNanos() - applyCpu)
        }
    }

    private fun updateResources(plan: Plan, selectedKey: String?, descriptionsEnabled: Boolean) {
        val previous = desiredDescriptions
        val selected = plan.slots.firstOrNull { it.entry.key == selectedKey }?.entry?.let(::descriptionKey)
        val retry = selected?.takeIf { it != selectedDescription && described[it]?.failed == true }
        if (retry != null) described.remove(retry)
        selectedDescription = selected
        desiredDescriptions = if (!descriptionsEnabled || loadDescription == null) emptyMap() else plan.slots.associate { slot ->
            val source = descriptionKey(slot.entry)
            source to (previous[source]?.takeIf { source != retry }
                ?: Resource.Description(++nextToken, source, slot.entry))
        }
        described.keys.toList().forEach { if (it !in desiredDescriptions) described.remove(it) }
        val pending = desiredDescriptions.filterKeys { it !in described }
        val imagesByUrl = plan.images.groupBy { it.url }
        val ordered = buildList {
            for (slot in plan.slots) if (slot.index in plan.window.visible || slot.entry.key == selectedKey) {
                pending[descriptionKey(slot.entry)]?.let(::add)
            }
            for (slot in plan.slots) {
                pending[descriptionKey(slot.entry)]?.let(::add)
                for (url in listOfNotNull(slot.entry.posterUrl, slot.entry.largePosterUrl))
                    imagesByUrl[url]?.forEach { add(Resource.Image(it)) }
            }
        }
        resources.update(ordered)
    }

    private fun clearDescriptions() {
        described.clear()
        desiredDescriptions = emptyMap()
        selectedDescription = null
    }

    private suspend fun plan(input: SeriesListInput, known: Map<String, Int>): Plan {
        var selected = known[input.selectedKey]?.takeIf { input.entries.getOrNull(it)?.key == input.selectedKey } ?: -1
        if (selected < 0 && input.selectedKey != null) {
            for (index in input.entries.indices) {
                if (index % 256 == 0) currentCoroutineContext().ensureActive()
                if (input.entries[index].key == input.selectedKey) { selected = index; break }
            }
        }
        val window = seriesListWindow(input.entries.size, selected, input.pageSize, input.visible, input.prefetchEnabled)
        val slots = window.preparationOrder(selected).map { index ->
            val entry = input.entries[index]
            Slot(index, input.catalogKey + "|" + entry.key, entry)
        }
        return Plan(window, slots, imagePrefetchPlan(input.entries, selected, window,
            input.thumbnailWidth, input.thumbnailHeight, input.selectedPosterVisible))
    }

    fun clear() {
        retainData(emptySet())
        generation++
        work?.cancel()
        work = null
        resources.clear()
        clearDescriptions()
        cards.clear()
        desired = emptyMap()
        prepared.clear()
        knownIndices = emptyMap()
        window = SeriesListWindow(IntRange.EMPTY, IntRange.EMPTY)
    }
}
