package ru.seasonvar.tv.data.account

import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.SiteClient

class AccountListStatusTest {
    private val target = AccountListMark("123", "https://seasonvar.ru/serial-123-Test.html")
    @Test fun negative_status_forms_follow_the_public_site_script() {
        for ((status, code, action) in listOf(Triple(AccountListStatus.WANT_TO_WATCH, "-1", "wanttosee"),
            Triple(AccountListStatus.WATCHED, "-2", "watched"), Triple(AccountListStatus.NOT_WATCHING, "-3", "notWatched"))) {
            val change = AccountStatusChange(target, status)
            assertEquals(mapOf("id" to "123", "seria" to code, "minute" to "0", "second" to "0", action to "true"), change.form())
            val request = SiteClient.statusRequest(change)
            assertEquals("POST", request.method); assertEquals("https://seasonvar.ru/jsonMark.php", request.url.toString())
            assertEquals(target.pageUrl, request.header("Referer")); assertEquals("XMLHttpRequest", request.header("X-Requested-With"))
        }
    }
    @Test fun watching_uses_a_real_episode_and_status_models_cannot_mix_different_seasons() {
        val point = PlaybackMark("123", target.pageUrl, "7", "298", 61)
        assertEquals(point.form(), AccountStatusChange(target, AccountListStatus.WATCHING, point).form())
        assertThrows(IllegalArgumentException::class.java) { AccountStatusChange(target, AccountListStatus.WATCHING) }
        assertThrows(IllegalArgumentException::class.java) { AccountStatusChange(target, AccountListStatus.WATCHED, point) }
        assertEquals(AccountListStatus.WATCHING, AccountListStatus.from(PlaybackMarkSnapshot(point)))
        assertNull(AccountListStatus.from(PlaybackMarkSnapshot()))
    }
    @Test fun watching_prefers_a_known_id_and_does_not_invent_an_unavailable_episode() {
        val entry = ru.seasonvar.tv.data.SeriesEntry("123", target.pageUrl, "Тест", 1, "", "")
        val details = ru.seasonvar.tv.data.SeriesDetails(entry, "10", emptyList(), emptyList(), emptyList(), emptyList(),
            listOf(ru.seasonvar.tv.data.Translation("0", "Стандартная", listOf(
                ru.seasonvar.tv.data.Episode("one", "1"), ru.seasonvar.tv.data.Episode("two", "2")))), "0")
        val chosen = AccountStatusChange.watchingMark(details, ru.seasonvar.tv.data.EntrySelection(episodeId = "two", episodeLabel = "1"))
        assertEquals("2", chosen.episode)
        assertThrows(ru.seasonvar.tv.data.MarkupChangedException::class.java) {
            AccountStatusChange.watchingMark(details, ru.seasonvar.tv.data.EntrySelection(episodeLabel = "99"))
        }
    }

}
