package ru.seasonvar.tv.ui

import androidx.compose.runtime.*
import ru.seasonvar.tv.data.PublicFeed
import ru.seasonvar.tv.data.account.PersonalFeed
import ru.seasonvar.tv.ui.catalog.BrowseCatalogController
import ru.seasonvar.tv.ui.navigation.Section

@Composable
internal fun MainCatalogActivation(section: Section, waitingForStartPage: Boolean, browseCatalog: BrowseCatalogController?,
    onFeedVisible: (PublicFeed) -> Unit, onSmartVisible: (Boolean) -> Unit,
    onPersonalVisible: (PersonalFeed?) -> Unit, onSearchVisible: (Boolean) -> Unit) {
    val browsing = section == Section.CATALOG
    val smart = section == Section.SMART_CONTINUE
    val personalFeed = section.personalFeed
    val searching = section == Section.SEARCH
    val publicActivation = remember { object { var feed: PublicFeed? = null } }
    SideEffect {
        val feed = section.publicFeed.takeUnless { waitingForStartPage }
        if (publicActivation.feed != feed) {
            publicActivation.feed = feed
            feed?.let(onFeedVisible)
        }
    }
    LaunchedEffect(browsing) { if (browsing) browseCatalog?.activate() }
    DisposableEffect(smart) {
        onSmartVisible(smart)
        onDispose { onSmartVisible(false) }
    }
    DisposableEffect(personalFeed) {
        onPersonalVisible(personalFeed)
        onDispose { onPersonalVisible(null) }
    }
    DisposableEffect(searching) {
        onSearchVisible(searching)
        onDispose { if (searching) onSearchVisible(false) }
    }
}
