package ru.seasonvar.tv.data.settings

object ConnectionCheckSettings {
    const val CONNECT_TIMEOUT_MILLIS = 3_000L
    const val REQUEST_TIMEOUT_MILLIS = 5_000L
    const val CYCLE_TIMEOUT_MILLIS = 12_000L
    const val HTML_LIMIT_BYTES = 2 * 1024 * 1024L
    const val POSTER_LIMIT_BYTES = 1024 * 1024L
    const val VIDEO_PREFIX_BYTES = 1024L
    const val SEASON_ID = "32752"
    const val PAGE_URL = "https://seasonvar.ru/serial-32752-Lyubov__smert__i_roboty_psxknda-3-season.html"
    const val POSTER_URL = "https://cdn.svstat.net/oblojka/32752.jpg"
}
