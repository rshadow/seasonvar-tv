package ru.seasonvar.tv.data.account

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.MarkupChangedException

class HistoryParserTest {
    private val html get() = javaClass.getResource("/account-history.html")!!.readText()
    @Test fun preserves_site_order_repeated_seasons_and_separate_visit_dates() {
        val result = AccountParser.personalList(html, PersonalFeed.HISTORY)
        assertEquals(listOf("123", "456", "123"), result.entries.map { it.id })
        assertEquals("Тестовая история", result.entries.first().title)
        assertEquals("Test History", result.entries.first().originalTitle)
        assertEquals("English Only", result.entries[1].title)
        assertEquals(2, result.entries.first().season)
        assertEquals(listOf("7", "9", "6"), result.entries.map { it.initialSelection.episodeLabel })
        assertEquals("Тестовая озвучка", result.entries.first().initialSelection.translationLabel)
        assertEquals("Вчера, 19:10", result.entries[1].historyVisitedAt)
        assertEquals(3, result.entries.map { it.key }.toSet().size)
        assertEquals(result.entries.first().url, result.entries.last().url)
        assertTrue(result.entries.all { it.subtitle.isEmpty() && it.accountMark == null })
        assertEquals("https://seasonvar.ru/?mod=history&page=2", result.nextPage)
    }
    @Test fun selects_the_immediate_next_page_and_stops_on_the_last() {
        assertEquals("https://seasonvar.ru/?mod=history&page=3", HistoryParser.parse(html, "https://seasonvar.ru/?mod=history&page=2").nextPage)
        assertNull(HistoryParser.parse(html, "https://seasonvar.ru/?mod=history&page=3").nextPage)
    }
    @Test fun authenticated_empty_history_requires_a_recognised_container() {
        val doc = Jsoup.parse(html)
        doc.select(".pgs-history-c, .pgs-history-p").empty()
        val result = HistoryParser.parse(doc.outerHtml())
        assertTrue(result.entries.isEmpty())
        assertNull(result.nextPage)
    }
    @Test(expected = SessionExpiredException::class) fun guest_history_is_not_a_successful_empty_list() {
        val doc = Jsoup.parse(html)
        doc.selectFirst(".headblock-menu")!!.html("<a href='/?mod=login'>Войти</a>")
        HistoryParser.parse(doc.outerHtml())
    }
    @Test fun changed_markup_invalid_records_and_foreign_links_are_not_silently_skipped() {
        listOf(html.replace("pgs-history-c", "changed-list"), html.replace("<b>", "<em>").replace("</b>", "</em>"),
            html.replace("<span>", "<em>").replace("</span>", "</em>"),
            html.replace("/serial-123-example", "https://foreign.example.test/serial-123-example"),
            html.replace("/?mod=history&page=2", "https://foreign.example.test/?mod=history&page=2"))
            .forEach { bad -> try { HistoryParser.parse(bad); fail() } catch (_: MarkupChangedException) { } }
    }
    @Test fun pagination_accepts_only_read_only_history_urls_on_the_original_site() {
        listOf("http://seasonvar.ru/?mod=history&page=2", "https://foreign.example.test/?mod=history&page=2",
            "https://seasonvar.ru/?mod=logout&page=2", "https://seasonvar.ru/?mod=history&dellAllHistory=true",
            "https://seasonvar.ru/resume?mod=history&page=2", "https://user@example.test/?mod=history",
            "https://seasonvar.ru/?mod=history&page=0", "https://seasonvar.ru/?mod=history&page=-1",
            "https://seasonvar.ru/?mod=history&page=text", "https://seasonvar.ru/?mod=history&page=2&page=3",
            "https://seasonvar.ru/?mod=history&mod=logout", "https://seasonvar.ru/?mod=history#anchor")
            .forEach { assertNull(it, HistoryParser.pageUrl(it)) }
        assertEquals("https://seasonvar.ru/?mod=history", HistoryParser.pageUrl("https://www.seasonvar.ru/?page=1&mod=history"))
    }
    @Test(expected = MarkupChangedException::class) fun a_gap_in_page_links_does_not_skip_history() {
        val doc = Jsoup.parse(html)
        doc.select(".pgs-history-p a[href$=page=2]").remove()
        HistoryParser.parse(doc.outerHtml())
    }
    @Test(expected = MarkupChangedException::class) fun empty_page_with_more_pages_is_not_an_infinite_list() {
        val doc = Jsoup.parse(html)
        doc.select(".pgs-history-c").empty()
        HistoryParser.parse(doc.outerHtml())
    }
    @Test fun history_keys_do_not_expose_dates_and_survive_insertion_before_a_record() {
        val before = HistoryParser.parse(html).entries
        assertTrue(before.none { it.key.contains("03.11.2026") || it.key.contains("serial-") })
        val doc = Jsoup.parse(html)
        val duplicate = doc.selectFirst(".pgs-history-c a")!!.clone()
        doc.selectFirst(".pgs-history-c")!!.appendChild(duplicate)
        val entries = HistoryParser.parse(doc.outerHtml()).entries
        assertEquals(before.map { it.key }, entries.take(3).map { it.key })
        assertEquals(4, entries.map { it.key }.toSet().size)
        val first = doc.selectFirst(".pgs-history-c a")!!.clone()
        first.selectFirst("span")!!.text("04.11.2026 12:00")
        doc.selectFirst(".pgs-history-c")!!.prependChild(first)
        assertEquals(before.map { it.key }, HistoryParser.parse(doc.outerHtml()).entries.drop(1).take(3).map { it.key })
    }

    @Test fun episode_is_taken_from_the_history_record_and_not_guessed_from_link_or_season() {
        val doc = Jsoup.parse(html)
        val link = doc.selectFirst(".pgs-history-c a")!!
        link.textNodes().forEach { it.text(it.text().replace("7 серия", "7–8 серии")) }
        assertEquals("7–8", HistoryParser.parse(doc.outerHtml()).entries.first().initialSelection.episodeLabel)
        link.textNodes().forEach { it.text(it.text().replace("7–8 серии", "")) }
        link.attr("href", "/serial-123-example.html#rewind=99_seriya")
        val entry = HistoryParser.parse(doc.outerHtml()).entries.first()
        assertNull(entry.initialSelection.episodeLabel)
        assertEquals(2, entry.season)
    }

}
