package ru.seasonvar.tv.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SeriesDetailsParserTest {
    private val html get() = javaClass.getResource("/series-details.html")!!.readText()
    private val entry = SeriesEntry("2219", "https://seasonvar.ru/serial-2219-test.html", "Игра престолов", 1, "", "")

    @Test fun reads_original_description_metadata_cast_and_exact_season_paths() {
        val result = SeriesDetailsParser.parse(html, entry)
        assertEquals(listOf("Первый абзац описания.", "Второй абзац описания."), result.paragraphs)
        assertEquals("Game of Thrones", result.entry.originalTitle)
        assertEquals("1009", result.serialId)
        assertEquals("2011", result.facts.first { it.first == "Вышел" }.second)
        assertEquals("США", result.facts.first { it.first == "Страна" }.second)
        assertEquals(2, result.actors.size)
        assertEquals(8, result.seasons.size)
        assertEquals("3991", result.seasons[1].id)
        assertEquals("https://seasonvar.ru/serial-3991-Vse_starki_umrut_psfnisz-00002-sezon.html", result.seasons[1].url)
        assertEquals(2, result.seasons[1].number)
        assertEquals("https://cdn.svstat.net/oblojka/2219.jpg", result.entry.posterUrl)
    }

    @Test fun reads_large_actor_photos_including_hidden_cast_and_missing_images() {
        val withPhotos = html.replace("<a>Первый актёр</a>",
            "<a href=\"/actor/Первый\"><img src=\"//cdn.svstat.net/a/s/1/123.jpg\">Первый актёр</a>")
        val actors = SeriesDetailsParser.parse(withPhotos, entry).actors
        assertEquals(listOf("Первый актёр", "Второй актёр"), actors.map { it.name })
        assertEquals("https://cdn.svstat.net/a/1/123.jpg", actors.first().photoUrl)
        assertEquals(ActorPages.url("/actor/Первый"), actors.first().url)
        assertEquals(ActorImages.PLACEHOLDER_URL, actors.last().photoUrl)
        assertNull(actors.last().url)
    }

    @Test fun preserves_cast_without_photos_and_does_not_invent_actors() {
        val actors = SeriesDetailsParser.parse(html, entry).actors
        assertTrue(actors.all { it.photoUrl == ActorImages.PLACEHOLDER_URL })
        val withoutCast = html.replace(Regex("<div class=\"pgs-sinfo-actor\">.*?</div></div>"), "")
        assertTrue(SeriesDetailsParser.parse(withoutCast, entry).actors.isEmpty())
    }

    @Test fun reads_translations_and_orders_episodes_by_next_links_instead_of_json_key_order() {
        val result = SeriesDetailsParser.parse(html, entry)
        assertEquals("0", result.defaultTranslationId)
        assertEquals(listOf("Стандартный", "Субтитры", "Трейлеры"), result.translations.map { it.label })
        assertEquals((1..10).map(Int::toString), result.translations.first { it.id == "0" }.episodes!!.map { it.label })
        assertEquals("Основной", result.translations.first { it.id == "68" }.episodes!!.single().label)
    }

    @Test fun reads_array_episode_format_with_implicit_default_translation() {
        val single = javaClass.getResource("/series-single.html")!!.readText()
        val result = SeriesDetailsParser.parse(single, entry.copy(id = "16577"))
        assertEquals("0", result.defaultTranslationId)
        assertEquals("Стандартный", result.translations.single().label)
        assertEquals("1_seriya", result.translations.single().episodes!!.single().id)
    }

    @Test fun reads_both_optional_ratings_without_confusing_scores_and_vote_counts() {
        val rated = javaClass.getResource("/series-ratings.html")!!.readText()
        val result = SeriesDetailsParser.parse(rated, entry.copy(id = "2779", title = "Наруто"))
        assertEquals("8.40", result.facts.first { it.first == "IMDB" }.second)
        assertEquals("7.81", result.facts.first { it.first == "КиноПоиск" }.second)
        assertFalse(result.facts.any { it.second in setOf("165000", "141607") })
        assertEquals(220, result.translations.single().episodes!!.size)
        assertEquals("220", result.translations.single().episodes!!.last().label)
    }

    @Test fun missing_or_empty_ratings_do_not_create_placeholder_scores() {
        val rated = javaClass.getResource("/series-ratings.html")!!.readText()
        val noScores = rated.replace("<span>8.40</span>", "<span></span>")
            .replace(Regex("""<div class="pgs-sinfo_list rating">КиноПоиск:.*?</div>""", RegexOption.DOT_MATCHES_ALL), "")
        val result = SeriesDetailsParser.parse(noScores, entry.copy(id = "2779"))
        assertFalse(result.facts.any { it.first in setOf("IMDB", "КиноПоиск") })
        assertNotNull(result.entry)
    }

    @Test fun absent_episode_data_is_distinct_from_verified_empty_list() {
        val without = SeriesDetailsParser.parse(html.replace("var arEpisodes", "var otherData"), entry)
        assertNull(without.translations.first().episodes)
        val empty = html.replace(Regex("""var arEpisodes = .*?;</script>"""), "var arEpisodes = {\"0\":{}};</script>")
        assertEquals(emptyList<Episode>(), SeriesDetailsParser.parse(empty, entry).translations.first().episodes)
    }

    @Test fun supports_single_season_without_links_and_preserves_long_description() {
        val long = "Текст описания. ".repeat(300)
        val changed = html.replace("Первый абзац описания.", long)
            .replace(Regex("""<div class="pgs-seaslist">.*?<ul class="pgs-trans""", RegexOption.DOT_MATCHES_ALL), "<ul class=\"pgs-trans")
        val result = SeriesDetailsParser.parse(changed, entry)
        assertEquals(1, result.seasons.size)
        assertEquals(long.trim(), result.paragraphs.first())
    }

    @Test(expected = MarkupChangedException::class) fun rejects_wrong_season() {
        SeriesDetailsParser.parse(html.replace("data-id-season=\"2219\"", "data-id-season=\"2218\""), entry)
    }

    @Test(expected = MarkupChangedException::class) fun rejects_foreign_season_link() {
        SeriesDetailsParser.parse(html.replace("href=\"/serial-3991", "href=\"https://example.com/serial-3991"), entry)
    }

    @Test(expected = MarkupChangedException::class) fun rejects_broken_episode_chain_instead_of_showing_partial_list() {
        SeriesDetailsParser.parse(html.replace("\"next\": \"2\"", "\"next\": \"unknown\""), entry)
    }

    @Test fun details_reuse_description_page_cache() = runBlocking {
        val directory = Files.createTempDirectory("details-test").toFile()
        try {
            var calls = 0
            val source = object : HtmlSource {
                override suspend fun get(url: String): String { calls++; return html }
                override suspend fun sidebar(feed: PublicFeed) = error("Unexpected request")
            }
            val repository = CatalogRepository(directory, source)
            repository.description(entry)
            val result = repository.details(entry)
            assertEquals(1, calls)
            assertEquals(8, result.seasons.size)
        } finally { directory.deleteRecursively() }
    }
    @Test fun preserves_site_playback_restriction_without_inventing_episodes() {
        val blocked = javaClass.getResource("/series-restricted.html")!!.readText()
        val result = SeriesDetailsParser.parse(blocked, entry.copy(id = "39185"))
        assertEquals("По просьбе правообладателя, сезон заблокирован для вашей страны.", result.playbackUnavailableReason)
        assertTrue(result.translations.isEmpty())
        assertEquals(2, result.seasons.size)
        assertEquals(listOf("Тестовое описание."), result.paragraphs)
    }

    @Test fun explicit_refresh_bypasses_metadata_cache() = runBlocking {
        val directory = Files.createTempDirectory("details-refresh-test").toFile()
        try {
            var calls = 0
            val source = object : HtmlSource {
                override suspend fun get(url: String): String { calls++; return html }
                override suspend fun sidebar(feed: PublicFeed) = error("Unexpected request")
            }
            val repository = CatalogRepository(directory, source)
            repository.details(entry)
            repository.details(entry)
            assertEquals(1, calls)
            repository.details(entry, force = true)
            assertEquals(2, calls)
        } finally { directory.deleteRecursively() }
    }

    @Test fun hidden_web_player_error_placeholder_is_not_a_playback_restriction() {
        val page = html + """<div id="errorPlayer" class="pgs-player-block error">Ошибка при загрузке плеера, попробуйте перезагрузить страницу</div>"""
        val result = SeriesDetailsParser.parse(page, entry)
        assertNull(result.playbackUnavailableReason)
        assertEquals(10, result.translations.first { it.id == "0" }.episodes!!.size)
    }

}
