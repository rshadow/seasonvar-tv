package ru.seasonvar.tv.ui.navigation

import ru.seasonvar.tv.data.account.AccountSession
import ru.seasonvar.tv.ui.account.AccountFailure
import ru.seasonvar.tv.ui.account.AccountUiState
import ru.seasonvar.tv.ui.catalog.CatalogUiState

internal data class PersonalStartPreview(val section: Section, val owner: String, val state: CatalogUiState) {
    fun isAvailable(account: AccountSession, status: AccountUiState): Boolean {
        if (account.expired) return false
        if (account.user != null) return account.user.id == owner
        return account.version == 0L && (status.busy || status.failure == AccountFailure.NETWORK)
    }
}
