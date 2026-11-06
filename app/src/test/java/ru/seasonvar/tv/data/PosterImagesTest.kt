package ru.seasonvar.tv.data

import org.junit.Assert.*
import org.junit.Test

class PosterImagesTest {
    @Test fun site_thumbnail_and_regular_poster_use_the_same_large_image() {
        val large = "https://cdn.svstat.net/oblojka/large/51144.jpg"
        assertEquals(large, PosterImages.largeUrl("https://cdn.svstat.net/oblojka/51144.jpg"))
        assertEquals(large, PosterImages.largeUrl("https://cdn.svstat.net/oblojka/small/51144.jpg"))
        assertEquals(large, PosterImages.largeUrl(large))
    }
    @Test fun upgrade_to_https_preserves_image_query() {
        assertEquals("https://cdn.svstat.net/oblojka/large/123.jpg?v=2",
            PosterImages.largeUrl("http://cdn.svstat.net/oblojka/small/123.jpg?v=2"))
    }
    @Test fun unknown_images_and_origins_are_not_rewritten() {
        for (url in listOf("broken", "https://other.example/oblojka/123.jpg",
            "https://cdn.svstat.net:8443/oblojka/123.jpg", "https://user@cdn.svstat.net/oblojka/123.jpg",
            "https://cdn.svstat.net/avatar/123.jpg", "https://cdn.svstat.net/oblojka/large/large/123.jpg")) {
            assertNull(url, PosterImages.largeUrl(url))
        }
    }
}
