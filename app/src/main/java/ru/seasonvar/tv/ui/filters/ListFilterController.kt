package ru.seasonvar.tv.ui.filters

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.seasonvar.tv.data.MarkupChangedException
import ru.seasonvar.tv.data.SiteHttpException
import ru.seasonvar.tv.ui.catalog.LoadFailure

class ListFilterController<P>(
    private val scope: CoroutineScope,
    private val source: ListFilterSource<P>,
    private val owner: String,
    private val onApplying: () -> Unit,
    private val onApplied: (P) -> Unit,
    private val context: () -> Any? = { owner },
) : ListFiltersController {
    private fun defaults() = source.fields.associate { it.id to it.defaultValue }
    private fun initial(): ListFiltersState {
        val saved = source.restore(owner)
        val values = defaults().mapValues { (id, default) -> saved[id] ?: default }
        return ListFiltersState(fields = source.fields, values = values, draft = values)
    }
    private val mutableState = MutableStateFlow(initial())
    override val state = mutableState.asStateFlow()
    private var optionsJob: Job? = null
    private var applyJob: Job? = null

    override fun open() {
        if (state.value.applying) return
        optionsJob?.cancel()
        val expected = context()
        mutableState.value = state.value.copy(open = true, draft = state.value.values, loading = true, failure = null, applyFailure = null)
        optionsJob = scope.launch {
            try {
                val fields = source.options()
                ensureActive()
                if (context() != expected) return@launch
                val current = state.value
                mutableState.value = current.copy(fields = fields, loading = false, draft = fields.associate { field ->
                    field.id to (current.draft[field.id]?.takeIf(field::accepts) ?: field.defaultValue)
                })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { ensureActive(); if (context() == expected) mutableState.value = state.value.copy(loading = false, failure = failure(e)) }
        }
    }

    override fun dismiss() {
        optionsJob?.cancel(); applyJob?.cancel()
        mutableState.value = state.value.copy(open = false, loading = false, applying = false, draft = state.value.values, applyFailure = null)
    }

    fun contextChanged() {
        dismiss()
        mutableState.value = initial()
    }

    override fun choose(field: String, value: String) {
        val current = state.value
        if (current.open && !current.loading && !current.applying && current.fields.find { it.id == field }?.accepts(value) == true)
            mutableState.value = current.copy(draft = current.draft + (field to value), applyFailure = null)
    }

    override fun reset() {
        val current = state.value
        if (!current.open || current.loading || current.applying || current.failure != null) return
        mutableState.value = current.copy(draft = defaults(), applyFailure = null)
        apply()
    }

    override fun apply() {
        val current = state.value
        if (!current.open || current.loading || current.applying || current.failure != null) return
        if (current.values == current.draft) { dismiss(); return }
        onApplying()
        val expected = context()
        mutableState.value = current.copy(applying = true, applyFailure = null)
        applyJob = scope.launch {
            try {
                val result = source.load(current.draft)
                ensureActive()
                if (context() != expected) return@launch
                onApplied(result.page)
                accept(result, close = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { ensureActive(); if (context() == expected) mutableState.value = state.value.copy(applying = false, applyFailure = failure(e)) }
        }
    }

    fun accept(result: FilteredListPage<P>, close: Boolean = false) {
        val current = state.value
        mutableState.value = current.copy(open = current.open && !close, fields = result.fields, values = result.values,
            draft = if (current.open && !close) current.draft else result.values, applying = false, applyFailure = null)
        source.save(owner, result.values)
    }

    private fun failure(error: Exception) = when (error) {
        is MarkupChangedException -> LoadFailure.MARKUP
        is SiteHttpException -> LoadFailure.HTTP
        else -> LoadFailure.NETWORK
    }
}
