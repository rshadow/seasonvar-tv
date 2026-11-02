package ru.seasonvar.tv.data.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VideoRepositoryTest {
    @Test fun refreshes_page_and_playlist_on_each_launch_with_the_correct_referer() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val request = PlaybackRequest("1", "https://seasonvar.ru/serial-1-test.html", "0", "1_seriya", "1", "Тест")
        val playlist = "https://seasonvar.ru/example.txt"
        val repository = VideoRepository { url, referer ->
            calls += url to referer
            if (url == request.pageUrl) """<div class="pgs-sinfo" data-id-season="1"></div><script>var pl = {'0': '/example.txt'};</script>"""
            else """[{"id":"1","file":"https://media.example.test/one.mp4"}]"""
        }
        repeat(2) { assertEquals("https://media.example.test/one.mp4", repository.resolve(request).url) }
        assertEquals(listOf(request.pageUrl to "https://seasonvar.ru/", playlist to request.pageUrl,
            request.pageUrl to "https://seasonvar.ru/", playlist to request.pageUrl), calls)
    }
    @Test fun rejects_foreign_pages_before_any_network_request() = runBlocking {
        val repository = VideoRepository { _, _ -> error("Unexpected request") }
        val request = PlaybackRequest("1", "https://foreign.example.test/serial-1.html", "0", "1_seriya", "1", "Тест")
        try { repository.resolve(request); fail("Expected rejection") }
        catch (_: ru.seasonvar.tv.data.MarkupChangedException) { }
    }

}
