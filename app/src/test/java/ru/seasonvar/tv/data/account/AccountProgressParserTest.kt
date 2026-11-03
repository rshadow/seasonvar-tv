package ru.seasonvar.tv.data.account

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.*

class AccountProgressParserTest {
    private val html get() = javaClass.getResource("/account-resume-current.html")!!.readText()
    private fun details(id: String = "123") = SeriesDetails(
        SeriesEntry(id, "https://seasonvar.ru/serial-$id-test.html", "Тест", 7, "", ""), "100",
        emptyList(), emptyList(), emptyList(), listOf(
            SeasonOption("123", "", "Сезон 7", 7), SeasonOption("456", "", "Сезон 0", 0),
            SeasonOption("999", "", "Сезон 99", 99), SeasonOption("missing", "", "Сезон 1", 1)),
        listOf(Translation("0", "Стандартный", (1..5).map { Episode("${it}_seriya", "$it") })), "0")
    @Test fun recognises_only_valid_episode_counters_and_keeps_other_values_unknown() {
        assertEquals(ProgressCounter(3, 10), AccountProgressParser.episodes("Отмечено 3 из 10"))
        assertEquals(ProgressCounter(0, 10), AccountProgressParser.episodes("0 из 10"))
        listOf("", "Сезон 2", "сезоны 2 из 5", "-1 из 10", "11 из 10", "0 из 0", "9999999999999 из 10")
            .forEach { assertNull(it, AccountProgressParser.episodes(it)) }
    }
    @Test fun snapshot_retains_scoped_episode_counters_and_explicit_watched_seasons() {
        val snapshot = AccountProgressParser.snapshot(html)
        assertEquals(setOf("123", "456", "999"), snapshot.markedSeasonIds)
        assertEquals(setOf("456", "999"), snapshot.completedSeasonIds)
        assertEquals(mapOf("123" to 2), snapshot.markedSeasonNumbers)
        assertEquals(ProgressCounter(3, 10), snapshot.episodeCounters["123"])
        assertEquals(ProgressCounter(1, 1), snapshot.episodeCounters["456"])
    }
    @Test fun uses_marked_season_number_instead_of_counting_completed_seasons() {
        val progress = AccountProgressParser.forDetails(details(), AccountProgressParser.snapshot(html))
        assertEquals(ProgressCounter(2, 4), progress.seasons)
        assertEquals(SeasonEpisodeProgress("123", ProgressCounter(3, 10)), progress.episodes)
    }
    @Test fun changing_season_uses_its_mark_and_leaves_unknown_season_number_absent() {
        val snapshot = AccountProgressParser.snapshot(html)
        val progress = AccountProgressParser.forDetails(details("456"), snapshot)
        assertNull(progress.seasons)
        assertEquals(SeasonEpisodeProgress("456", ProgressCounter(1, 1)), progress.episodes)
    }
    @Test fun explicit_watched_flag_fills_full_episode_progress_when_total_is_known() {
        val progress = AccountProgressParser.forDetails(details("999"), AccountProgressParser.snapshot(html))
        assertEquals(SeasonEpisodeProgress("999", ProgressCounter(5, 5)), progress.episodes)
    }
    @Test fun authorised_missing_mark_means_zero_but_unknown_episode_total_is_not_invented() {
        val snapshot = AccountProgressParser.snapshot(html)
        assertEquals(SeasonEpisodeProgress("missing", ProgressCounter(0, 5)), AccountProgressParser.forDetails(details("missing"), snapshot).episodes)
        val unknown = details("missing").copy(translations = listOf(Translation("0", "Стандартный", null)))
        assertNull(AccountProgressParser.forDetails(unknown, snapshot).episodes)
    }
    @Test fun malformed_or_conflicting_mark_is_not_replaced_by_zero() {
        val doc = Jsoup.parse(html)
        doc.selectFirst("[data-tabr=marks-new] .pgs-marks-current")!!.text("Непонятная отметка")
        val snapshot = AccountProgressParser.snapshot(doc.outerHtml())
        assertNull(AccountProgressParser.forDetails(details(), snapshot).episodes)
        assertEquals(ProgressCounter(2, 4), AccountProgressParser.forDetails(details(), snapshot).seasons)
        val other = Jsoup.parse(html)
        val duplicate = other.selectFirst("[data-tabr=marks-new] .pgs-marks-el")!!.clone().attr("data-marksel", "124")
        duplicate.selectFirst(".pgs-marks-current")!!.text("4 из 12")
        other.selectFirst("[data-tabr=marks-new]")!!.appendChild(duplicate)
        assertNull(AccountProgressParser.forDetails(details(), AccountProgressParser.snapshot(other.outerHtml())).episodes)
        duplicate.selectFirst(".pgs-marks-seas")!!.text("3 сезон")
        assertNull(AccountProgressParser.forDetails(details(), AccountProgressParser.snapshot(other.outerHtml())).seasons)
    }
    @Test fun duplicate_season_links_and_other_series_marks_do_not_change_season_progress() {
        val page = details()
        val duplicate = page.copy(seasons = page.seasons + page.seasons.first())
        val snapshot = AccountProgressParser.snapshot(html)
        assertEquals(AccountProgressParser.forDetails(page, snapshot), AccountProgressParser.forDetails(duplicate, snapshot))
        val onlyThis = page.copy(seasons = listOf(page.seasons.first()))
        assertEquals(ProgressCounter(2, 1), AccountProgressParser.forDetails(onlyThis, snapshot).seasons)
    }
    @Test(expected = SessionExpiredException::class) fun guest_marks_cannot_produce_zero_progress() {
        AccountProgressParser.snapshot("<div class='headblock-menu'><a href='/?mod=login'>Вход</a></div>")
    }
    @Test fun blocked_page_does_not_turn_leftover_episode_data_into_a_new_total() {
        val page = details("missing").copy(playbackUnavailableReason = "Недоступно")
        val progress = AccountProgressParser.forDetails(page, AccountProgressParser.snapshot(html))
        assertNull(progress.seasons)
        assertNull(progress.episodes)
    }

}
