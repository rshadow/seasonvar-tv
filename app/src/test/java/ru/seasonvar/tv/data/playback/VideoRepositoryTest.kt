package ru.seasonvar.tv.data.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VideoRepositoryTest {
    @Test fun history_uses_the_same_page_and_selected_playlist_leaf_without_extra_requests() = runBlocking {
        val calls = mutableListOf<String>()
        val request = PlaybackRequest("123", "https://seasonvar.ru/serial-123-test.html", "9", "201_seriya", "201", "Тест")
        val repository = VideoRepository { url, _ ->
            calls += url
            if (url == request.pageUrl) """<div class="pgs-sinfo" data-id-season="123" data-id-serial="40"></div><script>var pl = {'9': '/list.txt'};</script>"""
            else """[{"folder":[{"id":"200","vars":800,"file":"https://media.example.test/old.mp4"},{"id":"201","vars":"900","file":"https://media.example.test/new.mp4"}]}]"""
        }
        val source = repository.resolve(request)
        assertEquals("https://media.example.test/new.mp4", source.url)
        assertEquals(PlaybackHistoryEvent("123", request.pageUrl, "40", "900", "9", "201"), source.history)
        assertEquals(listOf(request.pageUrl, "https://seasonvar.ru/list.txt"), calls)
    }

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
