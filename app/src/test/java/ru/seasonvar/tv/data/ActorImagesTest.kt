package ru.seasonvar.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ActorImagesTest {
    @Test fun uses_large_photo_like_the_site_tooltip() {
        assertEquals("https://cdn.svstat.net/a/0/3487.jpg",
            ActorImages.photoUrl("https://cdn.svstat.net/a/s/0/3487.jpg"))
        assertEquals("https://cdn.svstat.net/a/1/16433.jpg",
            ActorImages.photoUrl("http://cdn.svstat.net/a/1/16433.jpg"))
    }

    @Test fun preserves_the_site_placeholder_and_uses_it_for_missing_photos() {
        assertEquals(ActorImages.PLACEHOLDER_URL, ActorImages.photoUrl(ActorImages.PLACEHOLDER_URL))
        assertEquals(ActorImages.PLACEHOLDER_URL, ActorImages.photoUrl(""))
    }

    @Test fun rejects_foreign_or_credentialed_images_and_other_paths() {
        for (url in listOf("https://example.com/a/1/1.jpg", "https://user@cdn.svstat.net/a/1/1.jpg",
            "https://cdn.svstat.net:8443/a/1/1.jpg", "https://cdn.svstat.net/oblojka/1.jpg", "file:///a/1/1.jpg")) {
            assertEquals(ActorImages.PLACEHOLDER_URL, ActorImages.photoUrl(url))
        }
    }
}
