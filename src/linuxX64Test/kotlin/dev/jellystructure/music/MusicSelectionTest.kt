package dev.jellystructure.music

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicArtist
import dev.jellystructure.model.MusicCredit
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.shared.tv.MUSIC_SELECTION_CAP
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R388 (FR-R388-2/3/4, acceptance 9) — `MusicTvService.selection`: each scope's order, the cap and the sample. */
class MusicSelectionTest {
    private val base = "/tmp/jellystructure-test-selection-${getpid()}"
    private lateinit var store: MusicStore
    private lateinit var svc: MusicTvService

    private val onlyA = device(setOf("liba"))
    private val onlyB = device(setOf("libb"))
    private fun device(allowed: Set<String>?) = DeviceData("d", "tok", "u1", "viewer", "", isAdmin = false, allowedLibraries = allowed, kind = "phone")

    private val hl = MusicCredit("x", "Harbour Lights")
    private val ch = MusicCredit("y", "Cherry Hill")

    private fun album(id: String, title: String, year: Int, added: Long, genre: String, by: MusicCredit = hl, lib: String = "liba") =
        MusicAlbum(id = id, libraryId = lib, title = title, year = year, albumArtists = listOf(by), genres = listOf(genre), addedAt = added)
    private fun track(id: String, albumId: String?, pos: Int, title: String, by: MusicCredit = hl, added: Long = 0, lib: String = "liba") =
        MusicTrack(id = id, albumId = albumId, libraryId = lib, title = title, position = pos, artists = listOf(by), addedAt = added)

    private suspend fun newService(random: Random = Random(7)) =
        MusicTvService(store, { null }, JellyfinClient(), ConfigStore("$base.toml").also { it.load() }, random = random)

    @BeforeTest fun setUp() = runBlocking {
        listOf("$base.db", "$base.db-wal", "$base.db-shm").forEach { runCatching { platform.posix.remove(it) } }
        store = MusicStore(createDatabase("$base.db"))
        val albums = listOf(
            album("a1", "Alpha", 2001, 1, "folk"), album("a2", "Beta", 1999, 2, "rock"), album("a3", "Cherry Lane", 2010, 3, "folk"),
            album("b1", "Delta", 2005, 0, "rock", by = ch),
        )
        val tracks = listOf(
            track("a1-1", "a1", 1, "Apple"), track("a1-2", "a1", 2, "Cherry"),
            track("a2-1", "a2", 1, "Banana"), track("a2-2", "a2", 2, "Date"),
            track("a3-1", "a3", 1, "Elder"), track("a3-2", "a3", 2, "Fig"),
            // A song of Harbour Lights' on Cherry Hill's album: `songsBy` has it, `ownAlbums` does not.
            track("guest", "b1", 1, "Guest"),
            track("yak", null, 0, "Yak", added = 5), track("zed", null, 0, "Zed", added = 10),
        )
        store.replaceLibrary(MusicLibraryRows("liba", listOf(MusicArtist(id = "x", libraryId = "liba", name = "Harbour Lights", path = "/m/x"), MusicArtist(id = "y", libraryId = "liba", name = "Cherry Hill", path = "/m/y")), albums, tracks))
        store.replaceLibrary(MusicLibraryRows("libb", listOf(MusicArtist(id = "z", libraryId = "libb", name = "Kvøld", path = "/m/z")),
            listOf(album("b9", "Fog", 2000, 9, "folk", by = MusicCredit("z", "Kvøld"), lib = "libb")),
            listOf(track("fog-1", "b9", 1, "Fog Song", by = MusicCredit("z", "Kvøld"), lib = "libb"))))
        svc = newService()
    }

    @AfterTest fun tearDown() { listOf("$base.db", "$base.db-wal", "$base.db-shm", "$base.toml").forEach { runCatching { platform.posix.remove(it) } } }

    private suspend fun ids(scope: String, sort: String? = null, genre: String? = null, q: String? = null, shuffle: Boolean = false, who: DeviceData = onlyA) =
        svc.selection(who, scope, sort, genre, q, shuffle)!!.tracks.map { it.id }

    @Test
    fun library_is_the_albums_newest_first_then_the_songs_on_no_album() = runBlocking<Unit> {
        assertEquals(listOf("a3-1", "a3-2", "a2-1", "a2-2", "a1-1", "a1-2", "guest", "zed", "yak"), ids("library"))
    }

