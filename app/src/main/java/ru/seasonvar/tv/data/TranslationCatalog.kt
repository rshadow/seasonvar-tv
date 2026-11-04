package ru.seasonvar.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.json.JSONArray
import java.util.Locale

object TranslationPriorities {
    fun name(value: String): String = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    fun clean(values: List<String>): List<String> = values.map { it.trim().replace(Regex("\\s+"), " ") }
        .filter { it.isNotEmpty() && it.length <= 120 }.distinctBy(::name)
    fun decode(json: String?): List<String> = try {
        if (json == null) emptyList() else JSONArray(json).let { array ->
            clean((0 until array.length()).map { array.get(it) as? String ?: throw IllegalArgumentException() })
        }
    } catch (_: Exception) { emptyList() }
    fun encode(values: List<String>): String = JSONArray(clean(values)).toString()
}

object TranslationCatalogParser {
    fun parse(html: String): List<String> {
        val selects = Jsoup.parse(html).select("select[data-filter=golosa]")
        if (selects.size != 1) throw MarkupChangedException()
        val options = selects.single().select("option").filter { it.attr("value") != "all" }
        val names = options.map { option ->
            val value = option.attr("value").trim()
            if (value.isBlank() || value.length > 120 || option.text().isBlank()) {
                throw MarkupChangedException()
            }
            value
        }
        if (names.isEmpty()) throw MarkupChangedException()
        return names.distinctBy(TranslationPriorities::name)
    }
}

class TranslationCatalogRepository(private val source: HtmlSource) {
    suspend fun load(): List<String> = withContext(Dispatchers.IO) {
        TranslationCatalogParser.parse(source.get("https://seasonvar.ru/"))
    }
}
