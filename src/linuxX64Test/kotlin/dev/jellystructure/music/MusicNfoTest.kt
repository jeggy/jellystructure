package dev.jellystructure.music

import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.MusicConfig
import dev.jellystructure.config.MusicSteps
import dev.jellystructure.io.FileIo
import dev.jellystructure.media.effectivePipeline
import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicArtist
import dev.jellystructure.model.MusicCredit
import dev.jellystructure.model.MusicGenreVote
import dev.jellystructure.model.MusicMbCredit
import dev.jellystructure.model.MusicReleaseOption
import dev.jellystructure.model.MusicTrack
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MusicNfoTest {
    private val album = MusicAlbum(
        id = "alb", title = "Salt & Stone", path = "/music/HL/Salt", albumArtists = listOf(MusicCredit("ja1", "Harbour Lights")),
        releaseGroupMbid = "rg-1", releaseMbid = "rel-1", primaryType = "Album", secondaryTypes = listOf("Compilation"),
        firstReleaseDate = "2004-03-01", release = MusicReleaseOption("rel-1", date = "2005-01-01", label = "Fjord Records", trackCount = 18),
        mbArtists = listOf(MusicMbCredit("mb-a1", "Harbour Lights")),
        mbGenres = listOf(MusicGenreVote("folk", 9), MusicGenreVote("rock", 3)),
    )
    private val tracks = listOf(
        MusicTrack(id = "t2", albumId = "alb", title = "Second", position = 2, durationMs = 200_000, releaseTrackMbid = "tr-2"),
        MusicTrack(id = "t18", albumId = "alb", title = "Last", position = 18, durationMs = 241_500),
        MusicTrack(id = "gone", albumId = "alb", title = "Gone", position = 5, missingSince = 1),
    )
    private val artists = mapOf("ja1" to MusicArtist(id = "ja1", name = "Harbour Lights", mbid = "mb-a1"))

    @Test
    fun album_nfo_lists_the_tracks_on_disk_with_ids_genres_and_dates() {
        val xml = MusicNfo.albumXml(album, tracks, artists)
        assertTrue("<title>Salt &amp; Stone</title>" in xml)
        assertTrue("<musicbrainzalbumid>rel-1</musicbrainzalbumid>" in xml)
        assertTrue("<musicbrainzreleasegroupid>rg-1</musicbrainzreleasegroupid>" in xml)
        assertTrue("<musicbrainzalbumartistid>mb-a1</musicbrainzalbumartistid>" in xml)
        assertTrue("<genre>folk</genre>" in xml && "<genre>rock</genre>" in xml)
        assertTrue("<compilation>true</compilation>" in xml)
        assertTrue("<year>2004</year>" in xml && "<label>Fjord Records</label>" in xml)
        assertTrue("<musicBrainzArtistID>mb-a1</musicBrainzArtistID>" in xml)
        assertTrue("<duration>3:20</duration>" in xml && "<duration>4:01</duration>" in xml)
        assertTrue("<musicBrainzTrackID>tr-2</musicBrainzTrackID>" in xml)
        assertFalse("Gone" in xml)                          // acceptance 2: a partial album lists what it holds
        assertTrue(xml.indexOf("Second") < xml.indexOf("Last"))
    }

    @Test
    fun artist_nfo_says_formed_for_a_group_and_born_for_a_person() {
        val group = MusicArtist(id = "a", name = "Harbour Lights", mbid = "m", type = "Group", lifeSpan = "1999–2010",
            biographies = mapOf("en" to "A band from the coast.", "da" to "Et band."), imageCredit = "A. Photographer · CC BY-SA 4.0 · Wikimedia Commons")
        val xml = MusicNfo.artistXml(group, listOf(album))
        assertTrue("<formed>1999</formed>" in xml && "<disbanded>2010</disbanded>" in xml)
        assertTrue("<biography>A band from the coast.</biography>" in xml)
        assertTrue("<musicbrainzartistid>m</musicbrainzartistid>" in xml && "<musicBrainzArtistID>m</musicBrainzArtistID>" in xml)
        assertTrue("<!-- Picture: A. Photographer · CC BY-SA 4.0 · Wikimedia Commons -->" in xml)
        val person = MusicNfo.artistXml(group.copy(type = "Person", lifeSpan = "1950–", biographyEdited = "Typed here."), emptyList())
        assertTrue("<born>1950</born>" in person && "<died>" !in person)
        assertTrue("<biography>Typed here.</biography>" in person)   // the admin's own text wins
    }

    @Test
    fun the_write_rule() = runBlocking {
        val path = "/tmp/jellystructure-test-musicnfo-${getpid()}.nfo"
        runCatching { platform.posix.remove(path) }
        val xml = MusicNfo.albumXml(album, tracks, artists)
        val first = MusicNfo.write(path, xml, null, overwrite = false)
        assertEquals(MusicNfo.Outcome.WRITTEN, first.outcome)
        assertEquals(MusicNfo.Outcome.UNCHANGED, MusicNfo.write(path, xml, first.hash, overwrite = false).outcome)
        // Jellyfin's own saver rewrites it.
        FileIo.writeText(Path(path), xml.replace("<genre>rock</genre>", "<genre>Rock</genre>").replace("<label>Fjord Records</label>", ""))
        val skipped = MusicNfo.write(path, xml, first.hash, overwrite = false)
        assertEquals(MusicNfo.Outcome.FOREIGN_SKIPPED, skipped.outcome); assertEquals(1, skipped.driftFields)
        val reasserted = MusicNfo.write(path, xml, first.hash, overwrite = true)
        assertEquals(MusicNfo.Outcome.WRITTEN, reasserted.outcome); assertEquals(xml, MusicNfo.read(path))
        assertEquals(MusicNfo.Outcome.NO_FOLDER, MusicNfo.write(null, xml, null, overwrite = true).outcome)
        platform.posix.remove(path)
        Unit
    }

    @Test
    fun an_artists_videos_are_found_by_the_filename_artist() {
        fun mv(id: String, artist: String?, year: Int) = dev.jellystructure.model.MediaItem(
            id = id, title = id, year = year, kind = dev.jellystructure.model.MediaKind.MUSIC_VIDEO, path = "/mv/$id.mkv",
            tmdbId = null, originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0,
            scannedAt = 0, director = artist,
        )
        val a = MusicArtist(id = "a", name = "Harbour Lights", aliases = listOf("The Harbour Lights"))
        val found = MusicVideoLinks.forArtist(a, listOf(mv("v2", "harbour-lights", 2010), mv("v1", "The Harbour Lights", 2003), mv("x", "Someone Else", 2001), mv("y", null, 2001)))
        assertEquals(listOf("v1", "v2"), found.map { it.id })
    }

    @Test
    fun lyrics_are_in_the_run_only_while_the_switch_is_on() {
        assertTrue(effectivePipeline(AppConfig()).any { it.step == MusicSteps.LYRICS })
        assertFalse(effectivePipeline(AppConfig(music = MusicConfig(fetchLyrics = false))).any { it.step == MusicSteps.LYRICS })
    }
}
