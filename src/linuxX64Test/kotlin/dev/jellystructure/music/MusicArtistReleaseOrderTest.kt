package dev.jellystructure.music

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.model.MusicArtist
import dev.jellystructure.model.MusicOfficialList
import dev.jellystructure.model.MusicOfficialSong
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.music.EditionsFixture.HL
import dev.jellystructure.music.EditionsFixture.LIB
import dev.jellystructure.music.EditionsFixture.LK
import dev.jellystructure.music.EditionsFixture.MB_LK
import dev.jellystructure.shared.tv.MUSIC_SELECTION_CAP
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-3619 (FR-3, FR-4, FR-6; acceptance 7 and 10) — an artist plays in release order: `selection(scope = "artist")`, its
 * 400s, and the page's *Songs* ties. Harbour Lights has an album each in 2001, two in 2005 (one dated to the year, one to
 * the month), an EP on the first album's day, a lead single and a compilation that repeat album songs, a single homed
 * under an album, an undated album, and a guest spot on Lighthouse Keepers' 2003 album.
 */
class MusicArtistReleaseOrderTest {
    private val base = "/tmp/jellystructure-test-release-order-${getpid()}"
    private lateinit var store: MusicStore
    private lateinit var svc: MusicTvService

    private val open = device(null)
    private val onlyLib = device(setOf(LIB))
    private fun device(allowed: Set<String>?) = DeviceData("d", "tok", "u1", "viewer", "", isAdmin = false, allowedLibraries = allowed, kind = "phone")

    private fun track(id: String, albumId: String, pos: Int, rec: String?, bitrate: Int = 320_000, credit: dev.jellystructure.model.MusicCredit = HL, lib: String = LIB) =
        EditionsFixture.track(id, albumId, pos, "Song $id", rec, bitrate = bitrate, credit = credit).copy(libraryId = lib)

    private fun library(): Pair<List<dev.jellystructure.model.MusicAlbum>, List<MusicTrack>> {
        val albums = listOf(
            EditionsFixture.album("al1", "First Light", 2001).copy(firstReleaseDate = "2001-05-10"),
            EditionsFixture.album("ep1", "Winter EP", 2001, primary = "EP").copy(firstReleaseDate = "2001-05-10"),
            EditionsFixture.album("al2", "Second Wind", 2005).copy(firstReleaseDate = "2005"),
            EditionsFixture.album("al3", "Third Tide", 2005).copy(firstReleaseDate = "2005-03"),
            // A lead single, on no album: its one song is also the first track of First Light.
            EditionsFixture.album("ld", "Lead Out", 2000, primary = "Single").copy(firstReleaseDate = "2000-11"),
            // Lives under Second Wind (MusicBrainz's single-from), so it is in no group and plays nothing on its own.
            EditionsFixture.album("hs", "Wind Up", 2004, primary = "Single", singleFrom = "rg-al2"),
            EditionsFixture.album("cp", "Odds and Ends", 2010, secondary = listOf("Compilation")),
            EditionsFixture.album("gu", "Pale Album", 2003, credit = LK, mb = MB_LK),
            EditionsFixture.album("un", "Mystery Tape", 2000, rg = null).copy(year = null, firstReleaseDate = null),
        )
        val tracks = listOf(
            track("al1-1", "al1", 1, "r1"), track("al1-2", "al1", 2, "r2"), track("al1-3", "al1", 3, "r3"),
            track("ep1-1", "ep1", 1, "r7"),
            track("al2-1", "al2", 1, "r4"), track("al2-2", "al2", 2, "r5"),
            track("al3-1", "al3", 1, "r6"),
            track("ld-1", "ld", 1, "r1", bitrate = 256_000),
            track("hs-1", "hs", 1, "r4", bitrate = 256_000), track("hs-2", "hs", 2, "rb", bitrate = 256_000),
            track("cp-1", "cp", 1, "r2", bitrate = 192_000), track("cp-2", "cp", 2, "r8", bitrate = 192_000),
            track("gu-1", "gu", 1, "r9"), track("gu-2", "gu", 2, "ra", credit = LK),
            track("un-1", "un", 1, null),
        )
        return albums to tracks
    }

