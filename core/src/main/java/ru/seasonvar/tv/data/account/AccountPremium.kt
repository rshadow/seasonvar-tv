package ru.seasonvar.tv.data.account

import java.time.LocalDate

sealed interface AccountPremium {
    data class Until(val date: LocalDate) : AccountPremium
    data object Inactive : AccountPremium
    data object Unknown : AccountPremium
}
