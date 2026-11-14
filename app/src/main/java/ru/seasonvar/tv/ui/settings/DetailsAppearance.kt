package ru.seasonvar.tv.ui.settings

internal enum class DetailsAppearance(val storageId: String) {
    PLAIN("plain"), BLURRED_POSTER("blurred_poster");

    companion object {
        fun fromStorage(value: String?) = entries.firstOrNull { it.storageId == value } ?: PLAIN
    }
}
