package ru.seasonvar.tv.data.images

import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.SeriesEntry

class ImagePrefetchPlanTest {
    private fun entry(index: Int) = SeriesEntry(index.toString(), "https://seasonvar.ru/serial-$index-test.html",
        "Тест", 1, "", "https://cdn.svstat.net/oblojka/$index.jpg")
    private val entries = (1..30).map(::entry)
    private fun plan(index: Int, page: Int, visible: IntRange, strip: Boolean = true, rows: List<SeriesEntry> = entries) =
        imagePrefetchPlan(rows, index, page, visible, 120, 180, strip)

    @Test fun strip_prefetches_two_screens_from_selection_skipping_displayed_images() {
        val result = plan(4, 5, 4..8)
        val thumbs = result.filter { it.kind == PrefetchKind.THUMBNAIL }
        val large = result.filter { it.kind == PrefetchKind.LARGE }
        assertEquals((10..14).map { "https://cdn.svstat.net/oblojka/$it.jpg" }, thumbs.map { it.url })
        assertEquals((6..14).map { "https://cdn.svstat.net/oblojka/large/$it.jpg" }, large.map { it.url })
        assertTrue(thumbs.all { it.width == 120 && it.height == 180 })
    }
    @Test fun grid_prepares_large_posters_including_selection_and_counts_partial_rows() {
        val page = prefetchPageSize(5, 600, 350)
        assertEquals(10, page)
        val result = plan(0, page, 0..9, strip = false)
        assertEquals(10, result.count { it.kind == PrefetchKind.THUMBNAIL })
        assertEquals(20, result.count { it.kind == PrefetchKind.LARGE })
    }
    @Test fun near_end_stops_at_last_item_and_does_not_wrap_to_start() {
        val result = plan(28, 5, 25..29)
        assertEquals(listOf("https://cdn.svstat.net/oblojka/large/30.jpg"), result.map { it.url })
    }
    @Test fun invalid_selection_or_unmeasured_viewport_does_not_start_work() {
        assertTrue(plan(-1, 5, 0..4).isEmpty())
        assertTrue(plan(30, 5, 0..4).isEmpty())
        assertTrue(plan(0, 0, 0..4).isEmpty())
        assertEquals(0, prefetchPageSize(5, 0, 200))
        assertEquals(0, prefetchPageSize(0, 600, 200))
    }
    @Test fun repeated_seasons_or_translations_share_one_image_request_and_visible_urls_are_not_repeated() {
        val shared = entries.take(3).map { it.copy(posterUrl = entries.first().posterUrl) }
        assertTrue(plan(0, 5, 0..0, rows = shared).isEmpty())
        val result = plan(0, 5, IntRange.EMPTY, strip = false, rows = shared)
        assertEquals(2, result.size)
        assertEquals(2, result.map { it.key }.distinct().size)
    }
    @Test fun unknown_large_source_only_prefetches_the_provided_thumbnail() {
        val row = entries.first().copy(posterUrl = "https://images.example/cover.jpg")
        assertEquals(PrefetchKind.THUMBNAIL, plan(0, 1, IntRange.EMPTY, rows = listOf(row)).single().kind)
    }
    @Test fun very_large_page_counts_do_not_overflow() {
        assertEquals(30, plan(0, Int.MAX_VALUE, IntRange.EMPTY, strip = false).count { it.kind == PrefetchKind.THUMBNAIL })
        assertEquals(Int.MAX_VALUE, prefetchPageSize(Int.MAX_VALUE, Int.MAX_VALUE, 1))
    }
}