    private suspend fun newService() = MusicTvService(store, { null }, JellyfinClient(), ConfigStore("$base.toml").also { it.load() })

    @BeforeTest fun setUp() = runBlocking {
        listOf("$base.db", "$base.db-wal", "$base.db-shm").forEach { runCatching { platform.posix.remove(it) } }
        store = MusicStore(createDatabase("$base.db"))
        val (albums, tracks) = library()
        val artists = listOf(MusicArtist(id = "hl", libraryId = LIB, name = "Harbour Lights", path = "/m/hl"), MusicArtist(id = "lk", libraryId = LIB, name = "Lighthouse Keepers", path = "/m/lk"))
        store.replaceLibrary(MusicLibraryRows(LIB, artists, albums, tracks))
        // A room only some viewers may open, and an artist who lives only there.
        store.replaceLibrary(MusicLibraryRows("lib-b", listOf(MusicArtist(id = "ghost", libraryId = "lib-b", name = "Ghost Choir", path = "/m/g")),
            listOf(EditionsFixture.album("hid", "Hidden Room", 2012).copy(libraryId = "lib-b"), EditionsFixture.album("gh", "Ghost Album", 2002, credit = dev.jellystructure.model.MusicCredit("ghost", "Ghost Choir"), mb = null).copy(libraryId = "lib-b")),
            listOf(track("hid-1", "hid", 1, "rh", lib = "lib-b"), track("gh-1", "gh", 1, "rg", credit = dev.jellystructure.model.MusicCredit("ghost", "Ghost Choir"), lib = "lib-b"))))
        svc = newService()
    }

    @AfterTest fun tearDown() { listOf("$base.db", "$base.db-wal", "$base.db-shm", "$base.toml").forEach { runCatching { platform.posix.remove(it) } } }

    private suspend fun order(sort: String, who: DeviceData = open, artist: String = "hl") =
        svc.selection(who, "artist", sort, null, null, false, artist)!!.tracks.map { it.id }

    @Test
    fun newest_first_is_every_release_by_date_each_in_its_own_track_order() = runBlocking<Unit> {
        // Hidden Room (2012, other room) leads; Odds and Ends (2010) adds only its new song; Third Tide (2005-03) before
        // Second Wind (2005 = 2005-01-01); the guest spot (2003); First Light before its same-day EP (group order); Lead Out
        // adds nothing (its song is on First Light); the undated album is last.
        assertEquals(listOf("hid-1", "cp-2", "al3-1", "al2-1", "al2-2", "hs-2", "gu-1", "al1-1", "al1-2", "al1-3", "ep1-1", "un-1"), order("newest"))
    }

    @Test
    fun oldest_first_is_the_same_releases_reversed_with_tracks_and_ties_kept_and_the_undated_still_last() = runBlocking<Unit> {
        // Lead Out is the oldest release, but its song plays at its place in First Light, and First Light plays whole.
        assertEquals(listOf("al1-1", "al1-2", "al1-3", "ep1-1", "gu-1", "al2-1", "al2-2", "hs-2", "al3-1", "cp-2", "hid-1", "un-1"), order("oldest"))
    }

