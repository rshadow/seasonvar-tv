package ru.seasonvar.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class TodayRepository(
    private val directory: File,
    private val source: HtmlSource,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun cached(day: LocalDate): TodayPage? = withContext(Dispatchers.IO) {
        val file = File(directory, "today.html")
        if (!file.isFile || java.time.Instant.ofEpochMilli(file.lastModified())
                .atZone(zone).toLocalDate() != day
        ) return@withContext null
        runCatching { SeasonvarParser.today(file.readText(), day) }.getOrNull()
    }

    suspend fun load(day: LocalDate): TodayPage = withContext(Dispatchers.IO) {
        val html = source.get("https://seasonvar.ru/")
        val parsed = SeasonvarParser.today(html, day)
        save("today.html", html)
        parsed
    }

    suspend fun description(entry: TodayEntry): SeriesDescription {
        val cached = withContext(Dispatchers.IO) {
            val file = File(directory, "series-" + entry.id + ".html")
            if (file.isFile && System.currentTimeMillis() - file.lastModified() < 86_400_000L) {
                runCatching { SeasonvarParser.description(file.readText(), entry) }.getOrNull()
            } else null
        }
        if (cached != null) return cached
        return withContext(Dispatchers.IO) {
            val html = source.get(entry.url)
            val description = SeasonvarParser.description(html, entry)
            save("series-" + entry.id + ".html", html)
            description
        }
    }

    private fun save(name: String, html: String) {
        runCatching {
            directory.mkdirs()
            val temporary = File.createTempFile("response-", ".html", directory)
            try {
                temporary.writeText(html)
                check(temporary.renameTo(File(directory, name)))
            } finally {
                temporary.delete()
            }
        }
    }
}
