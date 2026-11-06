package ru.seasonvar.tv.data.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ReleaseNotesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun item(version: String, body: Any = "- First\n- Second\n\nParagraph") = JSONObject()
        .put("tag_name", "v$version").put("draft", false).put("prerelease", false)
        .put("published_at", "2026-11-07T12:00:00Z").put("body", body)
    private fun page(vararg items: JSONObject) = JSONArray(items.toList()).toString()
    private fun repository(directory: File, fetch: suspend (Int) -> String) = UpdateRepository(object : UpdateSource {
        override suspend fun latest(): String? = null
        override suspend fun releases(page: Int): String = fetch(page)
        override suspend fun download(asset: ReleaseAsset, destination: File, progress: (Int) -> Unit) = error("No APK")
    }, directory) { _, _ -> error("No APK verification") }

    @Test fun public_stable_descriptions_do_not_require_apk_assets_and_preserve_text() {
        val (notes, more) = ReleaseNotesParser.parsePage(page(item("0.4.0"), item("0.5.0", JSONObject.NULL),
            item("0.6.0").put("draft", true), item("0.7.0").put("prerelease", true),
            item("0.8.0-beta"), item("0.9.0").put("published_at", JSONObject.NULL)))
        assertFalse(more)
        assertEquals(listOf("0.4.0", "0.5.0"), notes.map { it.version })
        assertEquals("- First\n- Second\n\nParagraph", notes.first().body)
        assertEquals("", notes.last().body)
        assertEquals(notes, ReleaseNotesParser.decode(ReleaseNotesParser.encode(notes)))
    }

    @Test fun includes_current_and_all_newer_versions_sorted_numerically_not_by_publication_order() {
        val notes = ReleaseNotesParser.parsePage(page(item("0.9.0"), item("0.2.0"), item("0.10.0"), item("0.3.1"))).first
        val relevant = ReleaseNotesParser.relevant(notes, "0.3.1-devab12cd3")
        assertEquals(listOf("0.10.0", "0.9.0", "0.3.1"), relevant.map { it.version })
        assertTrue(relevant.last().isInstalled("0.3.1-devab12cd3"))
        assertFalse(relevant.first().isInstalled("0.3.1-devab12cd3"))
        assertFalse(GitHubRelease("0.3.1", null).newerThan("0.3.1-devab12cd3"))
        assertTrue(GitHubRelease("0.4.0", null).newerThan("0.3.1-devab12cd3"))
        assertNull(ReleaseVersion.parse("0.3.1-devab12cd3"))
        assertNull(ReleaseVersion.installed("0.3.1-beta"))
    }

    @Test fun malformed_json_dates_and_cache_schema_are_not_accepted() {
        listOf("{}", "<html>error</html>", page(item("0.4.0").put("published_at", "not a date"))).forEach {
            assertThrows(UpdateFormatException::class.java) { ReleaseNotesParser.parsePage(it) }
        }
        assertThrows(UpdateFormatException::class.java) { ReleaseNotesParser.decode("{\"schema\":2,\"releases\":[]}") }
    }

    @Test fun pagination_continues_past_older_releases_and_cache_survives_a_new_repository_instance() = runBlocking {
        val directory = temporary.newFolder()
        val requested = mutableListOf<Int>()
        val first = JSONArray().apply { repeat(100) { put(item("0.1.0")) } }.toString()
        val repository = repository(directory) { number ->
            requested += number
            when (number) { 1 -> first; 2 -> page(item("0.4.0"), item("0.10.0")); else -> error("Unexpected page") }
        }
        val notes = repository.notes()
        assertEquals(listOf(1, 2), requested)
        assertEquals(listOf("0.10.0", "0.4.0"), ReleaseNotesParser.relevant(notes, "0.4.0").map { it.version })
        assertEquals(notes, repository(directory) { error("Cache must not use network") }.cachedNotes())
    }

    @Test fun incomplete_refresh_or_cancellation_keeps_previous_cache() = runBlocking {
        val directory = temporary.newFolder()
        val original = repository(directory) { page(item("0.4.0")) }.notes()
        for (failure in listOf(IOException(), CancellationException(), UpdateFormatException())) {
            val repository = repository(directory) { number ->
                if (number == 1) JSONArray().apply { repeat(100) { put(item("0.5.0")) } }.toString() else throw failure
            }
            try { repository.notes(); fail("Expected failed refresh") }
            catch (error: Exception) { assertEquals(failure.javaClass, error.javaClass) }
            assertEquals(original, repository.cachedNotes())
            assertFalse(File(directory, "release-notes.part").exists())
        }
    }

    @Test fun malformed_cache_is_ignored_and_unwritable_cache_does_not_hide_network_result() = runBlocking {
        val directory = temporary.newFolder()
        File(directory, "release-notes.json").writeText("broken")
        val repository = repository(directory) { page(item("0.4.0")) }
        assertTrue(repository.cachedNotes().isEmpty())
        assertEquals("0.4.0", repository.notes().single().version)
        val notDirectory = temporary.newFile()
        assertEquals("0.4.0", repository(notDirectory) { page(item("0.4.0")) }.notes().single().version)
    }
}