    @Test
    fun matched_album_extras_and_homed_bsides_play_in_its_run_in_both_sorts() = runBlocking<Unit> {
        runCatching { platform.posix.remove("$base.db") }
        store = MusicStore(createDatabase("$base.db"))
        val official = MusicOfficialList(
            listOf(MusicOfficialSong("official-r", "Harbour Bell", 240_000, 1, 1)),
            "bell-release", "Harbour Bell", 1, 1, "rg-bell", "Harbour Bell",
        )
        store.replaceLibrary(MusicLibraryRows(LIB, listOf(MusicArtist(id = "hl", libraryId = LIB, name = "Harbour Lights", path = "/m/hl")),
            listOf(
                EditionsFixture.album("bell", "Harbour Bell", 2003, official = official).copy(firstReleaseDate = "2003-06-01"),
                EditionsFixture.album("single", "Bell Signal", 2003, primary = "Single", singleFrom = "rg-bell"),
                EditionsFixture.album("comp", "Collected Tides", 2003, secondary = listOf("Compilation")).copy(firstReleaseDate = "2003-06-01"),
            ),
            listOf(
                track("bell-official", "bell", 1, "official-r"),
                track("bell-extra", "bell", 2, "extra-r"),
                track("bell-only", "bell", 3, "only-r"),
                track("single-a", "single", 1, "official-r", bitrate = 256_000),
                track("single-b", "single", 2, "bside-r", bitrate = 256_000),
                track("comp-extra", "comp", 1, "extra-r", bitrate = 192_000),
                track("comp-bside", "comp", 2, "bside-r", bitrate = 192_000),
                track("comp-own", "comp", 3, "comp-r", bitrate = 192_000),
            )))
        // The editions index sees the whole snapshot; this single's B-side must stay outside a LIB-only viewer's queue.
        store.replaceLibrary(MusicLibraryRows("lib-b", emptyList(),
            listOf(EditionsFixture.album("hidden-single", "Bell Echo", 2003, primary = "Single", singleFrom = "rg-bell").copy(libraryId = "lib-b")),
            listOf(track("hidden-a", "hidden-single", 1, "official-r", lib = "lib-b"),
                track("hidden-b", "hidden-single", 2, "hidden-r", lib = "lib-b"))))
        svc = newService()
        assertTrue("hidden-b" in order("newest"), "the unrestricted queue must see the hidden B-side")
        val expected = listOf("bell-official", "bell-extra", "bell-only", "single-b", "comp-own")
        for (sort in listOf("newest", "oldest")) {
            val selected = svc.selection(onlyLib, "artist", sort, null, null, false, "hl")!!.tracks
            assertEquals(expected, selected.map { it.id }, sort)
            assertEquals(listOf("bell", "bell", "bell", "single", "comp"), selected.map { it.albumId }, sort)
            assertEquals(1, selected.count { it.id == "bell-only" }, sort)
            assertEquals(0, selected.count { it.id == "comp-extra" || it.id == "comp-bside" }, sort)
        }
    }

    @Test
    fun an_albums_slot_plays_the_albums_own_file_when_a_compilation_holds_the_shown_copy() = runBlocking<Unit> {
        // DEB-3619 FR-3.3 amended (DEB-3677) — Old Harbour (1978) and New Anchor (1990) reappear on Greatest Piers (2006),
        // whose files are the better copies, so every song's shown copy lives on the compilation. Each album's slot still
        // plays that album's file; the compilation adds only its song that is on no album.
        runCatching { platform.posix.remove("$base.db") }
        store = MusicStore(createDatabase("$base.db"))
        fun at(id: String, album: String, pos: Int, rec: String, bitrate: Int) = track(id, album, pos, rec, bitrate = bitrate)
        store.replaceLibrary(MusicLibraryRows(LIB, listOf(MusicArtist(id = "hl", libraryId = LIB, name = "Harbour Lights", path = "/m/hl")),
            listOf(
                EditionsFixture.album("old", "Old Harbour", 1978).copy(firstReleaseDate = "1978-04-01"),
                EditionsFixture.album("new", "New Anchor", 1990).copy(firstReleaseDate = "1990-09-01"),
                EditionsFixture.album("gp", "Greatest Piers", 2006, secondary = listOf("Compilation")).copy(firstReleaseDate = "2006-02-01"),
            ),
            listOf(
                at("old-1", "old", 1, "q1", 192_000), at("old-2", "old", 2, "q2", 192_000), at("old-3", "old", 3, "q3", 192_000),
                at("new-1", "new", 1, "q4", 192_000), at("new-2", "new", 2, "q5", 192_000),
                at("gp-1", "gp", 1, "q2", 320_000), at("gp-2", "gp", 2, "q4", 320_000), at("gp-3", "gp", 3, "q1", 320_000), at("gp-4", "gp", 4, "q6", 320_000),
            )))
        svc = newService()
        suspend fun runs(sort: String) = svc.selection(open, "artist", sort, null, null, false, "hl")!!.tracks.map { it.id to it.albumId }
        assertEquals(listOf("old-1", "old-2", "old-3", "new-1", "new-2", "gp-4"), order("oldest"))
        assertEquals(listOf("gp-4", "new-1", "new-2", "old-1", "old-2", "old-3"), order("newest"))
        assertEquals(listOf("old", "old", "old", "new", "new", "gp"), runs("oldest").map { it.second })
        assertEquals(listOf("gp", "new", "new", "old", "old", "old"), runs("newest").map { it.second })
    }

