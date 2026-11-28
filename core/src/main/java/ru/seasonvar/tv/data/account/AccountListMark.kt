package ru.seasonvar.tv.data.account

import ru.seasonvar.tv.data.SeasonvarParser
import java.net.URI

/** A season's permanent account entry; this does not identify a history record. */
data class AccountListMark(val seasonId: String, val pageUrl: String) {
    init {
        require(seasonId.matches(Regex("[1-9][0-9]*")))
        require(SeasonvarParser.pageUrl(pageUrl) == pageUrl)
        require(Regex("/serial-([0-9]+)").find(URI(pageUrl).path)?.groupValues?.get(1) == seasonId)
    }
}