    @Test
    fun the_per_artist_lookups_answer_what_a_scan_of_the_library_answers() = runBlocking<Unit> {
        // DEB-1344 — `ownAlbums` / `songsBy` are indexes now; each must equal the filter it replaced, in the same order.
        val v = svc.View(onlyA)
        for (id in listOf("x", "y", "z", "nobody")) {
            assertEquals(v.albums.values.filter { a -> a.albumArtists.any { it.artistId == id } }, v.ownAlbums(id))
            assertEquals(
                v.tracks.values.filter { t -> t.artists.any { it.artistId == id } || t.albumId?.let { v.albums[it] }?.albumArtists?.any { it.artistId == id } == true },
                v.songsBy(id),
            )
        }
        assertEquals(listOf("guest"), v.songsBy("y").map { it.id }.filter { it == "guest" }, "a song credited to one artist on another's album is the album artist's too")
    }

    @Test
    fun albums_follow_the_chips_sort_album_by_album() = runBlocking<Unit> {
        assertEquals(listOf("a1-1", "a1-2", "a2-1", "a2-2", "a3-1", "a3-2", "guest"), ids("albums", sort = "title"))
        assertEquals(listOf("a3-1", "a3-2", "guest", "a1-1", "a1-2", "a2-1", "a2-2"), ids("albums", sort = "year"))
        assertEquals(ids("albums"), ids("library").filter { it != "zed" && it != "yak" })
    }

    @Test
    fun a_genre_is_its_albums_in_the_same_order() = runBlocking<Unit> {
        assertEquals(listOf("a3-1", "a3-2", "a1-1", "a1-2"), ids("genre", genre = "folk"))
        assertEquals(listOf("a1-1", "a1-2", "a3-1", "a3-2"), ids("genre", sort = "title", genre = "folk"))
    }

    @Test
    fun artists_are_each_their_own_albums_oldest_first_then_the_songs_on_other_albums() = runBlocking<Unit> {
        // Harbour Lights: Beta (1999), Alpha (2001), Cherry Lane (2010), then their other songs in album-year order —
        // the two on no album (year 0) before the guest song on a 2005 album. Cherry Hill's own album only holds that
        // song, which the fold has already kept.
        assertEquals(listOf("a2-1", "a2-2", "a1-1", "a1-2", "a3-1", "a3-2", "yak", "zed", "guest"), ids("artists"))
        // A–Z: Cherry Hill first, so the guest song leads.
        assertEquals(listOf("guest", "a2-1", "a2-2", "a1-1", "a1-2", "a3-1", "a3-2", "yak", "zed"), ids("artists", sort = "title"))
    }

    @Test
    fun tracks_are_the_browse_routes_songs_in_its_order() = runBlocking<Unit> {
        assertEquals(listOf("a1-1", "a2-1", "a1-2", "a2-2", "a3-1", "a3-2", "guest", "yak", "zed"), ids("tracks", sort = "title"))
        val browse = svc.browse(onlyA, "tracks", "title", 0, null).tracks.map { it.id }
        assertEquals(browse, ids("tracks", sort = "title"))
    }

    @Test
    fun search_is_songs_then_albums_then_artists_with_nothing_twice() = runBlocking<Unit> {
        // The song *Cherry*, then the album *Cherry Lane*'s songs, then the artist *Cherry Hill*'s.
        assertEquals(listOf("a1-2", "a3-1", "a3-2", "guest"), ids("search", q = "cherry"))
        assertEquals(listOf("a1-2", "a3-1", "a3-2", "guest"), ids("search", q = "  CHERRY "))
    }

    @Test
    fun the_selection_is_the_viewers_own() = runBlocking<Unit> {
        assertEquals(listOf("fog-1"), ids("library", who = onlyB))
        assertFalse("fog-1" in ids("library"))
        assertTrue(svc.selection(onlyB, "search", null, null, "cherry", false)!!.tracks.isEmpty())
        assertEquals(0, svc.selection(onlyB, "search", null, null, "cherry", false)!!.total)
    }

