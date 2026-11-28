package ru.seasonvar.tv.ui.navigation

import ru.seasonvar.tv.data.account.AccountSession
import ru.seasonvar.tv.data.account.PersonalListFilters
import ru.seasonvar.tv.ui.account.AccountFailure
import ru.seasonvar.tv.ui.account.AccountUiState
import ru.seasonvar.tv.ui.catalog.CatalogUiState

internal data class PersonalStartPreview(
    val section: Section,
    val owner: String,
    val state: CatalogUiState,
    val filters: PersonalListFilters = PersonalListFilters(),
) {
    fun isReplacedBy(live: CatalogUiState?): Boolean =
        live?.loaded == true && (!live.cached || !live.loading)

    fun isAvailable(account: AccountSession, status: AccountUiState): Boolean {
        if (account.expired) return false
        val user = account.user
        if (user != null) return user.id == owner
        return account.version == 0L && (status.busy || status.failure == AccountFailure.NETWORK)
    }
}
