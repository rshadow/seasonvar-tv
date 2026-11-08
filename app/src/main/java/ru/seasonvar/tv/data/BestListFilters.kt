package ru.seasonvar.tv.data

import org.jsoup.Jsoup
import ru.seasonvar.tv.data.account.FilterOption
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.LocalDate

data class BestListFilters(val genre: String = "", val period: String = "1") {
    fun values() = mapOf("genre" to genre, "period" to period)
    fun safe() = BestListFilters(genre.takeIf { it.matches(Regex("[0-9]{1,6}")) }.orEmpty(),
        period.takeIf { it in setOf("1", "7", "31") } ?: "1")
    fun url(): String = "https://seasonvar.ru/?mode=top".toHttpUrl().newBuilder().apply {
        if (genre.isNotEmpty()) addQueryParameter("genre", genre)
        addQueryParameter("period", period)
    }.build().toString()
    val cacheName get() = if (this == BestListFilters()) "best.html" else "best-genre-${genre.ifEmpty { "all" }}-period-$period.html"
    companion object {
        fun from(values: Map<String, String>) = BestListFilters(values["genre"].orEmpty(), values["period"] ?: "1")
    }
}

data class BestFilterOptions(val genres: List<FilterOption>, val periods: List<FilterOption>) {
    fun normalize(value: BestListFilters) = BestListFilters(
        value.genre.takeIf { id -> genres.any { it.value == id } }.orEmpty(),
        value.period.takeIf { id -> periods.any { it.value == id } } ?: "1")
}

data class FilteredBestPage(val page: CatalogPage, val options: BestFilterOptions, val filters: BestListFilters)

object BestFilterParser {
    fun options(html: String): BestFilterOptions {
        val doc = Jsoup.parse(html)
        val select = doc.select(".pgs-search select.gen").singleOrNull() ?: throw MarkupChangedException()
        val genres = select.select("option[value]").map { FilterOption(it.attr("value"), it.text()) }
        val periods = doc.select(".pgs-search input.per[name=period]").map {
            FilterOption(it.attr("value"), it.parent()?.text().orEmpty())
        }
        if (genres.isEmpty() || genres.count { it.value.isEmpty() } != 1 ||
            genres.any { it.label.isBlank() || (it.value.isNotEmpty() && !it.value.matches(Regex("[0-9]{1,6}"))) } ||
            genres.map { it.value }.distinct().size != genres.size ||
            periods.map { it.value }.toSet() != setOf("1", "7", "31") || periods.size != 3 || periods.any { it.label.isBlank() })
            throw MarkupChangedException()
        return BestFilterOptions(genres, periods)
    }

    fun parse(html: String, day: LocalDate, expected: BestListFilters): FilteredBestPage {
        val options = options(html)
        val doc = Jsoup.parse(html)
        val script = doc.select("script:not([src])").joinToString("\n") { it.data() }
        fun initialized(selector: String, property: String): String? = Regex(
            "\\$\\(\\s*[\"']\\.$selector\\[value=([0-9]+)\\][\"']\\s*\\)\\.prop\\(\\s*[\"']$property[\"']\\s*,\\s*true\\s*\\)")
            .findAll(script).map { it.groupValues[1] }.toList().let {
                if (it.size > 1) throw MarkupChangedException()
                it.singleOrNull()
            }
        val genre = initialized("gen option", "selected")
            ?: doc.select("select.gen option[selected]").singleOrNull()?.attr("value") ?: throw MarkupChangedException()
        val period = initialized("per", "checked")
            ?: doc.select("input.per[checked]").singleOrNull()?.attr("value") ?: throw MarkupChangedException()
        val filters = BestListFilters(genre, period)
        if (filters != expected || options.normalize(filters) != filters) throw MarkupChangedException()
        return FilteredBestPage(SeasonvarParser.best(html, day), options, filters)
    }
}
