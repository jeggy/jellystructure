package dev.jellystructure.music

import dev.jellystructure.audiobooks.AudiobooksBrowse
import dev.jellystructure.audiobooks.AudiobooksStore
import dev.jellystructure.model.Audiobook
import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicArt
import dev.jellystructure.model.MusicArtist
import dev.jellystructure.model.MusicCredit
import dev.jellystructure.model.MusicMatch
import dev.jellystructure.model.MusicTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 293 (FR-293-6) — for every music and audiobooks triage key, the Dashboard's count equals the length of the
 * Library's list for `filter=<key>`. Both sides ask [MusicTriage]; this holds them to it on a library that has
 * every case the old facet mapping got wrong (a locked unmatched album, an unmatched album without a cover, an
 * artist without a folder, a FLAC that a phone plays).
 */
class MusicTriageTest {
    private val x = MusicCredit("x", "Harbour Lights")
    private val y = MusicCredit("y", "Kvøld")
    private val snap = MusicStore.Snapshot(
        artists = listOf(
            MusicArtist(id = "x", name = "Harbour Lights", path = "/m/HL", imageState = MusicArt.FILE),
            MusicArtist(id = "y", name = "Kvøld", path = "/m/K"),
            MusicArtist(id = "z", name = "Credit Only"),
            MusicArtist(id = "gone", name = "Gone", path = "/m/G", missingSince = 5),
        ).associateBy { it.id },
        albums = listOf(
            MusicAlbum(id = "a1", title = "Matched No Cover", albumArtists = listOf(x), matchState = MusicMatch.MATCHED, path = "/m/HL/One"),
            MusicAlbum(id = "a2", title = "Needs You", albumArtists = listOf(y), matchState = MusicMatch.NEEDS_YOU, path = "/m/K/Two"),
            MusicAlbum(id = "a3", title = "Locked Unmatched", albumArtists = listOf(y), matchState = MusicMatch.UNMATCHED, matchLocked = true, path = "/m/K/Three"),
            MusicAlbum(id = "a4", title = "Unmatched No Cover", albumArtists = listOf(x), matchState = MusicMatch.UNMATCHED, path = "/m/HL/Four"),
            MusicAlbum(id = "a5", title = "Matched Cover", albumArtists = listOf(x), matchState = MusicMatch.MATCHED, coverState = MusicArt.FILE, path = "/m/HL/Five"),
        ).associateBy { it.id },
        tracks = listOf(
            MusicTrack(id = "t1", albumId = "a1", title = "One", container = "asf", codec = "wmav2", artists = listOf(x)),
            MusicTrack(id = "t2", albumId = "a1", title = "Two", container = "flac", codec = "flac", artists = listOf(x), jellyfinProviderIds = mapOf("MusicBrainzTrack" to "m")),
            MusicTrack(id = "t3", albumId = "a2", title = "Three", container = "ape", codec = "ape", artists = listOf(y)),
            MusicTrack(id = "t4", albumId = "a5", title = "Four", container = "mp3", codec = "mp3", artists = listOf(x)),
            MusicTrack(id = "t5", albumId = "a5", title = "Gone", container = "asf", codec = "wmav2", artists = listOf(x), missingSince = 9),
        ).associateBy { it.id },
    )

    @Test
    fun every_music_key_count_equals_its_list() {
        val p = MusicTriage.Music(snap, emptySet())
        for (key in MusicTriage.MUSIC.keys) {
            val view = MusicTriage.viewOf(key)!!
            val r = MusicBrowse.browse(snap, view, emptyMap(), null, emptyMap(), triage = key)
            assertEquals(p.count(key), r.rowsTotal, key)
            val listed = when (view) { MusicBrowse.ALBUMS -> r.albums.size; MusicBrowse.ARTISTS -> r.artists.size; else -> r.songs.size }
            assertEquals(r.rowsTotal, listed, key)
        }
    }

    @Test
    fun the_predicates_are_the_triage_rules_not_the_facets() {
        val p = MusicTriage.Music(snap, emptySet())
        val needs = MusicBrowse.browse(snap, MusicBrowse.ALBUMS, emptyMap(), null, emptyMap(), triage = "music_needs_match")
        assertEquals(setOf("a2", "a4"), needs.albums.map { it.id }.toSet(), "a locked unmatched album is not among them")
        assertEquals(listOf("a1"), MusicBrowse.browse(snap, MusicBrowse.ALBUMS, emptyMap(), null, emptyMap(), triage = "music_no_cover").albums.map { it.id })
        assertEquals(listOf("y"), MusicBrowse.browse(snap, MusicBrowse.ARTISTS, emptyMap(), null, emptyMap(), triage = "music_no_picture").artists.map { it.id })
        assertEquals(setOf("t1", "t3"), MusicBrowse.browse(snap, MusicBrowse.SONGS, emptyMap(), null, emptyMap(), triage = "music_reencodes").songs.map { it.id }.toSet())
        assertEquals(setOf("t1", "t4"), MusicBrowse.browse(snap, MusicBrowse.SONGS, emptyMap(), null, emptyMap(), triage = "music_files_no_ids").songs.map { it.id }.toSet())
        assertEquals(2, p.songAlbums("music_files_no_ids"))
    }

