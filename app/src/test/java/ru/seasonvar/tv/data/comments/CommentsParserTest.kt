package ru.seasonvar.tv.data.comments

import okhttp3.FormBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.MarkupChangedException
import ru.seasonvar.tv.data.SeriesEntry

class CommentsParserTest {
    private val entry = SeriesEntry("123", "https://seasonvar.ru/serial-123-example.html", "Пример", 1, "", "")

    @Test fun first_page_preserves_reply_order_and_both_kinds_of_spoiler() {
        val page = CommentsParser.first("""
            <div class="pgs-sinfo" data-id-season="123"></div>
            <div data-tabr="comments"><div data-cmt="wrap">
              <button data-click="cmtPage" data-page="1" class="act">1</button>
              <button data-click="cmtPage" data-page="2">2</button>
              <div class="svc_comment" data-cmtid="1">
                <div class="svc_nick"><span class="svc_date">Вчера</span><a data-nick="true">Зритель</a></div>
                <div class="svc_msg">До <details><summary>Спойлер</summary><div>Скрыто</div></details> после<script>ignored</script></div>
              </div>
              <div class="svc_comment svc_comment_sub" data-cmtid="2">
                <div class="svc_msg compress spoiler">Ответ<br>продолжение</div>
                <span data-click="cmtShowBigText">Спойлер</span>
              </div>
            </div></div>
        """.trimIndent(), entry)
        assertEquals(2, page.next)
        assertEquals(listOf("1", "2"), page.entries.map { it.id })
        assertEquals("Зритель", page.entries[0].author)
        assertEquals("Вчера", page.entries[0].date)
        assertFalse(page.entries[0].reply)
        assertTrue(page.entries[1].reply)
        assertEquals(listOf(CommentPart.Text("До"), CommentPart.Spoiler(listOf(CommentPart.Text("Скрыто"))),
            CommentPart.Text("после")), page.entries[0].parts)
        assertEquals(listOf(CommentPart.Spoiler(listOf(CommentPart.Text("Ответ\nпродолжение")))), page.entries[1].parts)
    }

    @Test fun next_page_checks_the_server_cursor_and_stops_at_the_end() {
        val html = """<button data-click="cmtPage" data-page="2" class="act">2</button>
            <div class="svc_comment" data-cmtid="3"><div class="svc_msg">Последний</div></div>"""
        val response = JSONObject().put("status", "ok").put("comm", html).toString()
        assertNull(CommentsParser.more(response, 2).next)
        assertThrows(MarkupChangedException::class.java) { CommentsParser.more(response, 3) }
        assertThrows(MarkupChangedException::class.java) { CommentsParser.more("""{"status":"error","comm":""}""", 2) }
    }

    @Test fun pagination_uses_the_season_id_and_original_referer() {
        val request = CommentsRepository.moreRequest(entry, 2)
        val form = request.body as FormBody
        assertEquals("https://seasonvar.ru/ajax.php", request.url.toString())
        assertEquals(entry.url, request.header("Referer"))
        assertEquals(mapOf("pageComm" to "2", "seasId" to "123"),
            (0 until form.size).associate { form.name(it) to form.value(it) })
    }
}
