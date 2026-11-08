package ru.seasonvar.tv.data.account

import org.jsoup.Jsoup
import ru.seasonvar.tv.data.MarkupChangedException

data class FilterOption(val value: String, val label: String)
data class PersonalListFilters(val genre: String = "all", val country: String = "all") {
    val active get() = genre != "all" || country != "all"
    fun values() = mapOf("genre" to genre, "country" to country)
    fun form() = linkedMapOf("marks_filter" to "true", "country" to country, "genre" to genre)
    companion object {
        fun from(values: Map<String, String>) = PersonalListFilters(values["genre"] ?: "all", values["country"] ?: "all")
    }
}

data class PersonalFilterOptions(val genres: List<FilterOption>, val countries: List<FilterOption>) {
    fun normalize(value: PersonalListFilters) = PersonalListFilters(
        value.genre.takeIf { id -> genres.any { it.value == id } } ?: "all",
        value.country.takeIf { id -> countries.any { it.value == id } } ?: "all",
    )
}
data class FilteredPersonalPage(val page: AccountPage, val options: PersonalFilterOptions, val filters: PersonalListFilters)

object PersonalFilterParser {
    fun options(html: String): PersonalFilterOptions {
        val doc = Jsoup.parse(html)
        fun choices(name: String): List<FilterOption> {
            val select = doc.select("select[name=$name]").singleOrNull() ?: throw MarkupChangedException()
            val rows = select.select("option[value]").map {
                FilterOption(it.attr("value"), it.text().trim()).also { option ->
                    if (option.value.isBlank() || option.value.length > 160 || option.label.isBlank() ||
                        option.value.any(Char::isISOControl)) throw MarkupChangedException()
                }
            }
            if (rows.none { it.value == "all" } || rows.map { it.value }.distinct().size != rows.size) throw MarkupChangedException()
            return rows
        }
        return PersonalFilterOptions(choices("marks-genre"), choices("marks-country"))
    }
}
