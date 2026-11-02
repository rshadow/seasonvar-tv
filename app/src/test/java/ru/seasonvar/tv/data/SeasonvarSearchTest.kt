package ru.seasonvar.tv.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SeasonvarSearchTest {
    private val fixture = javaClass.getResource("/search.json")!!.readText()
    private val query = "Игра престолов"
    @get:Rule val folder = TemporaryFolder()

    @Test fun response_preserves_seasons_exact_paths_and_cleans_html() {
        val entries = SeasonvarParser.search(fixture, query)
        assertEquals(listOf("2219", "3991", "6675", "8676", "11242", "13535", "15557",
            "16577", "22063", "18639", "25127", "28991", "32344", "42526"), entries.map { it.id })
        assertEquals("Игра престолов", entries.first().title)
        assertEquals("Game of Thrones", entries.first().originalTitle)
        assertEquals(1, entries.first().season)
        assertEquals("https://seasonvar.ru/serial-2219--He_vse_starki_umrut----_psxskbl-00001---sezon.html", entries.first().url)
        assertEquals("https://cdn.svstat.net/oblojka/2219.jpg", entries.first().posterUrl)
        assertEquals("Игра престолов", entries[8].title)
        assertEquals(8, entries[8].season)
        assertNull(entries[7].season)
    }

    @Test fun naruto_prefixes_preserve_series_despite_actor_and_tag_separators_without_ids() {
        val counts = mapOf("n" to 50, "na" to 50, "nar" to 50, "naru" to 37, "narut" to 6, "naruto" to 5)
        counts.forEach { (query, count) ->
            val response = javaClass.getResource("/search-$query.json")!!.readText()
            val root = JSONObject(response)
            val expectedPaths = root.getJSONArray("data").let { paths ->
                (0 until paths.length()).map { paths.getString(it) }.filter { it.startsWith("serial-") }
            }
            val entries = SeasonvarParser.search(response, query)
            assertEquals(query, count, entries.size)
            assertEquals(query, expectedPaths.map { "https://seasonvar.ru/" + it }, entries.map { it.url })
        }
    }

    @Test fun actor_only_response_is_a_valid_empty_series_result() {
        val response = javaClass.getResource("/search-actor.json")!!.readText()
        assertTrue(SeasonvarParser.search(response, "Narumi Takahira").isEmpty())
    }

    @Test fun null_arrays_and_empty_arrays_are_valid_empty_results() {
        assertTrue(SeasonvarParser.search("""{"query":"q","suggestions":null,"data":null,"id":null}""", "q").isEmpty())
        assertTrue(SeasonvarParser.search("""{"query":"q","suggestions":{"valu":[],"kp":[]},"data":[],"id":[]}""", "q").isEmpty())
    }

    @Test fun rejects_invalid_json_mismatched_arrays_and_wrong_query() {
        val broken = listOf("<html>Blocked</html>", "{}", fixture.replace("Игра", "Другая"),
            JSONObject(fixture).apply { getJSONArray("id").remove(0) }.toString(),
            JSONObject(fixture).apply { getJSONObject("suggestions").getJSONArray("kp").remove(0) }.toString(),
            JSONObject(fixture).apply { getJSONArray("id").put(0, 2219) }.toString(),
            JSONObject(fixture).apply { getJSONObject("suggestions").getJSONArray("kp").put(0, 7.67) }.toString())
        broken.forEach { json -> assertThrows(MarkupChangedException::class.java) { SeasonvarParser.search(json, query) } }
    }

    @Test fun rejects_external_paths_or_ids_that_disagree_with_paths() {
        listOf("https://other.example/serial-2219-test.html", "//other.example/serial-2219-test.html",
            "javascript:alert(1)", "serial-3991-test.html").forEach { path ->
            val json = JSONObject(fixture).apply { getJSONArray("data").put(0, path) }.toString()
            assertThrows(MarkupChangedException::class.java) { SeasonvarParser.search(json, query) }
        }
    }

    @Test fun falls_back_to_original_title_and_takes_season_from_label_only() {
        val json = JSONObject(fixture).apply {
            getJSONObject("suggestions").getJSONArray("valu").put(0, " / Original &amp; title (2 сезон)")
        }.toString()
        val entry = SeasonvarParser.search(json, query).first()
        assertEquals("Original & title", entry.title)
        assertNull(entry.originalTitle)
        assertEquals(2, entry.season)
    }

    @Test fun request_encodes_user_query_and_blank_query_does_not_use_network() = runBlocking {
        var requested: String? = null
        val input = "a & б/?+#"
        val repository = CatalogRepository(folder.newFolder(), object : HtmlSource {
            override suspend fun get(url: String): String {
                requested = url
                return JSONObject(fixture).put("query", input).toString()
            }
            override suspend fun sidebar(feed: PublicFeed): String = error("Unexpected sidebar request")
        })
        assertTrue(repository.search("  ").isEmpty())
        assertNull(requested)
        assertEquals(14, repository.search(input).size)
        val url = requested!!.toHttpUrl()
        assertEquals("seasonvar.ru", url.host)
        assertEquals("/autocomplete.php", url.encodedPath)
        assertEquals(1, url.querySize)
        assertEquals(input, url.queryParameter("query"))
    }
}
