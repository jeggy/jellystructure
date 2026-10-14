package dev.jellystructure.music

import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicArt
import dev.jellystructure.model.MusicArtist
import dev.jellystructure.model.MusicCredit
import dev.jellystructure.model.MusicGenreVote
import dev.jellystructure.model.MusicLyrics
import dev.jellystructure.model.MusicMatch
import dev.jellystructure.model.MusicTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 278 (FR-278-1..3) — the Music kind's rows and facet counts, decided on the server. */
class MusicBrowseTest {
    private val x = MusicCredit("x", "Harbour Lights")
    private val y = MusicCredit("y", "Kvøld")
    private val z = MusicCredit("z", "Guest Singer")
    private val snap = MusicStore.Snapshot(
        artists = listOf(
            MusicArtist(id = "x", name = "Harbour Lights", path = "/m/HL", imageState = MusicArt.FILE, matchState = MusicMatch.MATCHED),
            MusicArtist(id = "y", name = "Kvøld", path = "/m/K"),
            MusicArtist(id = "z", name = "Guest Singer"),
        ).associateBy { it.id },
        albums = listOf(
            MusicAlbum(id = "a1", title = "Salt Window", year = 2004, albumArtists = listOf(x), matchState = MusicMatch.MATCHED, coverState = MusicArt.FILE,
                mbGenres = listOf(MusicGenreVote("folk", 9), MusicGenreVote("rock", 1)), secondaryTypes = listOf("Live"), addedAt = 10),
            MusicAlbum(id = "a2", title = "Fog Songs", year = 1998, albumArtists = listOf(y), addedAt = 20),
        ).associateBy { it.id },
        tracks = listOf(
            MusicTrack(id = "t1", albumId = "a1", title = "One", position = 1, container = "mp3", codec = "mp3", artists = listOf(x), lyricsState = MusicLyrics.SYNCED),
            MusicTrack(id = "t2", albumId = "a1", title = "Two", position = 2, container = "mp3", codec = "mp3", artists = listOf(x, z)),
            MusicTrack(id = "t3", albumId = "a2", title = "Three", position = 1, container = "asf", codec = "wmav2", artists = listOf(y)),
        ).associateBy { it.id },
    )
    private fun facet(r: MusicBrowse.Result, key: String) = r.facets.single { it.key == key }.values.associate { it.value to it.count }

    @Test
    fun albums_newest_first_with_counts_that_ignore_their_own_facet() {
        val all = MusicBrowse.browse(snap, MusicBrowse.ALBUMS, emptyMap(), null, emptyMap())
        assertEquals(listOf("a2", "a1"), all.albums.map { it.id })
        assertEquals(1, facet(all, "format")["WMA"]); assertEquals(1, facet(all, "format")["MP3"])
        assertEquals("live", all.albums.single { it.id == "a1" }.type)

        val wma = MusicBrowse.browse(snap, MusicBrowse.ALBUMS, mapOf("format" to setOf("WMA")), null, emptyMap())
        assertEquals(listOf("a2"), wma.albums.map { it.id })
        assertEquals(1, facet(wma, "format")["MP3"], "picking a format never zeroes the other formats")
        assertEquals(0, facet(wma, "match")["matched"]); assertEquals(1, facet(wma, "match")[MusicMatch.UNMATCHED])
        assertTrue(wma.facets.single { it.key == "format" }.values.single { it.value == "WMA" }.let { it.on && it.note != null })
    }

    @Test
    fun songs_take_album_facets_but_keep_their_own_format_and_lyrics() {
        val songs = MusicBrowse.browse(snap, MusicBrowse.SONGS, mapOf("match" to setOf("matched")), null, emptyMap())
        assertEquals(listOf("t1", "t2"), songs.songs.map { it.id })
        assertEquals(1, facet(songs, "lyrics")["has"]); assertEquals(1, facet(songs, "lyrics")["missing"])
        assertTrue(MusicBrowse.browse(snap, MusicBrowse.SONGS, emptyMap(), null, emptyMap()).songs.single { it.id == "t3" }.reencodes)
    }

    @Test
    fun a_credit_only_artist_is_listed_as_credited_and_found_by_its_albums_facets() {
        val artists = MusicBrowse.browse(snap, MusicBrowse.ARTISTS, emptyMap(), null, emptyMap())
        val guest = artists.artists.single { it.id == "z" }
        assertEquals(0, guest.albums); assertEquals(1, guest.songs); assertFalse(guest.folder)
        assertEquals(2, artists.artists.single { it.id == "x" }.songs)
        val folk = MusicBrowse.browse(snap, MusicBrowse.ARTISTS, mapOf("genre" to setOf("folk")), null, emptyMap())
        assertEquals(setOf("x", "z"), folk.artists.map { it.id }.toSet())
    }

    @Test
    fun search_and_genres() {
        assertEquals(listOf("a2"), MusicBrowse.browse(snap, MusicBrowse.ALBUMS, emptyMap(), "kvø", emptyMap()).albums.map { it.id })
        assertEquals(listOf("t2"), MusicBrowse.browse(snap, MusicBrowse.SONGS, emptyMap(), "guest", emptyMap()).songs.map { it.id })
        val g = MusicBrowse.genres(snap)
        assertEquals("folk", g.first().name); assertEquals(1, g.first().albums); assertEquals(2, g.first().songs)
    }

    // ── Phase 278 (FR-278-14) — the Songs window ──

