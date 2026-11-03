package ru.seasonvar.tv.diagnostics

import org.jsoup.Jsoup

// Only structure and counts: never serialize text, attribute values, URLs or cookies.
internal fun accountStructure(html: String): String {
    val doc = Jsoup.parse(html)
    val header = doc.selectFirst(".headblock-menu")
    val elements = header?.getAllElements()?.take(24)?.joinToString(";") { node ->
        node.tagName() + "(" + node.attributes().asList().joinToString(",") { it.key } + ")"
    }.orEmpty()
    val tabs = listOf("marks-new", "marks-nonew", "marks-want", "marks-watched").joinToString(",") { tab ->
        tab + "=" + doc.select("[data-tabr=$tab] .pgs-marks-el").size
    }
    val row = doc.selectFirst(".pgs-marks-el")
    val rowShape = row?.getAllElements()?.take(35)?.joinToString(";") { node ->
        node.tagName() + "." + node.classNames().filter { it.startsWith("pgs-") && it.matches(Regex("[a-z-]+")) }.joinToString(".") +
            "(" + node.attributes().asList().joinToString(",") { it.key } + ")"
    }.orEmpty()
    val bn = doc.select(".pgs-marks-bn")
    return "headerImages=" + (header?.select("img")?.size ?: 0) + " header=" + elements +
        " bn=" + bn.size + " bnSerialLinks=" + bn.select("a[href*=serial-]").size +
        " bnTags=" + bn.select("*").groupingBy { it.tagName() }.eachCount() +
        " rows=" + doc.select(".pgs-marks-el").size + " tabs=" + tabs + " rowShape=" + rowShape +
        " avatarParsed=" + runCatching { ru.seasonvar.tv.data.account.AccountParser.user(html)?.avatarUrl != null }.getOrDefault(false)
}

internal fun historyStructure(html: String): String {
    val doc = Jsoup.parse(html, "https://seasonvar.ru/")
    val history = doc.selectFirst(".pgs-history") ?: doc.selectFirst(".content-wrap.profile")
    val shape = history?.getAllElements()?.take(90)?.joinToString(";") { node ->
        node.tagName() + "." + node.classNames().filter { it.startsWith("pgs-") && it.matches(Regex("[a-z-]+")) }.joinToString(".") +
            "(" + node.attributes().asList().joinToString(",") { it.key } + ")"
    }.orEmpty()
    val pages = doc.select(".pgs-history-p a[href]").size
    return "historyContainer=" + (doc.selectFirst(".pgs-history") != null) +
        " historyRows=" + doc.select(".pgs-history-c a[href*=serial-]").size +
        " historyShape=" + shape + " historyPageLinks=" + pages
}

internal fun accountProgressStructure(html: String): String {
    val words = setOf("отмечено", "просмотрено", "вышло", "всего", "из", "сезон", "сезона", "сезонов", "сезоны", "серия", "серии", "серий", "эпизодов", "эпизод", "по")
    fun shape(text: String) = Regex("[\\p{L}]+|[0-9]+").replace(text) {
        val word = it.value.lowercase(java.util.Locale.ROOT)
        when { word.all { c -> c.isDigit() } -> "#"; word in words -> word; else -> "word" }
    }.take(200)
    val doc = Jsoup.parse(html)
    return "progressShapes=" + doc.select(".pgs-marks-el").take(6).joinToString(";") { row ->
        listOf("pgs-marks-seas", "pgs-marks-current", "pgs-marks-last").joinToString("|") { field ->
            field + ":" + row.select(".$field").joinToString(",") { node ->
                shape(node.ownText()) + " children=" + node.children().joinToString(",") { child ->
                    child.tagName() + "[" + shape(child.text()) + "] title=[" + shape(child.attr("title")) + "]"
                }
            }
        }
    }
}
