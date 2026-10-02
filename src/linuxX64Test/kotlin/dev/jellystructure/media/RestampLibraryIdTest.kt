package dev.jellystructure.media

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Phase 298 FR-298-5 — a renamed library's titles take its new id; the folder boundary is respected. */
class RestampLibraryIdTest {
    private val dir = "/tmp/jellystructure-test-restamp-${getpid()}"

    @AfterTest
    fun tearDown() { platform.posix.system("rm -rf '$dir'") }

    private fun item(id: String, path: String, libraryId: String) = MediaItem(
        id = id, title = id, year = null, kind = MediaKind.MUSIC_VIDEO, path = path, tmdbId = null,
        originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0,
        scannedAt = 0L, libraryId = libraryId,
    )

    @Test
    fun titlesInsideTheFolderMoveAndNeighboursDoNot() = runBlocking {
        SystemFileSystem.createDirectories(Path(dir))
        val db = createDatabase("$dir/test.db")
        val store = MediaStore(db, JsTagStore("$dir/tags.json"), ConfigStore("$dir/config.toml"))
        store.addOrUpdate(item("v1", "/mnt/media/jellyfin/music_videos/A/a.mkv", "old"))
        store.addOrUpdate(item("v2", "/mnt/media/jellyfin/music_videos/B/b.mkv", "old"))
        // Same old id, but in /music — the folder whose name is a prefix of music_videos' must not move.
        store.addOrUpdate(item("m1", "/mnt/media/jellyfin/music/x.mkv", "old"))
        store.addOrUpdate(item("f1", "/mnt/media/jellyfin/movies/y.mkv", "film"))

        val versionBefore = store.feedVersion
        assertEquals(2, store.restampLibraryId("old", "new", "/mnt/media/jellyfin/music_videos"))
        val byId = store.allItems().associate { it.id to it.libraryId }
        assertEquals(mapOf("v1" to "new", "v2" to "new", "m1" to "old", "f1" to "film"), byId)
        assertEquals(true, store.feedVersion > versionBefore)
        // Nothing left to move: a second run is a no-op.
        assertEquals(0, store.restampLibraryId("old", "new", "/mnt/media/jellyfin/music_videos/"))
    }
}
