package ru.seasonvar.tv.data.account

import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.MarkupChangedException

class AccountParserTest {
    private val page get() = javaClass.getResource("/account-resume.html")!!.readText()
    @Test fun reads_own_header_profile_and_avatar_and_only_resume_block() {
        val result = AccountParser.resume(page)
        assertEquals("12", result.user.id)
        assertEquals("Тестовый пользователь", result.user.name)
        assertEquals("https://cdn.svstat.net/avatar/example.jpg", result.user.avatarUrl)
        assertEquals(listOf("123", "456"), result.entries.map { it.id })
        assertEquals(2, result.entries[0].season)
        assertEquals("Тестовый сериал", result.entries[0].title)
        assertEquals("https://seasonvar.ru/serial-123-test-2-season.html", result.entries[0].url)
    }
    @Test fun authenticated_empty_list_is_not_an_expired_session() {
        val empty = page.replace(Regex("""<div class="pgs-marks-bn">.*?</div>""", RegexOption.DOT_MATCHES_ALL), "")
        assertTrue(AccountParser.resume(empty).entries.isEmpty())
    }
    @Test(expected = SessionExpiredException::class) fun guest_empty_marks_are_not_an_empty_personal_list() {
        AccountParser.resume("""<div class="headblock-menu"><a href="/?mod=login">Вход</a></div><div class="pgs-marks"></div>""")
    }
    @Test(expected = MarkupChangedException::class) fun block_page_is_not_misclassified_as_bad_credentials() {
        AccountParser.user("<html><body>Unavailable</body></html>")
    }
    @Test(expected = MarkupChangedException::class) fun authenticated_wrong_page_is_not_an_empty_list() {
        AccountParser.resume("""<div class="headblock-menu"><a href="/?mod=logout">Выход</a></div>""")
    }
    @Test(expected = MarkupChangedException::class) fun does_not_accept_foreign_series_links() {
        AccountParser.resume(page.replace("/serial-123", "https://foreign.example.test/serial-123"))
    }
    @Test fun does_not_treat_an_external_logout_link_as_login() {
        val guest = """<div class="headblock-menu"><a href="https://other.example.test/?mod=logout">Выход</a></div>"""
        assertNull(AccountParser.user(guest))
    }
    @Test fun refuses_unsafe_avatar_but_keeps_verified_identity() {
        assertNull(AccountParser.resume(page.replace("https://cdn.svstat.net/avatar/example.jpg", "javascript:example")).user.avatarUrl)
    }
    @Test fun own_profile_link_in_header_is_sufficient_but_a_footer_profile_is_not() {
        assertEquals("12", AccountParser.user("""<div class="headblock-menu"><a href="/profile/12">Тест</a></div>""")!!.id)
        assertNull(AccountParser.user("""<div class="headblock-menu"><a href="/?mod=login">Войти</a></div><a href="/profile/12">Другой пользователь</a>"""))
    }

    private val current get() = javaClass.getResource("/account-resume-current.html")!!.readText()
    @Test fun reads_both_in_progress_tabs_in_site_order_with_season_and_mark_text() {
        val result = AccountParser.resume(current)
        assertEquals(listOf("123", "456"), result.entries.map { it.id })
        assertEquals(2, result.entries[0].season)
        assertEquals("Отмечено 3 из 10 · Новые серии", result.entries[0].update)
        assertEquals("https://seasonvar.ru/serial-123-test-2-season.html", result.entries[0].url)
        assertEquals("Тестовый пользователь", result.user.name)
        assertEquals("https://cdn.svstat.net/avatar/example.jpg", result.user.avatarUrl)
    }
    @Test fun empty_in_progress_tabs_do_not_fall_back_to_want_or_watched() {
        val page = current.replace(Regex("""<li data-tabr="marks-new">.*?</li>""", RegexOption.DOT_MATCHES_ALL), "<li data-tabr=marks-new></li>")
            .replace(Regex("""<li data-tabr="marks-nonew" hidden>.*?</li>""", RegexOption.DOT_MATCHES_ALL), "<li data-tabr=marks-nonew></li>")
        assertTrue(AccountParser.resume(page).entries.isEmpty())
    }
    @Test fun distinct_marks_for_one_season_are_not_dropped_and_have_distinct_keys() {
        val page = current.replace("/serial-456-other.html", "/serial-123-test-2-season.html")
        val result = AccountParser.resume(page)
        assertEquals(2, result.entries.size)
        assertEquals(listOf("123", "123"), result.entries.map { it.id })
        assertEquals(2, result.entries.map { it.key }.toSet().size)
    }
    @Test(expected = MarkupChangedException::class) fun malformed_mark_does_not_silently_turn_into_an_empty_list() {
        AccountParser.resume(current.replace("pgs-marks-name", "unknown-name"))
    }

    @Test fun avatar_alt_is_not_used_as_a_display_name_when_visible_name_is_missing() {
        val noName = current.replace("""<li class="head-user-name">Тестовый пользователь</li>""", "")
        val result = AccountParser.user(noName)!!
        assertEquals("Аккаунт Seasonvar", result.name)
        assertNotNull(result.avatarUrl)
    }
    @Test fun visible_account_name_is_preferred_over_image_description_and_profile_action() {
        val result = AccountParser.user(current.replace("""alt="avatar"""", """alt="Изображение профиля""""))!!
        assertEquals("Тестовый пользователь", result.name)
    }

}
