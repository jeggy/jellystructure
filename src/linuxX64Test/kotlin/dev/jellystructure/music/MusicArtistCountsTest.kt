package dev.jellystructure.music

import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicArtist
import dev.jellystructure.model.MusicCredit
import dev.jellystructure.model.MusicTrack
import kotlin.test.Test
import kotlin.test.assertEquals

/** DEB-5847 (FR-1, FR-2) — an artist's counts in one pass, and the artist page's album rows for its own albums only. */
class MusicArtistCountsTest {
    private val x = MusicCredit("x", "Harbour Lights")
    private val y = MusicCredit("y", "Kvøld")
    private val z = MusicCredit("z", "Guest Singer")
    private val snap = MusicStore.Snapshot(
        artists = listOf(MusicArtist(id = "x", name = "Harbour Lights"), MusicArtist(id = "y", name = "Kvøld"), MusicArtist(id = "z", name = "Guest Singer"), MusicArtist(id = "w", name = "Nobody"))
            .associateBy { it.id },
        albums = listOf(
            MusicAlbum(id = "a1", title = "Salt Window", year = 2004, albumArtists = listOf(x), addedAt = 10),
            MusicAlbum(id = "a2", title = "Fog Songs", year = 1998, albumArtists = listOf(y), addedAt = 20),
            MusicAlbum(id = "a3", title = "Lost Tide", year = 2010, albumArtists = listOf(x), missingSince = 5),
            MusicAlbum(id = "a4", title = "Singles", year = 2004, albumArtists = listOf(x), addedAt = 30),
        ).associateBy { it.id },
        tracks = listOf(
            MusicTrack(id = "t1", albumId = "a1", title = "One", position = 1, artists = listOf(x)),
            MusicTrack(id = "t2", albumId = "a1", title = "Two", position = 2, artists = listOf(y, z)),
            MusicTrack(id = "t3", albumId = "a2", title = "Three", position = 1, artists = listOf(y)),
            MusicTrack(id = "t4", albumId = "a4", title = "One", position = 1, artists = listOf(x)),
            MusicTrack(id = "t5", albumId = "a2", title = "Gone", position = 2, artists = listOf(z), missingSince = 7),
        ).associateBy { it.id },
    )

    // The two old per-artist functions, kept as the oracle.
    private fun oldSongsBy(id: String) = snap.tracks.values.filter { it.missingSince == null }.filter { t ->
        t.artists.any { it.artistId == id } || (t.albumId?.let { snap.albums[it] }?.albumArtists?.any { it.artistId == id } == true)
    }.mapTo(HashSet()) { snap.songs.songOf(it) }.size
    private fun oldOwn(id: String) = snap.albums.values.filter { it.missingSince == null }.count { a -> a.albumArtists.any { it.artistId == id } }

    @Test
    fun the_maps_equal_the_old_per_artist_counts_for_every_artist() {
        for (id in snap.artists.keys) {
            assertEquals(oldSongsBy(id), snap.songCountByArtist[id] ?: 0, "songs of $id")
            assertEquals(oldOwn(id), snap.ownAlbumCountByArtist[id] ?: 0, "own albums of $id")
        }
        assertEquals(0, snap.songCountByArtist["z"]?.minus(1) ?: 0)
        assertEquals(1, snap.ownAlbumCountByArtist["y"])
    }

    @Test
    fun the_artist_page_rows_equal_the_whole_library_browse_filtered_to_its_albums() {
        for (id in snap.artists.keys) {
            val ids = snap.albumsByArtist[id].orEmpty().filter { it.missingSince == null }.map { it.id }.toSet()
            val whole = MusicBrowse.browse(snap, MusicBrowse.ALBUMS, emptyMap(), null, emptyMap(), "year").albums.filter { it.id in ids }
            assertEquals(whole, MusicBrowse.albumRows(snap, emptySet(), ids), "rows of $id")
        }
    }
}
