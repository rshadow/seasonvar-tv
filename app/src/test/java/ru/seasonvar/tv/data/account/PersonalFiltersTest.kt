package ru.seasonvar.tv.data.account

import kotlinx.coroutines.*
import okhttp3.FormBody
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.MarkupChangedException
import ru.seasonvar.tv.data.SiteClient

class PersonalFiltersTest {
    private val options get() = javaClass.getResource("/account-filter-options.html")!!.readText()
    private val account get() = javaClass.getResource("/account-resume-current.html")!!.readText()
    private val page get() = account + options
    private val fragment get() = Jsoup.parse(account).select("[data-tabr]").outerHtml()
    private val user = AccountParser.user(account)!!

    @Test fun reads_fresh_options_with_exact_values_and_normalizes_only_unknown_selections() {
        val choices = PersonalFilterParser.options(options)
        assertEquals(listOf("all", "17", "18"), choices.genres.map { it.value })
        assertEquals("Discovery&BBC", choices.countries.last().value)
        assertEquals(PersonalListFilters("17", "Япония"), choices.normalize(PersonalListFilters("17", "Япония")))
        assertEquals(PersonalListFilters(country = "Япония"), choices.normalize(PersonalListFilters("missing", "Япония")))
        for (broken in listOf("", options.replace("value=\"all\"", "value=\"other\""), options.replace("value=\"17\"", "value=\"18\""))) {
            try { PersonalFilterParser.options(broken); fail() } catch (_: MarkupChangedException) { }
        }
    }

    @Test fun filtered_fragment_uses_a_previously_authenticated_identity_and_selects_the_correct_tabs() {
        for (feed in PersonalFeed.entries.filter { it != PersonalFeed.HISTORY }) {
            assertEquals(AccountParser.personalList(account, feed), AccountParser.filteredPersonalList(fragment, user, feed))
        }
        val empty = "<ul>" + listOf("marks-new", "marks-nonew", "marks-want", "marks-watched").joinToString("") { "<li data-tabr='$it'></li>" } + "</ul>"
        assertTrue(AccountParser.filteredPersonalList(empty, user, PersonalFeed.RESUME).entries.isEmpty())
        for (broken in listOf("", "Access denied", empty.replace("marks-want", "other"))) {
            try { AccountParser.filteredPersonalList(broken, user, PersonalFeed.WANT); fail() } catch (_: MarkupChangedException) { }
        }
        try { AccountParser.filteredPersonalList("<div class=headblock-menu><a href='/?mod=login'>Вход</a></div>" + empty, user, PersonalFeed.WANT); fail() }
        catch (_: SessionExpiredException) { }
    }

    @Test fun sends_form_values_to_the_original_filter_route_with_correct_utf8_and_no_mark_parameters() {
        val request = SiteClient.personalFiltersRequest(PersonalListFilters("17", "Япония"))
        assertEquals("https://seasonvar.ru/ajax.php", request.url.toString())
        assertEquals("https://seasonvar.ru/resume", request.header("Referer"))
        assertEquals("XMLHttpRequest", request.header("X-Requested-With"))
        val form = request.body as FormBody
        assertEquals(mapOf("marks_filter" to "true", "genre" to "17", "country" to "Япония"),
            (0 until form.size).associate { form.name(it) to form.value(it) })
    }

    private inner class Transport : AccountTransport {
        val calls = mutableListOf<String>()
        var expired = false
        var reply = fragment
        var onFilter: suspend () -> Unit = {}
        override suspend fun get(url: String, jar: SessionCookies): String {
            calls += "GET"
            return if (expired) "<div class=headblock-menu><a href='/?mod=login'>Вход</a></div>" else page
        }
        override suspend fun login(login: String, password: String, jar: SessionCookies) = ""
        override suspend fun saveMark(mark: PlaybackMark, jar: SessionCookies): String = error("No marks may be written")
        override suspend fun filters(filters: PersonalListFilters, jar: SessionCookies): String {
            calls += "POST:${filters.genre}:${filters.country}"; onFilter(); return reply
        }
    }

    @Test fun repository_proves_identity_before_filtering_and_never_changes_marks_or_history_revision() = runBlocking {
        val transport = Transport(); val repo = AccountRepository(SessionManager(MemorySessionStore()), transport)
        repo.login("test", "dummy"); transport.calls.clear()
        val result = repo.filteredPersonalList(PersonalFeed.WANT, PersonalListFilters("17", "Япония"))
        assertEquals(listOf("GET", "POST:17:Япония"), transport.calls)
        assertEquals(listOf("789"), result.page.entries.map { it.id })
        assertEquals(0L, repo.marksRevision.value); assertEquals(0L, repo.historyRevision.value)
        transport.calls.clear(); repo.personalList(PersonalFeed.WATCHED)
        assertEquals(listOf("GET"), transport.calls)
    }

    @Test fun defaults_or_removed_options_use_a_normal_get_and_an_expired_session_never_sends_the_filter_post() = runBlocking {
        val transport = Transport(); val repo = AccountRepository(SessionManager(MemorySessionStore()), transport)
        repo.login("test", "dummy"); transport.calls.clear()
        val result = repo.filteredPersonalList(PersonalFeed.WANT, PersonalListFilters("missing", "missing"))
        assertEquals(PersonalListFilters(), result.filters); assertEquals(listOf("GET"), transport.calls)
        transport.calls.clear(); transport.expired = true
        try { repo.filteredPersonalList(PersonalFeed.WANT, PersonalListFilters("17")); fail() } catch (_: SessionExpiredException) { }
        assertEquals(listOf("GET"), transport.calls); assertNull(repo.session.value.user)
    }

    @Test fun an_invalid_or_empty_fragment_is_not_treated_as_an_empty_filtered_list() = runBlocking {
        val transport = Transport(); val repo = AccountRepository(SessionManager(MemorySessionStore()), transport)
        repo.login("test", "dummy"); transport.calls.clear(); transport.reply = ""
        try { repo.filteredPersonalList(PersonalFeed.WANT, PersonalListFilters("17")); fail() } catch (_: MarkupChangedException) { }
        assertEquals(listOf("GET", "POST:17:all", "GET"), transport.calls)
        assertNotNull(repo.session.value.user)
        transport.expired = false
        transport.onFilter = { transport.expired = true }
        try { repo.filteredPersonalList(PersonalFeed.WANT, PersonalListFilters("17")); fail() } catch (_: SessionExpiredException) { }
        assertNull(repo.session.value.user)
    }
}
