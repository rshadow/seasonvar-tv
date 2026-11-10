package ru.seasonvar.tv.data

import org.jsoup.Jsoup
import ru.seasonvar.tv.data.account.FilterOption
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.LocalDate

enum class BestSwitch(val id: String, val control: String, val personal: Boolean = false) {
    FHD("fhd", "fhdTop"), HD("hd", "hdTop"), SUBTITLES("sub", "subTop"),
    FOREIGN_ONLY("eng", "engTop"), AVAILABLE("block", "blockTop"),
    HIDE_WATCHING("mark", "markTop", true), HIDE_HISTORY("history", "historyTop", true),
    HIDE_BLACKLIST("nw", "notwatchedTop", true),
}

data class BestListFilters(val genre: String = "", val period: String = "1", val switches: Set<BestSwitch> = emptySet()) {
    fun values() = mapOf("genre" to genre, "period" to period) + BestSwitch.entries.associate { flag ->
        flag.id to (if (flag == BestSwitch.FOREIGN_ONLY) flag !in switches else flag in switches).toString()
    }
    fun form() = BestSwitch.entries.associate { "filterTop[${it.id}]" to if (it in switches) "yes" else "" }
    fun base() = copy(switches = emptySet())
    fun safe() = BestListFilters(genre.takeIf { it.matches(Regex("[0-9]{1,6}")) }.orEmpty(),
        period.takeIf { it in setOf("1", "7", "31") } ?: "1", switches)
    fun url(): String = "https://seasonvar.ru/?mode=top".toHttpUrl().newBuilder().apply {
        if (genre.isNotEmpty()) addQueryParameter("genre", genre)
        addQueryParameter("period", period)
    }.build().toString()
    val cacheName get(): String {
        if (this == BestListFilters()) return "best.html"
        val prefix = "best-genre-${genre.ifEmpty { "all" }}-period-$period"
        return if (switches.isEmpty()) "$prefix.html" else "$prefix-flags-${switches.sumOf { 1 shl it.ordinal }}.json"
    }
    companion object {
        fun from(values: Map<String, String>) = BestListFilters(values["genre"].orEmpty(), values["period"] ?: "1",
            BestSwitch.entries.filter { if (it == BestSwitch.FOREIGN_ONLY) values[it.id] == "false" else values[it.id] == "true" }.toSet())
    }
}

data class BestFilterOptions(val genres: List<FilterOption>, val periods: List<FilterOption>, val switches: Set<BestSwitch> = emptySet()) {
    fun normalize(value: BestListFilters) = BestListFilters(
        value.genre.takeIf { id -> genres.any { it.value == id } }.orEmpty(),
        value.period.takeIf { id -> periods.any { it.value == id } } ?: "1", value.switches.intersect(switches))
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
        val controls = doc.select("[data-chgroup=filterTop] input[data-filter]")
        val switches = BestSwitch.entries.filter { flag ->
            val found = controls.filter { it.attr("data-filter") == flag.control }
            if (found.size > 1 || found.any { it.attr("type") != "checkbox" }) throw MarkupChangedException()
            found.isNotEmpty()
        }.toSet()
        return BestFilterOptions(genres, periods, switches)
    }

    fun parse(html: String, day: LocalDate, expected: BestListFilters, windowed: Boolean = false): FilteredBestPage {
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
        return FilteredBestPage(SeasonvarParser.best(html, day, windowed), options, filters)
    }
}
