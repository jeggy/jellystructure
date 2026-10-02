package dev.jellystructure.config

import dev.jellystructure.auth.JellyfinLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Phase 298 — the household's own case first: the values are the config and Jellyfin on 2026-10-02. */
class LibraryReconcilerTest {

    private val musikOldId = "8a05b0252259a1dbd62df97522638439"
    private val videoerId = "c49e7e8dff6aa356e061de3a58505146"

    private val saved = listOf(
        LibraryMapping(jellyfinId = "253b", name = "Film", collectionType = "movies", jellyfinPath = "/media/movies/", localPath = "/mnt/media/jellyfin/movies/"),
        LibraryMapping(jellyfinId = "b7e6", name = "Blandet", collectionType = "homevideos", skip = true),
        LibraryMapping(jellyfinId = musikOldId, name = "Musik", collectionType = "musicvideos", jellyfinPath = "/media/music_videos", localPath = "/mnt/media/jellyfin/music_videos"),
        LibraryMapping(jellyfinId = musikOldId, name = "Musik", collectionType = "music", jellyfinPath = "/media/music/", localPath = "/mnt/media/jellyfin/music/", fallbackLanguage = "fo"),
    )
    private val jellyfin = listOf(
        JellyfinLibrary(id = "253b", name = "Film", collectionType = "movies", locations = listOf("/media/movies")),
        JellyfinLibrary(id = videoerId, name = "Musik Videoer", collectionType = "musicvideos", locations = listOf("/media/music_videos")),
        JellyfinLibrary(id = "b7e6", name = "Blandet", collectionType = "homevideos", locations = listOf("/media/mixed")),
        JellyfinLibrary(id = musikOldId, name = "Musik", collectionType = "music", locations = listOf("/media/music")),
    )

    @Test
    fun aRenamedLibraryKeepsItsPathsAndTakesItsNewNameAndId() {
        val r = LibraryReconciler.reconcile(saved, jellyfin)
        val videoer = r.mappings.single { it.jellyfinPath == "/media/music_videos" }
        assertEquals(videoerId, videoer.jellyfinId)
        assertEquals("Musik Videoer", videoer.name)
        assertEquals("/mnt/media/jellyfin/music_videos", videoer.localPath)
        val musik = r.mappings.single { it.collectionType == "music" }
        assertEquals(musikOldId, musik.jellyfinId)
        assertEquals("fo", musik.fallbackLanguage)
        assertEquals(listOf("Film", "Musik Videoer", "Blandet", "Musik"), r.mappings.map { it.name })
        // One id per library afterwards, and the change carries what the item re-stamp needs.
        assertEquals(r.mappings.size, r.mappings.map { it.jellyfinId }.toSet().size)
        val rename = r.changes.single { it.oldId != null }
        assertEquals(musikOldId, rename.oldId)
        assertEquals(videoerId, rename.newId)
        assertTrue(rename.message.startsWith("Library renamed in Jellyfin: Musik → Musik Videoer"))
    }

    @Test
    fun aSkippedLibraryWithNoPathMatchesById() {
        val r = LibraryReconciler.reconcile(saved, jellyfin)
        val blandet = r.mappings.single { it.jellyfinId == "b7e6" }
        assertTrue(blandet.skip)
        assertEquals("", blandet.localPath)
    }

    @Test
    fun aReconciledListReconcilesToItself() {
        val once = LibraryReconciler.reconcile(saved, jellyfin).mappings
        val twice = LibraryReconciler.reconcile(once, jellyfin)
        assertEquals(once, twice.mappings)
        assertTrue(twice.changes.isEmpty())
    }

    @Test
    fun aNewLibraryIsAddedSkippedWithNoLocalPath() {
        val r = LibraryReconciler.reconcile(saved.take(1), jellyfin.take(1) + JellyfinLibrary(id = "9c75", name = "Bøger", collectionType = "books", locations = listOf("/media/books")))
        val books = r.mappings.single { it.jellyfinId == "9c75" }
        assertTrue(books.skip)
        assertEquals("", books.localPath)
        assertEquals("/media/books", books.jellyfinPath)
    }

    @Test
    fun aLibraryGoneFromJellyfinIsRemoved() {
        val r = LibraryReconciler.reconcile(saved, jellyfin.filter { it.name != "Blandet" })
        assertTrue(r.mappings.none { it.name == "Blandet" })
        assertTrue(r.changes.any { it.message.contains("no longer in Jellyfin") })
    }

    @Test
    fun anEmptyAnswerChangesNothing() {
        val r = LibraryReconciler.reconcile(saved, emptyList())
        assertEquals(saved, r.mappings)
        assertTrue(r.changes.isEmpty())
    }

    @Test
    fun aTrailingSlashDoesNotDecide() {
        val r = LibraryReconciler.reconcile(
            listOf(LibraryMapping(jellyfinId = "old", name = "Film", jellyfinPath = "/media/movies", localPath = "/x")),
            listOf(JellyfinLibrary(id = "new", name = "Films", collectionType = "movies", locations = listOf("/media/movies/"))),
        )
        assertEquals("new", r.mappings.single().jellyfinId)
        assertEquals("/x", r.mappings.single().localPath)
    }
}
