package dev.jellystructure.music

import dev.jellystructure.auth.JellyfinAudioStream
import dev.jellystructure.auth.JellyfinMusicItem
import dev.jellystructure.auth.JellyfinMusicLibrary
import dev.jellystructure.auth.JellyfinNameId
import dev.jellystructure.config.LibraryMapping
import dev.jellystructure.config.MusicSteps
import dev.jellystructure.config.PipelineStep
import dev.jellystructure.db.createDatabase
import dev.jellystructure.model.MusicArt
import dev.jellystructure.model.MusicMatch
import dev.jellystructure.model.reencodesOnPhone
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicIngestTest {
    private val lib = LibraryMapping(jellyfinId = "lib1", name = "Music", collectionType = "music",
        jellyfinPath = "/media/music", localPath = "/mnt/music")

    private val band = JellyfinNameId("Harbour Lights", "art1")
    private val guest = JellyfinNameId("Guest Singer", "art2")

    private fun track(id: String, pos: Int, container: String = "mp3", codec: String = "mp3", artists: List<JellyfinNameId> = listOf(band)) =
        JellyfinMusicItem(
            id = id, name = "Song $pos", type = "Audio", path = "/media/music/Harbour Lights/Salt/$pos.$container",
            parentId = "alb1", albumId = "alb1", indexNumber = pos, parentIndexNumber = 1, runTimeTicks = 2_000_000_000L,
            container = container, normalizationGain = -9.5, albumNormalizationGain = -9.1,
            genres = listOf("Folk"), artistItems = artists, albumArtists = listOf(band),
            mediaStreams = listOf(JellyfinAudioStream("Audio", codec, 128_000, 44_100, 2)),
        )

    private fun jf(vararg tracks: JellyfinMusicItem) = JellyfinMusicLibrary(
        artists = listOf(JellyfinMusicItem(id = "art1", name = "Harbour Lights", type = "MusicArtist", path = "/media/music/Harbour Lights")),
        albums = listOf(JellyfinMusicItem(id = "alb1", name = "Salt", type = "MusicAlbum", path = "/media/music/Harbour Lights/Salt",
            year = 2004, albumArtists = listOf(band))),
        tracks = tracks.toList(),
    )

    @Test
    fun paths_credits_counts_and_formats() {
        val rows = MusicIngest.build(lib, jf(track("t1", 1), track("t2", 2, "asf", "wmav2", listOf(band, guest))),
            emptyMap(), emptyMap(), emptyMap(), now = 1000, exists = { false })
        val album = rows.albums.single()
        assertEquals("/mnt/music/Harbour Lights/Salt", album.path)
        assertEquals(2, album.trackCount)
        assertEquals(400_000L, album.durationMs)
        assertEquals(listOf("Folk"), album.genres)        // the album carries none; its tracks' tags stand in
        assertEquals(MusicArt.NONE, album.coverState)
        // The guest is an artist too — known only as a credit, with no folder.
        val guestRow = rows.artists.single { it.id == "art2" }
        assertNull(guestRow.path)
        assertEquals("/mnt/music/Harbour Lights", rows.artists.single { it.id == "art1" }.path)
        val wma = rows.tracks.single { it.id == "t2" }
        assertTrue(wma.reencodesOnPhone())
        assertFalse(rows.tracks.single { it.id == "t1" }.reencodesOnPhone())
        assertEquals(1000, album.createdAt)
    }

    @Test
    fun a_cover_file_in_the_folder_counts_and_jellyfin_art_is_second() {
        val rows = MusicIngest.build(lib, jf(track("t1", 1)), emptyMap(), emptyMap(), emptyMap(), 1000,
            exists = { it == "/mnt/music/Harbour Lights/Salt/folder.jpg" })
        assertEquals(MusicArt.FILE, rows.albums.single().coverState)
        val withTag = jf(track("t1", 1)).let { it.copy(albums = it.albums.map { a -> a.copy(imageTags = mapOf("Primary" to "abc")) }) }
        assertEquals(MusicArt.JELLYFIN, MusicIngest.build(lib, withTag, emptyMap(), emptyMap(), emptyMap(), 1000, { false }).albums.single().coverState)
    }

    @Test
    fun a_rescan_carries_the_match_and_never_bumps_an_unchanged_row() {
        val first = MusicIngest.build(lib, jf(track("t1", 1)), emptyMap(), emptyMap(), emptyMap(), 1000, { false })
        val matched = first.albums.single().copy(matchState = MusicMatch.MATCHED, releaseGroupMbid = "rg-1", matchLocked = true)
        val again = MusicIngest.build(lib, jf(track("t1", 1)),
            first.artists.associateBy { it.id }, mapOf(matched.id to matched), first.tracks.associateBy { it.id }, 2000, { false })
        val album = again.albums.single()
        assertEquals(MusicMatch.MATCHED, album.matchState)
        assertEquals("rg-1", album.releaseGroupMbid)
        assertTrue(album.matchLocked)
        assertEquals(1000, album.updatedAt)                // nothing Jellyfin reports changed
        assertEquals(first.tracks.single(), again.tracks.single())
    }

    @Test
    fun what_jellyfin_no_longer_reports_is_flagged_and_comes_back_clean() {
        val first = MusicIngest.build(lib, jf(track("t1", 1), track("t2", 2)), emptyMap(), emptyMap(), emptyMap(), 1000, { false })
        val second = MusicIngest.build(lib, jf(track("t1", 1)),
            first.artists.associateBy { it.id }, first.albums.associateBy { it.id }, first.tracks.associateBy { it.id }, 2000, { false })
        assertEquals(2000, second.tracks.single { it.id == "t2" }.missingSince)
        assertEquals(1, second.albums.single().trackCount)
        val third = MusicIngest.build(lib, jf(track("t1", 1), track("t2", 2)),
            second.artists.associateBy { it.id }, second.albums.associateBy { it.id }, second.tracks.associateBy { it.id }, 3000, { false })
        assertNull(third.tracks.single { it.id == "t2" }.missingSince)
        // Another library's rows are never flagged by this library's scan.
        val other = first.tracks.single { it.id == "t2" }.copy(id = "x", libraryId = "lib2")
        val fourth = MusicIngest.build(lib, jf(track("t1", 1)), emptyMap(), emptyMap(), mapOf("x" to other), 4000, { false })
        assertTrue(fourth.tracks.none { it.id == "x" })
    }

    @Test
    fun the_store_round_trips_through_sqlite_and_counts_health() = runBlocking {
        val path = "/tmp/jellystructure-test-music-${getpid()}.db"
        runCatching { platform.posix.remove(path) }
        val store = MusicStore(createDatabase(path))
        val rows = MusicIngest.build(lib, jf(track("t1", 1), track("t2", 2, "asf", "wmav2")), emptyMap(), emptyMap(), emptyMap(), 1000, { false })
        store.replaceLibrary(rows)
        val reread = MusicStore(createDatabase(path)).snapshot()
        assertEquals(rows.albums.single(), reread.albums["alb1"])
        assertEquals(2, reread.tracksByAlbum["alb1"]?.size)
        assertEquals(listOf("alb1"), reread.albumsByArtist["art1"]?.map { it.id })
        val h = store.health()
        assertEquals(1, h.albums); assertEquals(2, h.tracks); assertEquals(1, h.reencodes)
        assertEquals(1, h.unmatched); assertEquals(1, h.coversMissing)
        assertEquals(1, h.artistImagesMissing)            // the band has a folder and no picture
        runCatching { platform.posix.remove(path) }
        Unit
    }

    @Test
    fun scan_music_is_seeded_once_right_after_scan_files() {
        val pipeline = listOf(PipelineStep("scan_files"), PipelineStep("pull_tmdb"), PipelineStep("notify"))
        val seeded = MusicSteps.seed(pipeline, emptyList())
        assertEquals(listOf("scan_files", "scan_music"), seeded.map { it.step }.take(2))
        assertEquals(pipeline, MusicSteps.seed(pipeline, MusicSteps.ALL))            // removed by the operator: stays removed
        assertEquals(emptyList(), MusicSteps.seed(emptyList(), emptyList()))       // the built-in default carries it
    }

    @Test
    fun visibility_follows_the_library_grant() {
        assertTrue(musicVisible("lib1", null))
        assertTrue(musicVisible("LIB-1", setOf("lib1")))
        assertFalse(musicVisible("lib1", setOf("lib2")))
        assertFalse(musicVisible(null, setOf("lib1")))
    }
}
