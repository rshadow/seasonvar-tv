package ru.seasonvar.tv.data.home

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

class HomePosterFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = "a".repeat(64) + ".jpg"

    @Test fun a_poster_resolves_inside_the_private_poster_directory() {
        val directory = temporary.newFolder("posters")
        val file = File(directory, key).apply { writeText("poster") }
        assertEquals(file.canonicalFile, homePosterFile(directory, key))
    }

    @Test fun foreign_paths_and_non_poster_names_are_rejected() {
        val directory = temporary.newFolder("posters")
        for (name in listOf("../secrets.env", "/tmp/$key", "%2e%2e%2f$key", "$key/..", key + "\n", "a".repeat(64) + ".xml")) {
            try { homePosterFile(directory, name); fail("Accepted a foreign path") }
            catch (_: FileNotFoundException) { }
        }
    }

    @Test fun a_valid_poster_name_cannot_follow_a_link_outside_the_directory() {
        val directory = temporary.newFolder("posters")
        val outside = temporary.newFile("outside.jpg")
        Files.createSymbolicLink(File(directory, key).toPath(), outside.toPath())
        try { homePosterFile(directory, key); fail("Accepted an external symlink") }
        catch (_: FileNotFoundException) { }
    }
}
