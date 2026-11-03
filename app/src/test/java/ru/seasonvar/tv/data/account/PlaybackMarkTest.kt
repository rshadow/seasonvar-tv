package ru.seasonvar.tv.data.account

import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.MarkupChangedException
import ru.seasonvar.tv.data.SiteClient

class PlaybackMarkTest {
    private val page = "https://seasonvar.ru/serial-123-Test.html"
    private val mark = PlaybackMark("123", page, "7", "298", 754)
    private fun html(href: String, translation: Any = "298") = """
        <div class="pgs-sinfo" data-id-season="123"></div>
        <script>var mark = {"href":"$href","trans":"$translation"};</script>
    """.trimIndent()

    @Test fun form_uses_site_episode_label_translation_and_minute_second_fields() {
        assertEquals(mapOf("pauseadd" to "true", "id" to "123", "seria" to "7", "tran" to "298",
            "minute" to "12", "second" to "34"), mark.form())
        val request = SiteClient.markRequest(mark)
        assertEquals("https://seasonvar.ru/jsonMark.php", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals(page, request.header("Referer"))
        assertEquals("XMLHttpRequest", request.header("X-Requested-With"))
        val body = request.body as okhttp3.FormBody
        assertEquals(mark.form(), (0 until body.size).associate { body.name(it) to body.value(it) })
    }

    @Test fun episode_mark_zeroes_time_and_does_not_mark_the_whole_season() {
        val form = mark.copy(positionSeconds = 0).form()
        assertEquals("7", form["seria"])
        assertEquals("0", form["minute"])
        assertEquals("0", form["second"])
        assertFalse(form.containsKey("watched"))
        assertFalse(form.containsKey("delId"))
    }

    @Test fun reads_timed_mark_from_current_season_and_supports_relative_hash() {
        val hash = "#rewind=7_seriya_na_12_minute_34_sekunde"
        assertEquals(mark, PlaybackMarkParser.parse(html(page + hash), "123", page))
        assertEquals(mark, PlaybackMarkParser.parse(html(hash), "123", page))
        assertEquals(mark.copy(translationId = "0", positionSeconds = 0),
            PlaybackMarkParser.parse(html("#rewind=7_seriya", ""), "123", page))
    }

    @Test fun absent_and_non_playback_marks_are_not_resume_positions() {
        for (href in listOf("", "#rewind=-1_seriya", "#rewind=-2_seriya", "#rewind=-3_seriya")) {
            assertNull(PlaybackMarkParser.parse(html(href), "123", page))
        }
    }

    @Test fun only_explicit_success_message_is_accepted() {
        PlaybackMarkParser.requireSuccess("""{"msg":"success","id":42}""")
        for (json in listOf("", "<html>login</html>", "{}", """{"success":true}""", """{"msg":"error"}""")) {
            try { PlaybackMarkParser.requireSuccess(json); fail(json) } catch (_: MarkSaveRejectedException) { }
        }
    }

    @Test fun rejects_wrong_season_foreign_links_invalid_times_and_ambiguous_markup() {
        val invalid = listOf(
            html("https://foreign.example.test/serial-123-Test.html#rewind=7_seriya"),
            html("https://seasonvar.ru/serial-124-Test.html#rewind=7_seriya"),
            html("#rewind=7_seriya_na_1_minute_60_sekunde"),
            html("#rewind=7_seriya_na_999999999999_minute_1_sekunde"),
            html("#rewind=unknown"), html("#rewind=7_seriya").replace("data-id-season=\"123\"", "data-id-season=\"124\""),
            html("#rewind=7_seriya") + "<script>var mark={};</script>",
            html("#rewind=7_seriya").replace("var mark", "var changed"),
        )
        for (source in invalid) {
            try { PlaybackMarkParser.parse(source, "123", page); fail() } catch (_: MarkupChangedException) { }
        }
    }

    @Test fun untrusted_targets_status_codes_and_trailer_translation_never_create_write_requests() {
        val invalid = listOf(
            { mark.copy(seasonId = "124") },
            { mark.copy(pageUrl = "https://foreign.example.test/serial-123-Test.html") },
            { mark.copy(episode = "-2") },
            { mark.copy(translationId = "68") },
            { mark.copy(positionSeconds = -1) },
        )
        for (create in invalid) {
            try { create(); fail() } catch (_: IllegalArgumentException) { }
        }
    }
}
