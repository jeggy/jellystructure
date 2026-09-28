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
}
