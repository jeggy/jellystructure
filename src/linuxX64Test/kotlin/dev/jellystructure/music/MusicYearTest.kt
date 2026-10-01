package dev.jellystructure.music

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicCredit
import dev.jellystructure.model.MusicReleaseOption
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.model.originalDate
import dev.jellystructure.model.originalYear
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 290 — an album's year is the year it first came out (acceptance 1, 4, 5, 6). */
class MusicYearTest {
    /** A 2004 album whose folder holds the 2015 reissue. */
    private val reissue = MusicAlbum(id = "alb", title = "Salt & Stone", year = 2015, releaseGroupMbid = "rg-1", firstReleaseDate = "2004-03-01")

    @Test
    fun musicbrainz_first_release_wins_when_it_is_earlier() {
        assertEquals("2004-03-01", reissue.originalDate())
        assertEquals(2004, reissue.originalYear())
    }

    @Test
    fun the_files_year_wins_when_it_is_earlier() {
        // FR-290-1 / D2 — a copy dated 1977 proves the album existed by 1977, whatever a (likely wrong) match says.
        val a = reissue.copy(year = 1977, firstReleaseDate = "1991")
        assertEquals("1977", a.originalDate())
        assertEquals(1977, a.originalYear())
    }

    @Test
    fun the_same_year_keeps_the_precise_date() {
        assertEquals("2004-03-01", reissue.copy(year = 2004).originalDate())
    }

    @Test
    fun an_unmatched_or_cleared_album_shows_the_files_year() {
        assertEquals(2015, reissue.copy(releaseGroupMbid = null).originalYear())
        assertEquals(2015, reissue.copy(firstReleaseDate = null).originalYear())
        assertEquals(2015, reissue.copy(firstReleaseDate = "").originalYear())
        assertEquals("2004", reissue.copy(year = null, firstReleaseDate = "2004").originalDate())
        assertNull(MusicAlbum(id = "x", title = "x").originalDate())
    }

    @Test
    fun the_nfo_says_the_original_year() {
        val xml = MusicNfo.albumXml(reissue, emptyList(), emptyMap())
        assertTrue("<year>2004</year>" in xml && "<originalreleasedate>2004-03-01</originalreleasedate>" in xml, xml)
    }

    @Test
    fun the_files_get_the_original_date_in_both_tags() = runBlocking {
        val base = "/tmp/jellystructure-test-musicyear-${getpid()}"
        runCatching { platform.posix.remove("$base.db") }
        try {
            val store = MusicStore(createDatabase("$base.db"))
            val album = reissue.copy(libraryId = "lib", albumArtists = listOf(MusicCredit("x", "Harbour Lights")))
            val track = MusicTrack(id = "t1", albumId = "alb", libraryId = "lib", title = "First", position = 1)
            store.replaceLibrary(MusicLibraryRows("lib", emptyList(), listOf(album), listOf(track)))
            val writer = MusicTagWriter(store, ConfigStore("$base.toml").also { it.load() }, null, JellyfinClient(), null)
            val m = writer.wanted(album, track, listOf(track))
            assertEquals("2004-03-01", m["date"])
            assertEquals("2004-03-01", m["originaldate"])
        } finally {
            listOf("$base.db", "$base.toml").forEach { runCatching { platform.posix.remove(it) } }
        }
    }

    @Test
    fun find_match_lists_the_pressing_in_use_first_among_equals() {
        val opts = listOf(
            MusicReleaseOption("old", date = "1999", agreeing = 12),
            MusicReleaseOption("digital-2010", date = "2010-04-27", agreeing = 13),
            MusicReleaseOption("hires-2015", date = "2015-09-15", agreeing = 13, disambiguation = "24bit/96kHz"),
            MusicReleaseOption("named-2015", date = "2015", agreeing = 13),
        )
        assertEquals(listOf("digital-2010", "named-2015", "hires-2015", "old"), MusicScoring.orderReleases(opts, null, null).map { it.mbid })
        assertEquals(listOf("named-2015", "digital-2010", "hires-2015", "old"), MusicScoring.orderReleases(opts, null, "named-2015").map { it.mbid })
        assertEquals(listOf("hires-2015", "named-2015", "digital-2010", "old"), MusicScoring.orderReleases(opts, "hires-2015", "named-2015").map { it.mbid })
        // Agreement still comes first: the pressing in use does not jump a better one.
        assertEquals("digital-2010", MusicScoring.orderReleases(opts, "old", null).first().mbid)
    }
}
