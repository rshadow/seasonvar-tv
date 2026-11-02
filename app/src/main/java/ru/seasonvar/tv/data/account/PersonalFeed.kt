package ru.seasonvar.tv.data.account

enum class PersonalFeed(val tabs: List<String>) {
    RESUME(listOf("marks-new", "marks-nonew")),
    UPDATES(listOf("marks-new")),
    NO_UPDATES(listOf("marks-nonew")),
    WANT(listOf("marks-want")),
    WATCHED(listOf("marks-watched"));

    val catalogKey: String get() = "PERSONAL_" + name
}