    @Test
    fun the_selection_for_a_viewer_is_theirs_the_hidden_room_and_an_unseen_artist_are_left_out() = runBlocking<Unit> {
        assertFalse("hid-1" in order("newest", onlyLib))
        assertEquals(order("newest").filter { it != "hid-1" }, order("newest", onlyLib))
        val s = svc.selection(onlyLib, "artist", "newest", null, null, false, "ghost")!!
        assertTrue(s.tracks.isEmpty()); assertEquals(0, s.total); assertFalse(s.capped)
        assertEquals(listOf("gh-1"), order("newest", open, "ghost"))
        assertTrue(svc.selection(open, "artist", "oldest", null, null, false, "nobody")!!.tracks.isEmpty())
    }

    @Test
    fun a_guest_album_contributes_only_the_artists_own_songs() = runBlocking<Unit> {
        assertTrue("gu-1" in order("newest")); assertFalse("gu-2" in order("newest"))
        assertFalse("gu-2" in order("oldest"))
    }

    @Test
    fun a_missing_artist_or_a_sort_other_than_newest_or_oldest_is_null_which_the_route_answers_400() = runBlocking<Unit> {
        assertNull(svc.selection(open, "artist", "newest", null, null, false))
        assertNull(svc.selection(open, "artist", "newest", null, null, false, "  "))
        assertNull(svc.selection(open, "artist", "title", null, null, false, "hl"))
        assertNull(svc.selection(open, "artist", null, null, null, false, "hl"))
        assertNull(svc.selection(open, "artist", "played", null, null, false, "hl"))
    }

    @Test
    fun shuffle_is_ignored_for_this_scope() = runBlocking<Unit> {
        assertEquals(order("newest"), svc.selection(open, "artist", "newest", null, null, shuffle = true, artist = "hl")!!.tracks.map { it.id })
    }

    @Test
    fun the_cap_is_the_first_thousand_of_the_order() = runBlocking<Unit> {
        val n = MUSIC_SELECTION_CAP + 50
        runCatching { platform.posix.remove("$base.db") }
        store = MusicStore(createDatabase("$base.db"))
        store.replaceLibrary(MusicLibraryRows(LIB, listOf(MusicArtist(id = "hl", libraryId = LIB, name = "Harbour Lights", path = "/m/hl")),
            listOf(EditionsFixture.album("big", "Big Room", 2001)), (1..n).map { track("big-$it", "big", it, "rb$it") }))
        svc = newService()
        val s = svc.selection(open, "artist", "newest", null, null, false, "hl")!!
        assertEquals(n, s.total); assertTrue(s.capped)
        assertEquals((1..MUSIC_SELECTION_CAP).map { "big-$it" }, s.tracks.map { it.id })
    }

    @Test
    fun songs_with_the_same_play_count_follow_the_newest_first_order() = runBlocking<Unit> {
        // FR-6 — nothing played, so every count ties: the page's Songs list follows newest-first release order,
        // including the B-side in its parent album's run.
        assertEquals(order("newest"), svc.artist(open, "hl", null)!!.topTracks.map { it.id })
        assertEquals(order("newest").filter { it != "hid-1" }, svc.artist(onlyLib, "hl", null)!!.topTracks.map { it.id })
    }
}
