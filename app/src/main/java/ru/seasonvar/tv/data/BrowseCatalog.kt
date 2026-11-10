package ru.seasonvar.tv.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jsoup.parser.Parser
import org.jsoup.parser.StreamParser
import ru.seasonvar.tv.data.account.FilterOption
import java.time.LocalDate

data class CatalogFilters(val values: Map<String, String> = emptyMap()) {
    operator fun get(key: String) = values[key] ?: defaults[key] ?: "all"
    fun with(key: String, value: String) = CatalogFilters(values + (key to value))
    fun genres(key: String) = get(key).split(',').filter { it.matches(Regex("[0-9]{1,6}")) }.toSet()
    fun form(): List<Pair<String, String>> = buildList {
        fun scalar(key: String, value: String) { add("filter[$key]" to value) }
        fun array(key: String, values: Iterable<String>) { values.forEach { add("filter[$key][]" to it) } }
        array(if (get("countryNot") == "true") "notQuotC" else "quotC", listOf(get("country")).filter { it != "all" })
        array("quotG", genres("genres").sorted()); array("notQuotG", genres("excludedGenres").sorted())
        for ((field, suffix) in listOf("year" to "Y", "seasons" to "S", "rating" to "R")) {
            val prefix = when (get(field + "Op")) { ">" -> "more"; "<" -> "under"; else -> "quot" }
            array(prefix + suffix, listOf(get(field)).filter { it != "all" })
        }
        array("quotT", listOf(get("voice")).filter { it != "all" })
        array("sortTo", listOf(get("sort")))
        scalar("only", get("origin")); scalar("rait", get("ratingSource"))
        for (key in listOf("fhd", "hd", "sub", "mark", "history", "nw")) scalar(key, if (get(key) == "true") "yes" else "")
        for (key in listOf("engName", "block", "exp")) scalar(key, "")
    }
    companion object {
        val personal = setOf("mark", "history", "nw")
        val toggles = setOf("countryNot", "fhd", "hd", "sub") + personal
        val defaults = mapOf("country" to "all", "origin" to "", "genres" to "", "excludedGenres" to "",
            "year" to "all", "yearOp" to "=", "seasons" to "all", "seasonsOp" to "=", "rating" to "all",
            "ratingOp" to "=", "ratingSource" to "kp", "voice" to "all", "sort" to "newest") + toggles.associateWith { "false" }
    }
}

data class CatalogFilterOptions(val choices: Map<String, List<FilterOption>>) {
    fun normalize(filters: CatalogFilters, signedIn: Boolean): CatalogFilters {
        val result = CatalogFilters.defaults.toMutableMap()
        for ((key, options) in choices) result[key] = filters[key].takeIf { value -> options.any { it.value == value } }
            ?: CatalogFilters.defaults.getValue(key)
        for (key in CatalogFilters.toggles) result[key] = (filters[key] == "true" && (signedIn || key !in CatalogFilters.personal)).toString()
        val genres = choices.getValue("genres").map { it.value }.toSet() - "all"
        val included = filters.genres("genres").intersect(genres)
        result["genres"] = included.sorted().joinToString(",")
        result["excludedGenres"] = (filters.genres("excludedGenres").intersect(genres) - included).sorted().joinToString(",")
        return CatalogFilters(result)
    }
}

object BrowseCatalogParser {
    private val pagePath = Regex("/serial-([0-9]+)[^/?#]*\\.html")
    private val seasonPath = Regex("-([0-9]+)-season\\.html$")
    fun options(html: String): CatalogFilterOptions {
        val fields = mapOf("country" to "country", "genres" to "genre", "year" to "year", "seasons" to "seasc",
            "rating" to "raiting", "voice" to "golosa", "sort" to "sort")
        val controls = mutableMapOf<String, org.jsoup.nodes.Element>()
        StreamParser(Parser.htmlParser()).use { parser ->
            parser.parse(html.reader(), "https://seasonvar.ru/")
            while (controls.size < fields.size) {
                val element = parser.selectNext("select[data-filter], a[data-id]") ?: break
                val control = element.attr("data-filter")
                if (control in fields.values && control !in controls) controls[control] = element
                element.remove()
            }
        }
        val choices = fields.mapValues { (_, control) ->
            val select = controls[control] ?: throw MarkupChangedException()
            select.select("option[value]").map { FilterOption(it.attr("value"), it.text()) }.also { values ->
                if (values.isEmpty() || values.any { it.label.isBlank() || it.value.length > 160 } ||
                    values.map { it.value }.distinct().size != values.size ||
                    values.none { it.value == if (control == "sort") "newest" else "all" }) throw MarkupChangedException()
            }
        }.toMutableMap()
        choices["origin"] = listOf(FilterOption("", "Все"), FilterOption("rus", "Отечественные"), FilterOption("eng", "Зарубежные"))
        for (key in listOf("yearOp", "seasonsOp", "ratingOp")) choices[key] = listOf(
            FilterOption("=", "Равно"), FilterOption(">", "Больше"), FilterOption("<", "Меньше"))
        choices["ratingSource"] = listOf(FilterOption("kp", "КиноПоиск"), FilterOption("imdb", "IMDb"))
        return CatalogFilterOptions(choices)
    }

    fun entries(reader: java.io.Reader): ParsedSeriesEntries {
        val parser = StreamParser(Parser.htmlParser()).parse(reader, "https://seasonvar.ru/")
        val rows = sequence {
            val ids = HashSet<String>()
            var empty = false
            while (true) {
                val element = parser.selectNext("a[data-id], .pgs-msg") ?: break
                if (element.hasClass("pgs-msg")) {
                    if (element.text().replace(Regex("\\s+"), " ").trim() == "Под заданный фильтр ничего не подходит") empty = true
                    element.remove()
                } else {
                    if (element.parents().none { it.hasClass("lside-serial") && it.attr("data-serial") == "list" }) throw MarkupChangedException()
                    val path = element.attr("href")
                    val match = pagePath.matchEntire(path) ?: throw MarkupChangedException()
                    val id = match.groupValues[1]
                    val title = element.text().trim()
                    if (id != element.attr("data-id") || title.isBlank()) throw MarkupChangedException()
                    val season = seasonPath.find(path)?.groupValues?.get(1)?.toIntOrNull()
                    element.remove()
                    if (ids.add(id)) yield(SeriesEntry(id, "https://seasonvar.ru$path", title, season, "",
                        "https://cdn.svstat.net/oblojka/$id.jpg"))
                }
            }
            if ((ids.isEmpty() && !empty) || (ids.isNotEmpty() && empty)) throw MarkupChangedException()
        }
        return ListParsingWindow.open(rows) { parser.close(); reader.close() }
    }

    suspend fun page(html: String, day: LocalDate): CatalogPage {
        val parsed = entries(html.reader())
        val window = parsed.window ?: return CatalogPage(day, parsed)
        try {
            while (window.state.value.hasMore && !window.state.value.failed) {
                currentCoroutineContext().ensureActive()
                window.advance(window.state.value.entries.size)
            }
            if (window.state.value.failed) throw MarkupChangedException()
            return CatalogPage(day, window.state.value.entries)
        } finally { window.close() }
    }
}
