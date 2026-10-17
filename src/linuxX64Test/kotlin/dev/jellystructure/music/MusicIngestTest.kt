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

    /** Phase 305 (dev review 2b, test 12) — `BitDepth` is Jellyfin's; the official list and *single from* are ours. */
    @Test
    fun a_rescan_keeps_official_and_single_from_and_takes_bit_depth_from_jellyfin() {
        val first = MusicIngest.build(lib, jf(track("t1", 1)), emptyMap(), emptyMap(), emptyMap(), 1000, { false })
        val official = dev.jellystructure.model.MusicOfficialList(listOf(dev.jellystructure.model.MusicOfficialSong("r1", "Song 1")), "rel", "Salt", 3, 4, "rg-1", "Salt")
        val ours = first.albums.single().copy(official = official, singleFrom = "rg-x", relsReadAt = 5,
            extraOrigins = mapOf("r9" to dev.jellystructure.model.MusicExtraOrigin(title = "Salt", country = "JP")))
        val flac = track("t1", 1, "flac", "flac").let { it.copy(mediaStreams = listOf(JellyfinAudioStream("Audio", "flac", 900_000, 96_000, 2, bitDepth = 24))) }
        val again = MusicIngest.build(lib, jf(flac), first.artists.associateBy { it.id }, mapOf(ours.id to ours), first.tracks.associateBy { it.id }, 2000, { false })
        val album = again.albums.single()
        assertEquals(official, album.official)
        assertEquals("rg-x", album.singleFrom)
        assertEquals("JP", album.extraOrigins["r9"]?.country)
        assertEquals(24, again.tracks.single().bitDepth)
    }

    // ── FR-277-1a — an artist Jellyfin keeps no library folder for has no folder here ──

    private val slashLib = lib.copy(jellyfinPath = "/media/music/", localPath = "/mnt/music/")
    private val metaPath = "/config/metadata/artists/Harbour Lights"
    private fun artistJf(path: String, tag: Boolean = false) = JellyfinMusicLibrary(
        artists = listOf(JellyfinMusicItem(id = "art1", name = "Harbour Lights", type = "MusicArtist", path = path,
            imageTags = if (tag) mapOf("Primary" to "abc") else emptyMap())),
        albums = emptyList(), tracks = emptyList(),
    )

    @Test
    fun an_artist_outside_the_library_folder_gets_a_null_path() {
        fun artist(l: LibraryMapping, path: String, tag: Boolean = false) =
            MusicIngest.build(l, artistJf(path, tag), emptyMap(), emptyMap(), emptyMap(), 1000, { false }).artists.single()
        val outside = artist(slashLib, metaPath, tag = true)
        assertNull(outside.path)
        assertEquals(MusicArt.JELLYFIN, outside.imageState)            // what Jellyfin shows
        assertEquals(MusicArt.NONE, artist(slashLib, metaPath).imageState)
        assertEquals(MusicArt.NONE, outside.backdropState); assertEquals(MusicArt.NONE, outside.logoState)
        assertEquals("/mnt/music/Harbour Lights", artist(slashLib, "/media/music/Harbour Lights").path)
        // a sibling that merely shares the prefix is outside too
        assertNull(artist(slashLib, "/media/music2/Harbour Lights").path)
        // a blank jellyfin_path means one path for both containers: nothing is outside
        val blank = lib.copy(jellyfinPath = "", localPath = "")
        assertEquals(metaPath, artist(blank, metaPath).path)
    }

    @Test
    fun a_stored_path_outside_the_library_is_cleared_and_a_real_folder_survives_a_credit() {
        val stored = MusicIngest.build(lib.copy(jellyfinPath = "", localPath = ""), artistJf(metaPath), emptyMap(), emptyMap(), emptyMap(), 1000, { false })
            .artists.single().copy(matchState = MusicMatch.MATCHED, mbid = "mb-1", biographies = mapOf("en" to "Lead"))
        assertEquals(metaPath, stored.path)
        // the same item seen again with the library's folder set: the stored path is not carried
        val again = MusicIngest.build(slashLib, artistJf(metaPath, tag = true), mapOf(stored.id to stored), emptyMap(), emptyMap(), 2000, { false }).artists.single()
        assertNull(again.path)
        assertEquals(MusicArt.JELLYFIN, again.imageState)
        assertEquals(MusicMatch.MATCHED, again.matchState); assertEquals("mb-1", again.mbid); assertEquals(stored.biographies, again.biographies)
        // …and a later scan changes nothing
        val third = MusicIngest.build(slashLib, artistJf(metaPath, tag = true), mapOf(again.id to again), emptyMap(), emptyMap(), 3000, { false }).artists.single()
        assertEquals(again, third)
        // a credit-only sighting of the bad stored path clears it too
        val credit = JellyfinMusicLibrary(emptyList(), emptyList(), listOf(track("t1", 1).copy(artistItems = listOf(band))))
        assertNull(MusicIngest.build(slashLib, credit, mapOf(stored.id to stored), emptyMap(), emptyMap(), 2000, { false }).artists.single { it.id == "art1" }.path)
        // a stored real folder seen again only as a credit keeps its folder
        val real = MusicIngest.build(slashLib, artistJf("/media/music/Harbour Lights"), emptyMap(), emptyMap(), emptyMap(), 1000, { true })
            .artists.single()
        assertEquals(MusicArt.FILE, real.imageState)
        val kept = MusicIngest.build(slashLib, credit, mapOf(real.id to real), emptyMap(), emptyMap(), 2000, { false }).artists.single { it.id == "art1" }
        assertEquals("/mnt/music/Harbour Lights", kept.path)
        assertEquals(MusicArt.FILE, kept.imageState)
    }

    @Test
    fun a_folderless_artist_is_not_a_missing_picture() {
        val rows = MusicIngest.build(slashLib, artistJf(metaPath), emptyMap(), emptyMap(), emptyMap(), 1000, { false })
        val folderless = rows.artists.single().copy(matchState = MusicMatch.MATCHED, mbid = "mb-1")
        val snap = MusicStore.Snapshot(artists = mapOf(folderless.id to folderless), albums = emptyMap(), tracks = emptyMap())
        assertEquals(0, MusicTriage.Music(snap, emptySet()).count("music_no_picture"))
        assertTrue(MusicBrowse.browse(snap, MusicBrowse.ARTISTS, emptyMap(), null, emptyMap(), triage = "music_no_picture").artists.isEmpty())
        val path = "/tmp/jellystructure-test-music-folderless-${getpid()}.db"
        runCatching { platform.posix.remove(path) }
        runBlocking {
            val store = MusicStore(createDatabase(path))
            store.replaceLibrary(MusicLibraryRows("lib1", listOf(folderless), emptyList(), emptyList()))
            assertEquals(0, store.health().artistImagesMissing)
        }
        runCatching { platform.posix.remove(path) }
    }
}