    /** 250 songs on one album: enough that a page is a page, and every one of them its own recording key, so 305's
     *  folding leaves the row count alone and the arithmetic below is about paging and nothing else. */
    private val many = MusicStore.Snapshot(
        artists = mapOf("x" to MusicArtist(id = "x", name = "Harbour Lights", path = "/m/HL", matchState = MusicMatch.MATCHED)),
        albums = mapOf("a1" to MusicAlbum(id = "a1", title = "Salt Window", year = 2004, albumArtists = listOf(x), matchState = MusicMatch.MATCHED, coverState = MusicArt.FILE)),
        tracks = (1..250).associate { i ->
            val id = "t${i.toString().padStart(3, '0')}"
            // Every fifth song is a WMA, so one facet count has a number worth checking against the whole set.
            val wma = i % 5 == 0
            id to MusicTrack(id = id, albumId = "a1", title = "Song $i", position = i, artists = listOf(x),
                container = if (wma) "asf" else "mp3", codec = if (wma) "wmav2" else "mp3")
        },
    )

    @Test
    fun a_songs_page_is_a_window_and_every_count_stays_the_whole_set() {
        val whole = MusicBrowse.browse(many, MusicBrowse.SONGS, emptyMap(), null, emptyMap())
        assertEquals(250, whole.rowsTotal); assertEquals(250, whole.songs.size)
        assertEquals(null, whole.page, "no page_size means the whole list, and the echo says so")
        assertEquals(null, whole.pageSize)

        val p1 = MusicBrowse.browse(many, MusicBrowse.SONGS, emptyMap(), null, emptyMap(), page = 1, pageSize = 100)
        assertEquals(100, p1.songs.size, "page_size=100 answers with 100 rows")
        assertEquals(250, p1.rowsTotal, "total counts the library, not the page")
        assertEquals(facet(whole, "format"), facet(p1, "format"), "every facet count is the whole-set number")
        assertEquals(50, facet(p1, "format")["WMA"]); assertEquals(200, facet(p1, "format")["MP3"])
        assertEquals(1, p1.page); assertEquals(100, p1.pageSize)
        assertEquals(whole.folded, p1.folded, "folded describes the library too")
    }

    @Test
    fun the_pages_are_the_sorted_list_in_order_and_run_out_rather_than_fail() {
        val whole = MusicBrowse.browse(many, MusicBrowse.SONGS, emptyMap(), null, emptyMap()).songs.map { it.id }
        val paged = (1..3).flatMap { p -> MusicBrowse.browse(many, MusicBrowse.SONGS, emptyMap(), null, emptyMap(), page = p, pageSize = 100).songs.map { it.id } }
        assertEquals(whole, paged, "the pages concatenate to the one sorted list — the window is taken after the comparator")
        assertEquals(50, MusicBrowse.browse(many, MusicBrowse.SONGS, emptyMap(), null, emptyMap(), page = 3, pageSize = 100).songs.size)
        // The admin's scroller reads an empty page as the end of the sequence, so past the end is empty, not an error.
        assertEquals(0, MusicBrowse.browse(many, MusicBrowse.SONGS, emptyMap(), null, emptyMap(), page = 4, pageSize = 100).songs.size)
        assertEquals(250, MusicBrowse.browse(many, MusicBrowse.SONGS, emptyMap(), null, emptyMap(), page = 4, pageSize = 100).rowsTotal)
        assertEquals(0, MusicBrowse.browse(many, MusicBrowse.SONGS, emptyMap(), null, emptyMap(), page = Int.MAX_VALUE, pageSize = 100).songs.size, "a page far past the end cannot wrap")
    }

    @Test
    fun a_window_narrows_within_the_filter_and_leaves_albums_and_artists_alone() {
        val wma = MusicBrowse.browse(many, MusicBrowse.SONGS, mapOf("format" to setOf("WMA")), null, emptyMap(), page = 1, pageSize = 20)
        assertEquals(20, wma.songs.size); assertEquals(50, wma.rowsTotal)
        assertTrue(wma.songs.all { it.reencodes }, "the page is a window on the filtered rows, not on the library")
        assertEquals(200, facet(wma, "format")["MP3"], "picking a format never zeroes the other formats, paged or not")
        // FR-278-14 — the window is the Songs rows' alone; the other two views answer whole, and echo no page.
        val albums = MusicBrowse.browse(many, MusicBrowse.ALBUMS, emptyMap(), null, emptyMap(), page = 1, pageSize = 1)
        assertEquals(1, albums.albums.size, "one album in this snapshot, whole either way")
        assertEquals(null, albums.page); assertEquals(null, albums.pageSize)
        val artists = MusicBrowse.browse(many, MusicBrowse.ARTISTS, emptyMap(), null, emptyMap(), page = 2, pageSize = 1)
        assertEquals(1, artists.artists.size, "page 2 of the Artists view is still every artist — it does not page")
        assertEquals(null, artists.page)
    }

    @Test
    fun a_page_size_that_is_not_a_window_is_the_whole_list() {
        for (size in listOf(0, -1)) {
            val r = MusicBrowse.browse(many, MusicBrowse.SONGS, emptyMap(), null, emptyMap(), page = 1, pageSize = size)
            assertEquals(250, r.songs.size, "page_size=$size is not a window"); assertEquals(null, r.pageSize)
        }
        // A page below 1 is read as the first page rather than as a negative offset.
        val p0 = MusicBrowse.browse(many, MusicBrowse.SONGS, emptyMap(), null, emptyMap(), page = 0, pageSize = 10)
        assertEquals(1, p0.page)
        assertEquals(MusicBrowse.browse(many, MusicBrowse.SONGS, emptyMap(), null, emptyMap(), page = 1, pageSize = 10).songs.map { it.id }, p0.songs.map { it.id })
    }
}
