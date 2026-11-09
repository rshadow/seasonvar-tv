package ru.seasonvar.tv.data.account

import kotlinx.coroutines.*
import okhttp3.Cookie
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.cache.*
import java.io.File
import java.io.IOException

class PersonalHtmlCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private val page get() = javaClass.getResource("/account-resume-current.html")!!.readText() +
        javaClass.getResource("/account-filter-options.html")!!.readText()
    private inner class Fixture {
        val directory = folder.newFolder()
        val sessions: SessionManager
        val disk: HtmlResponseCache
        val cards: CardDataCache
        var calls = 0; var posts = 0; var offline = false; var expired = false
        var afterGet: suspend () -> Unit = {}
        val account: AccountRepository
        init {
            val store = MemorySessionStore().apply {
                cookies = listOf(Cookie.Builder().name("fixture").value("dummy").domain("seasonvar.ru").secure().build())
            }
            sessions = SessionManager(store) {
                val current = sessions.currentCacheScope()
                if (current?.private == true && current.owner != null) disk.selectPrivateOwner(current.owner)
                else if (current?.private == false) disk.clearPrivate()
                cards.clearPrivate()
            }
            disk = HtmlResponseCache(directory, sessions::cacheScope, sessions::currentCacheScope)
            cards = CardDataCache(disk)
            account = AccountRepository(sessions, object : AccountTransport {
                override suspend fun get(url: String, jar: SessionCookies): String {
                    calls++; if (offline) throw IOException()
                    val reply = if (expired) "<div class='headblock-menu'><a href='/?mod=login'>Вход</a></div>" else page
                    afterGet(); return reply
                }
                override suspend fun filters(filters: PersonalListFilters, jar: SessionCookies): String {
                    posts++; return Jsoup.parse(page).select("[data-tabr]").outerHtml()
                }
                override suspend fun login(login: String, password: String, jar: SessionCookies) = ""
                override suspend fun saveMark(mark: PlaybackMark, jar: SessionCookies): String = error("No write")
            }, disk, cards)
        }
    }
    @Test fun validated_resume_html_is_shared_by_personal_tabs_and_survives_a_new_cache_instance() = runBlocking {
        val f = Fixture(); f.account.restore(); f.account.personalList(PersonalFeed.RESUME)
        val count = f.calls
        for (feed in PersonalFeed.entries.filter { it != PersonalFeed.HISTORY }) {
            assertEquals(AccountParser.personalList(page, feed), f.account.cachedPersonalList(feed))
        }
        assertEquals(count, f.calls)
        assertEquals(1, File(f.directory, "private").listFiles()!!.count { it.name.startsWith("personal-") })
        val restarted = HtmlResponseCache(f.directory, f.sessions::cacheScope, f.sessions::currentCacheScope)
        restarted.selectPrivateOwner("12")
        assertTrue(File(f.directory, "private").listFiles()!!.any { it.name.startsWith("personal-") })
    }
    @Test fun filtered_snapshot_keeps_exact_conditions_and_does_not_replace_default_tabs() = runBlocking {
        val f = Fixture(); f.account.restore()
        val filters = PersonalListFilters("17", "Япония")
        val response = f.account.filteredPersonalList(PersonalFeed.WANT, filters)
        val count = f.calls; val posts = f.posts
        assertEquals(response, f.account.cachedFilteredPersonalList(PersonalFeed.WANT, filters))
        assertNull(f.account.cachedFilteredPersonalList(PersonalFeed.WANT, filters.copy(genre = "18")))
        assertNotNull(f.account.cachedPersonalList(PersonalFeed.WANT))
        assertEquals(count, f.calls); assertEquals(posts, f.posts)
    }
    @Test fun logout_removes_private_html_and_native_fields_but_keeps_public_html_and_common_description() = runBlocking {
        val f = Fixture(); f.account.restore(); f.account.personalList(PersonalFeed.RESUME)
        val entry = SeriesEntry("1", "https://seasonvar.ru/serial-1-Test.html", "Тест", 1, "", "",
            accountMark = AccountMark("5", "Смотрю"))
        f.cards.load(entry, f.disk.capture(), false, { CachedHtml(
            "<div class='pgs-sinfo' data-id-season='1' data-id-serial='2'><div class='pgs-sinfo-info'><p>Описание</p></div></div>", System.currentTimeMillis()) })
        File(f.directory, "today.html").writeText("public")
        assertNotNull(f.cards.details(entry, f.disk.capture()))
        f.account.logout()
        assertFalse(File(f.directory, "private").exists()); assertNotNull(f.cards.description("1"))
        assertNull(f.cards.details(entry.copy(accountMark = null), f.disk.capture()))
        assertEquals("public", File(f.directory, "today.html").readText())
        assertNull(f.account.cachedPersonalList(PersonalFeed.RESUME))
    }
    @Test fun expired_session_cannot_save_guest_or_empty_markup_as_a_personal_snapshot() = runBlocking {
        val f = Fixture(); f.account.restore(); f.account.personalList(PersonalFeed.RESUME); f.expired = true
        assertTrue(runCatching { f.account.personalList(PersonalFeed.RESUME) }.exceptionOrNull() is SessionExpiredException)
        assertFalse(File(f.directory, "private").exists()); assertTrue(f.account.session.value.expired)
    }
    @Test fun a_late_list_response_after_logout_cannot_recreate_deleted_private_files() = runBlocking {
        val f = Fixture(); f.account.restore()
        f.afterGet = { f.sessions.clear() }
        f.account.personalList(PersonalFeed.RESUME)
        assertFalse(File(f.directory, "private").exists())
        assertNull(f.disk.read(HtmlResponseCache.requestKey("personal-", "GET", PersonalFeed.RESUME.url), f.disk.capture()))
    }
}