    @Test
    fun an_unknown_scope_or_a_missing_genre_or_q_is_null_which_the_route_answers_400() = runBlocking<Unit> {
        assertNull(svc.selection(onlyA, "everything", null, null, null, false))
        assertNull(svc.selection(onlyA, "", null, null, null, false))
        assertNull(svc.selection(onlyA, "genre", null, null, null, false))
        assertNull(svc.selection(onlyA, "genre", null, "  ", null, false))
        assertNull(svc.selection(onlyA, "search", null, null, null, false))
        assertNull(svc.selection(onlyA, "search", null, null, "   ", false))
    }

    @Test
    fun a_small_selection_is_whole_whatever_shuffle_says() = runBlocking<Unit> {
        val s = svc.selection(onlyA, "library", null, null, null, shuffle = true)!!
        assertEquals(ids("library"), s.tracks.map { it.id })
        assertEquals(9, s.total); assertFalse(s.capped)
    }

    @Test
    fun the_cap_takes_the_first_thousand_or_a_sample_in_the_lists_order() = runBlocking<Unit> {
        runCatching { platform.posix.remove("$base.db") }
        store = MusicStore(createDatabase("$base.db"))
        val n = MUSIC_SELECTION_CAP + 100
        store.replaceLibrary(MusicLibraryRows("liba", emptyList(), emptyList(), (0 until n).map { track("s$it", null, 0, "Song $it", added = it.toLong() + 1) }))
        svc = newService(Random(1))
        // Newest first: s1099 … s100 are the first thousand.
        val plain = svc.selection(onlyA, "library", null, null, null, shuffle = false)!!
        assertEquals(n, plain.total); assertTrue(plain.capped)
        assertEquals((n - 1 downTo n - MUSIC_SELECTION_CAP).map { "s$it" }, plain.tracks.map { it.id })

        val shuffled = svc.selection(onlyA, "library", null, null, null, shuffle = true)!!
        assertEquals(n, shuffled.total); assertTrue(shuffled.capped)
        val got = shuffled.tracks.map { it.id }
        assertEquals(MUSIC_SELECTION_CAP, got.size); assertEquals(MUSIC_SELECTION_CAP, got.toSet().size)
        val full = (n - 1 downTo 0).map { "s$it" }
        assertEquals(got, full.filter { it in got.toSet() }, "a sample keeps the list's relative order")
        assertNotEquals(plain.tracks.map { it.id }.toSet(), got.toSet(), "drawn from the whole selection, not its head")
        // Another seed, another set.
        svc = newService(Random(2))
        assertNotEquals(got.toSet(), svc.selection(onlyA, "library", null, null, null, shuffle = true)!!.tracks.map { it.id }.toSet())
    }

    @Test
    fun exactly_the_cap_is_not_capped() = runBlocking<Unit> {
        runCatching { platform.posix.remove("$base.db") }
        store = MusicStore(createDatabase("$base.db"))
        store.replaceLibrary(MusicLibraryRows("liba", emptyList(), emptyList(), (0 until MUSIC_SELECTION_CAP).map { track("s$it", null, 0, "Song $it", added = it.toLong()) }))
        svc = newService()
        val s = svc.selection(onlyA, "library", null, null, null, shuffle = true)!!
        assertEquals(MUSIC_SELECTION_CAP, s.tracks.size); assertEquals(MUSIC_SELECTION_CAP, s.total); assertFalse(s.capped)
    }
}

/** R388 — an album plays what *Play album* plays (the official list, no extras), and a song on two albums is one. */
class MusicSelectionEditionsTest : MusicTvEditionsBase("selection-editions") {
    @Test
    fun an_album_with_an_official_tracklist_leaves_its_extras_out() = runBlocking<Unit> {
        val ids = svc.selection(open, "search", null, null, "kite weather", false)!!.tracks.map { it.id }
        assertFalse("kw-12" in ids, "the extra track is not part of Play album")
        assertTrue(ids.count { it.startsWith("kw-") } >= 10)
    }

    @Test
    fun a_song_held_twice_is_queued_once_and_a_library_the_viewer_cannot_open_is_left_out() = runBlocking<Unit> {
        val all = svc.selection(open, "search", null, null, "kite weather", false)!!.tracks
        assertEquals(1, all.count { it.title == "Kite Weather" })
        assertEquals(all.size, all.map { it.id }.toSet().size)
        val mine = svc.selection(onlyLib, "search", null, null, "kite weather", false)!!.tracks
        assertFalse("flac-1" in mine.map { it.id })
        assertEquals(1, mine.count { it.title == "Kite Weather" })
    }
}
