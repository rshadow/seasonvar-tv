package ru.seasonvar.tv.data.account

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AccountPremiumTest {
    private fun user(menu: String, outside: String = "") = AccountParser.user("""
        <div class="headblock-menu"><a href="/profile/12">Тест</a>$menu</div>$outside
    """)!!

    @Test fun expiration_comes_from_the_local_premium_menu_link_without_styling_classes() {
        for (label in listOf("Премиум активен до 10.11.2028", "Премиум аккаунт активен до 10.11.2028",
            "Премиум до 10.11.2028", "Премиум <small>аккаунт до 10.11.2028</small>")) {
            val account = user("<a href='/premium'>$label</a><div class='btn-abs__prem'>999</div>")
            assertEquals(AccountPremium.Until(LocalDate.of(2028, 11, 10)), account.premium)
        }
        assertEquals(AccountPremium.Until(LocalDate.of(2028, 11, 10)),
            user("<a href='https://seasonvar.ru/premium/'>Премиум&nbsp;до&nbsp;10.11.2028</a>").premium)
    }

    @Test fun unrelated_dates_badges_footer_and_foreign_links_do_not_grant_premium() {
        assertEquals(AccountPremium.Unknown, user("<span class='btn-abs__prem'>731</span>",
            "<a href='/premium'>Премиум до 10.11.2028</a>").premium)
        assertEquals(AccountPremium.Unknown,
            user("<a href='https://example.org/premium'>Премиум до 10.11.2028</a>").premium)
        assertEquals(AccountPremium.Unknown,
            user("<a href='/other'>Премиум до 10.11.2028</a>").premium)
        assertEquals(AccountPremium.Unknown,
            user("<a href='/premium?mod=payment'>Премиум до 10.11.2028</a>").premium)
    }

    @Test fun no_subscription_is_distinct_from_missing_or_malformed_information() {
        assertEquals(AccountPremium.Inactive, user("<a href='/premium'>Премиум</a>").premium)
        assertEquals(AccountPremium.Unknown, user("").premium)
        for (date in listOf("31.11.2028", "29.02.2027", "tomorrow", "10.11")) {
            assertEquals(AccountPremium.Unknown, user("<a href='/premium'>Премиум до $date</a>").premium)
        }
        assertEquals(AccountPremium.Until(LocalDate.of(2028, 2, 29)),
            user("<a href='/premium'>Премиум до 29.02.2028</a>").premium)
    }

    @Test fun conflicting_expiration_dates_are_unknown_instead_of_using_the_first_date() {
        assertEquals(AccountPremium.Unknown, user("""
            <a href='/premium'>Премиум до 10.11.2028</a>
            <a href='/premium'>Премиум аккаунт до 11.11.2028</a>
        """).premium)
        assertEquals(AccountPremium.Until(LocalDate.of(2028, 11, 10)), user("""
            <a href='/premium'>Премиум до 10.11.2028</a>
            <a href='/premium'>Премиум аккаунт до 10.11.2028</a>
        """).premium)
    }
}
