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
        assertEquals("", result.entries[0].update)
        assertEquals("", result.entries[0].subtitle)
        assertEquals("Отмечено 3 из 10", result.entries[0].accountMark!!.progress)
        assertEquals("Новые серии", result.entries[0].accountMark!!.status)
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

    @Test fun each_personal_section_reads_only_its_own_tab_including_hidden_tabs() {
        val expected = mapOf(PersonalFeed.RESUME to listOf("123", "456"),
            PersonalFeed.UPDATES to listOf("123"), PersonalFeed.NO_UPDATES to listOf("456"),
            PersonalFeed.WANT to listOf("789"), PersonalFeed.WATCHED to listOf("999"))
        expected.forEach { (feed, ids) ->
            assertEquals(feed.name, ids, AccountParser.personalList(current, feed).entries.map { it.id })
        }
    }
    @Test fun preserves_order_and_distinct_marks_even_when_their_season_url_matches() {
        val doc = org.jsoup.Jsoup.parse(current)
        val tab = doc.selectFirst("[data-tabr=marks-want]")!!
        val second = tab.selectFirst(".pgs-marks-el")!!.clone().attr("data-marksel", "790")
        second.selectFirst(".pgs-marks-name")!!.text("Следующая отметка")
        tab.appendChild(second)
        val entries = AccountParser.personalList(doc.outerHtml(), PersonalFeed.WANT).entries
        assertEquals(listOf("Хочу посмотреть", "Следующая отметка"), entries.map { it.title })
        assertEquals(1, entries.map { it.url }.toSet().size)
        assertEquals(2, entries.map { it.key }.toSet().size)
    }
    @Test fun empty_tab_is_empty_without_importing_neighbouring_lists() {
        PersonalFeed.entries.filter { it != PersonalFeed.RESUME && it.tabs.isNotEmpty() }.forEach { feed ->
            val doc = org.jsoup.Jsoup.parse(current)
            doc.selectFirst("[data-tabr=${feed.tabs.single()}]")!!.empty()
            assertTrue(feed.name, AccountParser.personalList(doc.outerHtml(), feed).entries.isEmpty())
        }
    }
    @Test fun missing_tab_or_malformed_selected_row_is_an_error_for_every_section() {
        PersonalFeed.entries.filter { it.tabs.isNotEmpty() }.forEach { feed ->
            val doc = org.jsoup.Jsoup.parse(current)
            doc.selectFirst("[data-tabr=${feed.tabs.first()}]")!!.remove()
            try { AccountParser.personalList(doc.outerHtml(), feed); fail(feed.name) } catch (_: MarkupChangedException) { }
            val bad = current.replace("pgs-marks-name", "unrecognised-name")
            try { AccountParser.personalList(bad, feed); fail(feed.name) } catch (_: MarkupChangedException) { }
        }
    }
    @Test fun guest_tabs_are_never_reported_as_an_empty_personal_section() {
        val doc = org.jsoup.Jsoup.parse(current)
        doc.selectFirst(".headblock-menu")!!.html("<a href='/?mod=login'>Вход</a>")
        PersonalFeed.entries.forEach { feed ->
            try { AccountParser.personalList(doc.outerHtml(), feed); fail(feed.name) } catch (_: SessionExpiredException) { }
        }
    }
    @Test fun unrelated_bad_tab_does_not_break_the_selected_list() {
        val doc = org.jsoup.Jsoup.parse(current)
        doc.selectFirst("[data-tabr=marks-watched] .pgs-marks-name")!!.remove()
        assertEquals("789", AccountParser.personalList(doc.outerHtml(), PersonalFeed.WANT).entries.single().id)
    }

    @Test fun absent_account_fields_stay_absent_instead_of_creating_empty_labels() {
        val result = AccountParser.personalList(current, PersonalFeed.WANT)
        assertNull(result.entries.single().accountMark)
        val withoutStatus = AccountParser.personalList(current, PersonalFeed.NO_UPDATES).entries.single()
        assertEquals("Отмечено 1 из 1", withoutStatus.accountMark!!.progress)
        assertEquals("", withoutStatus.accountMark.status)
        assertTrue(withoutStatus.subtitle.isEmpty())
    }

    @Test fun account_mark_keeps_the_exact_episode_id_but_does_not_confuse_count_with_id() {
        val entries = AccountParser.resume(current.replace("rewind=3_example", "rewind=7_seriya")).entries
        assertEquals("7_seriya", entries[0].initialSelection.episodeId)
        assertNull(entries[1].initialSelection.episodeId)
        assertNull(entries[1].initialSelection.episodeLabel)
    }


    @Test fun personal_rows_carry_a_typed_season_mark_even_without_progress_text() {
        for (feed in listOf(PersonalFeed.RESUME, PersonalFeed.WANT, PersonalFeed.WATCHED)) {
            val entries = AccountParser.personalList(current, feed).entries
            assertTrue(entries.isNotEmpty())
            entries.forEach { assertEquals(AccountListMark(it.id, it.url), it.accountListMark) }
        }
    }
    @Test(expected = MarkupChangedException::class)
    fun refuses_a_remove_control_pointing_at_a_different_season() {
        AccountParser.resume(current.replace("data-id=\"123\"", "data-id=\"999\""))
    }
}
