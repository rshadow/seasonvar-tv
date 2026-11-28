package ru.seasonvar.tv.data.account

enum class PersonalFeed(val tabs: List<String>, val url: String = "https://seasonvar.ru/resume") {
    RESUME(listOf("marks-new", "marks-nonew")),
    UPDATES(listOf("marks-new")),
    NO_UPDATES(listOf("marks-nonew")),
    WANT(listOf("marks-want")),
    WATCHED(listOf("marks-watched")),
    HISTORY(emptyList(), "https://seasonvar.ru/?mod=history");

    val catalogKey: String get() = "PERSONAL_" + name
}
