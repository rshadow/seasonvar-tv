package ru.seasonvar.tv.data.cache

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HtmlResponseCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private var scope: CacheScope? = CacheScope(false, 0)
    private var now = 1_800_000_000_000L
    private fun cache(directory: File = folder.newFolder()) = HtmlResponseCache(directory, { scope }, { scope }, { now })

    @Test fun public_and_private_html_use_one_store_but_never_share_files() {
        val cache = cache(); val guest = cache.capture()
        cache.save("list.html", "guest", guest)
        scope = CacheScope(true, 1, "12"); cache.selectPrivateOwner("12")
        val account = cache.capture(); assertNull(cache.read("list.html", account))
        cache.save("list.html", "private", account); assertEquals("private", cache.read("list.html", account)?.html)
        scope = CacheScope(false, 2); cache.clearPrivate()
        assertEquals("guest", cache.read("list.html", cache.capture())?.html)
        scope = CacheScope(true, 3, "13"); cache.selectPrivateOwner("13")
        assertNull(cache.read("list.html", cache.capture()))
    }
    @Test fun expired_future_dated_and_unconfirmed_session_snapshots_are_not_read() {
        val directory = folder.newFolder(); val cache = cache(directory)
        cache.save("list.html", "guest", cache.capture())
        File(directory, "list.html").setLastModified(now - HtmlResponseCache.DAY)
        assertNull(cache.read("list.html", cache.capture()))
        File(directory, "list.html").setLastModified(now + 1)
        assertNull(cache.read("list.html", cache.capture()))
        scope = CacheScope(true, 1)
        cache.save("list.html", "unknown", cache.capture()); assertFalse(File(directory, "private/list.html").exists())
    }
    @Test fun logout_and_invalidation_reject_late_writes_without_deleting_public_or_unrelated_html() {
        val directory = folder.newFolder(); val cache = cache(directory)
        cache.save("public.html", "public", cache.capture())
        scope = CacheScope(true, 1, "12"); cache.selectPrivateOwner("12")
        cache.save("personal-one.html", "personal", cache.capture())
        cache.save("history-one.html", "old history", cache.capture())
        val previous = cache.capture(); cache.invalidate("history-")
        cache.save("history-one.html", "late history", previous)
        assertNull(cache.read("history-one.html", cache.capture()))
        assertEquals("personal", cache.read("personal-one.html", cache.capture())?.html)
        val oldAccount = cache.capture(); scope = CacheScope(false, 2); cache.clearPrivate()
        cache.save("personal-late.html", "private", oldAccount)
        assertFalse(File(directory, "private").exists())
        assertEquals("public", cache.read("public.html", cache.capture())?.html)
    }
    @Test fun owner_marker_prevents_a_cookie_change_during_a_crash_from_reusing_another_accounts_files() {
        val directory = folder.newFolder(); val first = cache(directory)
        scope = CacheScope(true, 1, "12"); first.selectPrivateOwner("12")
        first.save("personal.html", "account 12", first.capture())
        val restarted = cache(directory); restarted.selectPrivateOwner("12")
        assertEquals("account 12", restarted.read("personal.html", restarted.capture())?.html)
        scope = CacheScope(true, 2, "13"); restarted.selectPrivateOwner("13")
        assertNull(restarted.read("personal.html", restarted.capture()))
    }
    @Test fun request_keys_include_post_parameters_and_page_but_not_parameter_order() {
        fun key(method: String = "POST", url: String = "https://seasonvar.ru/ajax.php", values: Map<String,String>) =
            HtmlResponseCache.requestKey("personal-", method, url, values)
        val fields = linkedMapOf("genre" to "17", "country" to "Япония")
        assertEquals(key(values = fields), key(values = fields.entries.reversed().associate { it.toPair() }))
        assertNotEquals(key(values = fields), key(values = fields + ("genre" to "18")))
        assertNotEquals(key(values = fields), key(method = "GET", values = fields))
        assertNotEquals(key(values = fields), key(url = "https://seasonvar.ru/?page=2", values = fields))
        assertNotEquals(key(values = mapOf("a" to "b:c")), key(values = mapOf("a:b" to "c")))
    }
}