    @Test
    fun facets_narrow_within_the_key_and_count_honestly() {
        val r = MusicBrowse.browse(snap, MusicBrowse.SONGS, mapOf("format" to setOf("WMA")), null, emptyMap(), triage = "music_reencodes")
        assertEquals(listOf("t1"), r.songs.map { it.id })
        val format = r.facets.single { it.key == "format" }.values.associate { it.value to it.count }
        assertEquals(1, format["WMA"]); assertEquals(1, format["other"], "the facet counts only rows inside the key")
        assertEquals(0, format["MP3"] ?: 0)
    }

    /** Phase 305 (test 19) — a WMA copy folded behind a FLAC song still counts: `filter=` lists every copy (293). */
    @Test
    fun a_folded_wma_copy_still_counts_for_reencodes() {
        val rec = dev.jellystructure.model.MusicRecording.AGREES
        val folded = snap.copy(tracks = snap.tracks +
            ("t2" to snap.tracks["t2"]!!.copy(bitrate = 900_000, recordingMbid = "r-two", recordingState = rec)) +
            ("t6" to MusicTrack(id = "t6", albumId = "a5", title = "Two", container = "asf", codec = "wmav2", bitrate = 128_000, artists = listOf(x), recordingMbid = "r-two", recordingState = rec)))
        assertEquals(listOf("t2"), MusicBrowse.browse(folded, MusicBrowse.SONGS, emptyMap(), "Two", emptyMap()).songs.map { it.id }, "folded behind the FLAC")
        val p = MusicTriage.Music(folded, emptySet())
        val r = MusicBrowse.browse(folded, MusicBrowse.SONGS, emptyMap(), null, emptyMap(), triage = "music_reencodes")
        assertEquals(p.count("music_reencodes"), r.rowsTotal)
        assertTrue(r.songs.any { it.id == "t6" })
    }

    @Test
    fun an_unknown_key_or_a_key_for_another_view_narrows_nothing() {
        val all = MusicBrowse.browse(snap, MusicBrowse.ALBUMS, emptyMap(), null, emptyMap()).rowsTotal
        assertEquals(all, MusicBrowse.browse(snap, MusicBrowse.ALBUMS, emptyMap(), null, emptyMap(), triage = "nope").rowsTotal)
        assertEquals(all, MusicBrowse.browse(snap, MusicBrowse.ALBUMS, emptyMap(), null, emptyMap(), triage = "music_reencodes").rowsTotal)
        assertEquals(null, MusicTriage.viewOf("nope"))
    }

    @Test
    fun every_audiobooks_key_count_equals_its_list() {
        val books = AudiobooksStore.Snapshot(
            books = listOf(
                Audiobook(id = "b1", title = "Gap", gap = listOf(6), narrators = listOf("N")),
                Audiobook(id = "b2", title = "Gap Dismissed", gap = listOf(2), gapDismissed = true, coverState = MusicArt.FILE),
                Audiobook(id = "b3", title = "Two", albumTags = listOf("A", "B"), coverState = MusicArt.FILE, narrators = listOf("N")),
                Audiobook(id = "b4", title = "Fine", coverState = MusicArt.FILE, narrators = listOf("N")),
                Audiobook(id = "b5", title = "Gone", missingSince = 3),
            ).associateBy { it.id },
            parts = emptyMap(), authors = emptyMap(),
        )
        for (key in MusicTriage.AUDIOBOOKS.keys) {
            val r = AudiobooksBrowse.browse(books, "audiobooks", emptyMap(), null, emptyMap(), { 0 }, triage = key)
            assertEquals(MusicTriage.bookCount(key, books), r.books.size, key)
            assertTrue(r.books.all { it.id != "b5" })
        }
        assertEquals(listOf("b1"), AudiobooksBrowse.browse(books, "audiobooks", emptyMap(), null, emptyMap(), { 0 }, triage = "audiobooks_missing_part").books.map { it.id })
    }
}
