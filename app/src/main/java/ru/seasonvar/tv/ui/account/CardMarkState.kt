package ru.seasonvar.tv.ui.account

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.data.SeriesDetails
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.cache.CardMarkChange
import ru.seasonvar.tv.data.cache.CardDataCache

internal val LocalCardDataCache = staticCompositionLocalOf<CardDataCache?> { null }

@Composable
internal fun cardMarkState(entry: SeriesEntry): CardMarkChange? {
    val cache = LocalCardDataCache.current
        ?: (LocalContext.current.applicationContext as? SeasonvarApplication)?.cardData ?: return null
    val changes = remember(cache, entry.id) { cache.observeMark(entry.id) }
    return changes.collectAsState(initial = cache.mark(entry.id)).value
}

@Composable
internal fun currentCardEntry(entry: SeriesEntry, details: SeriesDetails? = null): SeriesEntry =
    cardMarkState(entry)?.apply(entry, details) ?: entry
