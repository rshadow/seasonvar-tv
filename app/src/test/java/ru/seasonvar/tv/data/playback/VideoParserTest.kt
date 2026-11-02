package ru.seasonvar.tv.data.playback

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.MarkupChangedException
import java.util.Base64

class VideoParserTest {
    private val request = PlaybackRequest("1", "https://seasonvar.ru/serial-1-test.html", "0", "1_seriya", "1", "Тест")
    private fun row(id: String, file: String) = JSONObject().put("id", id).put("title", "$id серия").put("file", file)
    private fun json(vararg rows: JSONObject) = JSONArray(rows.toList()).toString()
    private val html = """<div class="pgs-sinfo" data-id-season="1"></div><script>
        var pl = {'0': "/playls2/example/default.txt"};
        pl[9] = "/playls2/example/other.txt";
    </script>"""

    @Test fun chooses_exact_translation_from_original_javascript_without_executing_it() {
        assertEquals("https://seasonvar.ru/playls2/example/default.txt", VideoParser.playlistUrl(html, request))
        assertEquals("https://seasonvar.ru/playls2/example/other.txt", VideoParser.playlistUrl(html, request.copy(translationId = "9")))
    }
    @Test(expected = MarkupChangedException::class) fun rejects_wrong_season_page() {
        VideoParser.playlistUrl(html.replace("data-id-season=\"1\"", "data-id-season=\"2\""), request)
    }
    @Test(expected = MarkupChangedException::class) fun rejects_external_playlist() {
        VideoParser.playlistUrl(html.replace("/playls2/example/default.txt", "https://example.test/playlist"), request)
    }
    @Test fun finds_episode_by_id_inside_nested_folders_instead_of_using_row_index() {
        val folder = JSONObject().put("folder", JSONArray().put(row("1", "https://media.example.test/1.mp4")))
        val second = JSONObject().put("playlist", JSONArray().put(row("201", "//media.example.test/201.mp4")))
        val result = VideoParser.source(json(folder, second), request.copy(episodeId = "201_seriya", episodeLabel = "201"))
        assertEquals("https://media.example.test/201.mp4", result.url)
    }
    @Test fun decodes_playerjs_base64_and_public_marker_and_preserves_uri_query() {
        val url = "//media.example.test/episode.mp4?example=one%2Btwo&quality=sd"
        val encoded = Base64.getEncoder().encodeToString(url.toByteArray())
        val file = "#2" + encoded.take(10) + "//b2xvbG8=" + encoded.drop(10)
        assertEquals("https:" + url, VideoParser.source(json(row("1", file)), request).url)
    }
    @Test fun accepts_named_episode_and_selects_first_advertised_quality() {
        val data = json(row("Основной", "[480p]https://media.example.test/480.mp4,[720p]https://media.example.test/720.mp4"))
        assertEquals("https://media.example.test/480.mp4", VideoParser.source(data, request.copy(episodeLabel = "Основной")).url)
    }
    @Test(expected = VideoUnavailableException::class) fun does_not_fall_back_to_a_different_episode() {
        VideoParser.source(json(row("2", "https://media.example.test/2.mp4")), request)
    }
    @Test(expected = VideoUnavailableException::class) fun rejects_ambiguous_duplicate_episode_ids() {
        VideoParser.source(json(row("1", "https://media.example.test/a.mp4"), row("1", "https://media.example.test/b.mp4")), request)
    }
    @Test(expected = VideoUnavailableException::class) fun unsupported_encoding_is_not_treated_as_a_playable_url() {
        VideoParser.source(json(row("1", "#3unsupported")), request)
    }
    @Test(expected = MarkupChangedException::class) fun rejects_user_information_in_media_uri() {
        VideoParser.source(json(row("1", "https://user:password@media.example.test/1.mp4")), request)
    }
    @Test(expected = MarkupChangedException::class) fun rejects_corrupt_base64_without_exposing_it_in_errors() {
        VideoParser.source(json(row("1", "#2!bad!")), request)
    }
}
