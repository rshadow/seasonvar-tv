package ru.seasonvar.tv.ui.reviews

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.seasonvar.tv.data.MarkupChangedException
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.SiteHttpException
import ru.seasonvar.tv.data.reviews.*
import ru.seasonvar.tv.ui.catalog.LoadFailure

data class ReviewsUiState(
    val entries: List<SeriesReview> = emptyList(),
    val total: Int? = null,
    val nextCursor: String? = null,
    val loading: Boolean = true,
    val loaded: Boolean = false,
    val loadingMore: Boolean = false,
    val failure: LoadFailure? = null,
    val moreFailure: LoadFailure? = null,
)

class ReviewsViewModel(
    private val first: suspend (Boolean) -> ReviewsPage,
    private val remaining: suspend (String, Boolean) -> List<SeriesReview>,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ReviewsUiState())
    val state = mutableState.asStateFlow()
    private var firstJob: Job? = null
    private var moreJob: Job? = null
    private var generation = 0L
    private var forceTail = false

    fun refresh(force: Boolean = false, clear: Boolean = false, preserveKey: String? = null) {
        firstJob?.cancel(); moreJob?.cancel()
        val request = ++generation
        val previousSize = if (clear) 0 else state.value.entries.size
        forceTail = force
        mutableState.value = if (clear) ReviewsUiState() else state.value.copy(loading = true,
            loadingMore = false, failure = null, moreFailure = null)
        firstJob = viewModelScope.launch {
            try {
                val page = first(force)
                ensureActive()
                val restoreTail = previousSize > page.entries.size ||
                    (preserveKey != null && page.entries.none { it.key == preserveKey })
                val tail = if (restoreTail && page.nextCursor != null) remaining(page.nextCursor, force) else null
                ensureActive()
                if (tail != null && tail.isEmpty() && (page.total ?: 0) > page.entries.size) throw MarkupChangedException()
                if (request != generation) return@launch
                val entries = uniqueReviews(page.entries + tail.orEmpty())
                val next = if (tail != null) null else page.nextCursor
                mutableState.value = ReviewsUiState(entries, total(entries, page.total, next), next,
                    loading = false, loaded = true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                ensureActive()
                if (request == generation) mutableState.value = state.value.copy(loading = false, failure = failure(error))
            }
        }
    }

    fun more(retry: Boolean = false) {
        val current = state.value
        val cursor = current.nextCursor ?: return
        if (current.loading || current.loadingMore || (current.moreFailure != null && !retry)) return
        val request = generation
        mutableState.value = current.copy(loadingMore = true, moreFailure = null)
        moreJob = viewModelScope.launch {
            try {
                val tail = remaining(cursor, forceTail || retry)
                ensureActive()
                if (tail.isEmpty() && (current.total ?: 0) > current.entries.size) throw MarkupChangedException()
                if (request != generation) return@launch
                val entries = uniqueReviews(current.entries + tail)
                mutableState.value = state.value.copy(entries = entries, total = entries.size, nextCursor = null,
                    loadingMore = false, moreFailure = null)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                ensureActive()
                if (request == generation) mutableState.value = state.value.copy(loadingMore = false, moreFailure = failure(error))
            }
        }
    }

    private fun total(entries: List<SeriesReview>, total: Int?, next: String?) =
        if (next == null) entries.size else total?.coerceAtLeast(entries.size)

    private fun failure(error: Exception) = when (error) {
        is MarkupChangedException -> LoadFailure.MARKUP
        is SiteHttpException -> LoadFailure.HTTP
        else -> LoadFailure.NETWORK
    }

    class Factory(private val repository: ReviewsRepository, private val entry: SeriesEntry) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ReviewsViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return ReviewsViewModel({ repository.first(entry, it) }, { cursor, force -> repository.more(entry, cursor, force) }) as T
        }
    }
}
