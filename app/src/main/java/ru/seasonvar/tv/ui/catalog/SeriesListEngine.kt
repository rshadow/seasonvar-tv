package ru.seasonvar.tv.ui.catalog

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.SeriesDescription
import ru.seasonvar.tv.data.SeriesListWindow
import ru.seasonvar.tv.data.seriesListWindow
import ru.seasonvar.tv.data.images.*

internal data class SeriesCardContent(val entry: SeriesEntry, val subtitle: String)

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
)

internal class SeriesListEngine(
    private val scope: CoroutineScope,
    private val prepare: suspend (SeriesEntry) -> SeriesCardContent,
    loadImage: suspend (PrefetchImage) -> Unit,
    permits: Semaphore,
    private val nextFrame: suspend () -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val loadDescription: (suspend (SeriesEntry) -> SeriesDescription)? = null,
) {
    private data class Slot(val index: Int, val key: String, val entry: SeriesEntry)
    private data class Plan(val window: SeriesListWindow, val slots: List<Slot>, val images: List<PrefetchImage>)
    private data class Preparation(val token: Long, val slot: Slot)
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
    private val prepared = mutableStateMapOf<String, SeriesCardContent>()
    private var desired = emptyMap<String, Preparation>()
    private var nextToken = 0L
    private val cards = WindowPrefetcher(scope, { it: Preparation -> it.token.toString() }, { task ->
        val content = withContext(dispatcher) { prepare(task.slot.entry) }
        nextFrame()
        currentCoroutineContext().ensureActive()
        if (desired[task.slot.key]?.token == task.token) prepared[task.slot.key] = content
    }, parallelism = 1)
    private var knownIndices = emptyMap<String, Int>()
    private var catalogKey: String? = null
    private var work: Job? = null
    private var generation = 0L
    internal var window = SeriesListWindow(IntRange.EMPTY, IntRange.EMPTY)
        private set
    internal val preparedCount get() = prepared.size
    internal val descriptionCount get() = described.size

    fun content(key: String, entry: SeriesEntry): SeriesCardContent? =
        prepared[key]?.takeIf { it.entry == entry }

    fun description(entry: SeriesEntry): DescriptionState =
        described[descriptionKey(entry)]?.copy(key = entry.key)
            ?: DescriptionState(key = entry.key, loading = loadDescription != null)

    private fun descriptionKey(entry: SeriesEntry) = entry.id + "|" + entry.url

    fun update(input: SeriesListInput) {
        work?.cancel()
        val version = ++generation
        if (catalogKey != input.catalogKey) {
            cards.clear()
            desired = emptyMap()
            prepared.clear()
            knownIndices = emptyMap()
            clearDescriptions()
            resources.clear()
            catalogKey = input.catalogKey
        }
        val known = knownIndices
        work = scope.launch {
            val plan = withContext(dispatcher) { plan(input, known) }
            ensureActive()
            if (version != generation) return@launch
            window = plan.window
            knownIndices = plan.slots.associate { it.entry.key to it.index }
            val previous = desired
            desired = plan.slots.associate { slot ->
                slot.key to (previous[slot.key]?.takeIf { it.slot.entry == slot.entry }
                    ?: Preparation(++nextToken, slot))
            }
            prepared.keys.toList().forEach { key ->
                if (desired[key]?.slot?.entry != prepared[key]?.entry) prepared.remove(key)
            }
            updateResources(plan, input.selectedKey)
            cards.update(desired.values.filter { prepared[it.slot.key]?.entry != it.slot.entry })
        }
    }

    private fun updateResources(plan: Plan, selectedKey: String?) {
        val previous = desiredDescriptions
        val selected = plan.slots.firstOrNull { it.entry.key == selectedKey }?.entry?.let(::descriptionKey)
        val retry = selected?.takeIf { it != selectedDescription && described[it]?.failed == true }
        if (retry != null) described.remove(retry)
        selectedDescription = selected
        desiredDescriptions = if (loadDescription == null) emptyMap() else plan.slots.associate { slot ->
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
