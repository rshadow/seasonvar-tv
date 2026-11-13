package ru.seasonvar.tv.data.reviews

data class SeriesReview(
    val identity: String,
    val author: String?,
    val date: String?,
    val text: String,
    val occurrence: Int = 0,
) {
    val key: String get() = "$identity:$occurrence"
}

data class ReviewsPage(val entries: List<SeriesReview>, val total: Int?, val nextCursor: String?)

internal fun uniqueReviews(entries: List<SeriesReview>): List<SeriesReview> {
    val occurrences = mutableMapOf<String, Int>()
    return entries.map { review ->
        val occurrence = occurrences.getOrDefault(review.identity, 0)
        occurrences[review.identity] = occurrence + 1
        review.copy(occurrence = occurrence)
    }
}
