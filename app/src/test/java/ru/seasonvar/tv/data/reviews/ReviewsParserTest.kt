package ru.seasonvar.tv.data.reviews

import okhttp3.FormBody
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.MarkupChangedException
import ru.seasonvar.tv.data.SeriesEntry

class ReviewsParserTest {
    private val html = javaClass.getResource("/series-reviews.html")!!.readText()
    private val entry = SeriesEntry("101", "https://seasonvar.ru/serial-101-Test.html", "Тест", null, "", "")

    @Test fun reads_count_author_optional_date_cursor_and_plain_text_paragraphs() {
        val page = ReviewsParser.first(html, entry)
        assertEquals(9, page.total)
        assertEquals("41", page.nextCursor)
        assertEquals(2, page.entries.size)
        val first = page.entries.first()
        assertEquals("Автор 1", first.author)
        assertEquals("13 ноября 2026", first.date)
        assertEquals("Первый абзац с выделением.\n\nВторой абзац & продолжение.", first.text)
        assertFalse(first.text.contains("NEVER_SHOW") || first.text.contains("Стаж"))
        assertNull(page.entries.last().date)
    }

    @Test fun accepts_verified_empty_reviews_and_rejects_wrong_pages_or_missing_cursor() {
        val empty = "<div class='pgs-sinfo' data-id-season='101'></div><li data-tabt='reviews'>Рецензии (0)</li><li data-tabr='reviews'></li>"
        val page = ReviewsParser.first(empty, entry)
        assertEquals(0, page.total)
        assertTrue(page.entries.isEmpty())
        assertNull(page.nextCursor)
        for (bad in listOf(empty.replace("(0)", "(9)"), "<html>blocked</html>", html.replace("data-id-season=\"101\"", "data-id-season=\"102\""),
            html.replace("var review", "var other"))) {
            try { ReviewsParser.first(bad, entry); fail("Expected markup rejection") }
            catch (_: MarkupChangedException) { }
        }
    }

    @Test fun additional_response_is_an_html_fragment_not_a_full_page_or_notification() {
        val more = ReviewsParser.more("<div class='pgs-review-post'>Продолжение.<br>Ещё строка.</div>")
        assertEquals("Продолжение.\nЕщё строка.", more.single().text)
        assertNull(more.single().author)
        assertTrue(ReviewsParser.more(" ").isEmpty())
        for (bad in listOf("<html>blocked</html>", html, "<div class='pgs-review-post'><div class='pgs-review-name'>Только имя</div></div>")) {
            try { ReviewsParser.more(bad); fail("Expected markup rejection") }
            catch (_: MarkupChangedException) { }
        }
    }

    @Test fun identical_posts_remain_separate_and_have_unique_ui_keys() {
        val page = ReviewsParser.more("<div class='pgs-review-post'>Одинаково</div>".repeat(2))
        assertEquals(2, page.size)
        assertEquals(2, page.map { it.key }.distinct().size)
        val appended = uniqueReviews(page + page)
        assertEquals(4, appended.map { it.key }.distinct().size)
        assertEquals(page.map { it.key }, appended.take(2).map { it.key })
    }

    @Test fun read_request_uses_the_season_id_and_only_the_original_read_fields() {
        val request = ReviewsRequest.more(entry, "41")
        assertEquals("https://seasonvar.ru/ajax.php", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals(entry.url, request.header("Referer"))
        assertEquals("XMLHttpRequest", request.header("X-Requested-With"))
        val body = request.body as FormBody
        assertEquals(mapOf("review_id_serial" to "101", "last_review" to "41"),
            (0 until body.size).associate { body.name(it) to body.value(it) })
    }

    @Test fun rejects_foreign_pages_mismatched_seasons_and_non_numeric_cursors() {
        for ((target, cursor) in listOf(entry.copy(url = "https://example.com/serial-101-Test.html") to "41",
            entry.copy(id = "102") to "41", entry to "41&write=1")) {
            try { ReviewsRequest.more(target, cursor); fail("Expected invalid request rejection") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
