package dev.jellystructure.music

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicArt
import dev.jellystructure.model.MusicArtist
import dev.jellystructure.model.MusicCredit
import dev.jellystructure.model.MusicTrack
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 279 — the phone's music is the viewer's: scoped to the libraries they may open (acceptance 3), no mix on a
 *  small library (acceptance 2), and lyrics read from our own sidecar when Jellyfin has none yet. */
class MusicTvServiceTest {
    private val base = "/tmp/jellystructure-test-musictv-${getpid()}"
    private lateinit var store: MusicStore
    private lateinit var svc: MusicTvService
    private val lrc = "$base.lrc"

    private val open = device(null)
    private val onlyA = device(setOf("liba"))
    private val onlyB = device(setOf("libb"))
    private fun device(allowed: Set<String>?) = DeviceData("d", "tok", "u1", "viewer", "", isAdmin = false, allowedLibraries = allowed, kind = "phone")

    @BeforeTest fun setUp() = runBlocking {
        runCatching { platform.posix.remove("$base.db") }
        store = MusicStore(createDatabase("$base.db"))
        val hl = MusicCredit("x", "Harbour Lights")
        val albums = (1..3).map { i -> MusicAlbum(id = "a$i", libraryId = "liba", title = "Album $i", albumArtists = listOf(hl), genres = listOf("folk"), coverState = if (i == 1) MusicArt.FILE else MusicArt.NONE, addedAt = i.toLong()) }
        val tracks = albums.map { a -> MusicTrack(id = "t-${a.id}", albumId = a.id, libraryId = "liba", title = "Song of ${a.title}", position = 1, artists = listOf(hl), path = if (a.id == "a1") "$base.mp3" else null) }
        store.replaceLibrary(MusicLibraryRows("liba", listOf(MusicArtist(id = "x", libraryId = "liba", name = "Harbour Lights", path = "/m/x")), albums, tracks))
        store.replaceLibrary(MusicLibraryRows("libb", listOf(MusicArtist(id = "y", libraryId = "libb", name = "Kvøld", path = "/m/y")),
            listOf(MusicAlbum(id = "b1", libraryId = "libb", title = "Fog", albumArtists = listOf(MusicCredit("y", "Kvøld")))),
            listOf(MusicTrack(id = "t-b1", albumId = "b1", libraryId = "libb", title = "Fog Song", artists = listOf(MusicCredit("y", "Kvøld"))))))
        // No Jellyfin configured: the viewer's own numbers are empty, and nothing is fetched.
        svc = MusicTvService(store, { t -> if (t.id == "t-a1") lrc else null }, JellyfinClient(), ConfigStore("$base.toml").also { it.load() })
    }

    @AfterTest fun tearDown() {
        listOf("$base.db", "$base.toml", lrc).forEach { runCatching { platform.posix.remove(it) } }
    }

    @Test
    fun a_viewer_sees_only_the_libraries_they_may_open() = runBlocking {
        assertEquals(setOf("a1", "a2", "a3"), svc.browse(onlyA, "albums", null, 0, null).albums.map { it.id }.toSet())
        assertEquals(listOf("b1"), svc.browse(onlyB, "albums", null, 0, null).albums.map { it.id })
        assertEquals(4, svc.browse(open, "tracks", null, 0, null).total)
        assertNull(svc.album(onlyB, "a1"))
        assertFalse(svc.visible(onlyB, "t-a1")); assertTrue(svc.visible(onlyA, "t-a1"))
        assertEquals(0, svc.search(onlyB, "harbour").artistsTotal)
        assertEquals(1, svc.search(onlyA, "harbour").artistsTotal)
    }

    @Test
    fun home_newest_first_no_mix_and_a_genre_row_at_three_albums() = runBlocking {
        val rows = svc.home(onlyA).rows
        assertEquals(listOf("recent", "artists", "genre"), rows.map { it.key })
        assertEquals(listOf("a3", "a2", "a1"), rows.first().albums.map { it.id })
        assertEquals("folk", rows.last().title)
        assertEquals("/api/tv/image/music/album/a1?v=${store.album("a1")!!.updatedAt}", rows.first().albums.last().imageUrl)
        assertNull(rows.first().albums.first().imageUrl, "no cover ⇒ no URL; the phone sets a wordmark")
        assertTrue(svc.home(onlyB).rows.none { it.key == "genre" })
    }

    /** R344 (dev review 12) — `versions` on every song, from 292's shown set; `version_types` on the bootstrap. */
    @Test
    fun songs_carry_their_versions_and_home_carries_the_colours() = runBlocking {
        store.putTracks(listOf(store.track("t-a1")!!.copy(recordingMbid = "r1", recordingState = dev.jellystructure.model.MusicRecording.AGREES)))
        store.setChoices(listOf("rec:r1"), mapOf("session" to true, "live" to false), 5)
        store.putVersionType("session", "#123456", null)
        val tracks = svc.album(onlyA, "a1")!!.tracks
        assertEquals(listOf("session"), tracks.single().versions, "a Session whose Live the owner removed goes alone")
        assertEquals(emptyList(), svc.album(onlyA, "a2")!!.tracks.single().versions)
        val types = svc.home(onlyA).versionTypes
        assertEquals(9, types.size)
        assertEquals("#123456", types.single { it.key == "session" }.color)
        assertEquals("#f0795b", types.single { it.key == "live" }.color)
    }

    /** R344 (dev review 2) — the app's compiled-in colours are the admin's defaults. */
    @Test
    fun ravilos_default_colours_are_the_admins() {
        assertEquals(dev.jellystructure.model.MusicVersions.TYPES.map { it.key to it.color }, dev.jellystructure.shared.tv.MusicVersionDefaults.TYPES.map { it.key to it.color })
    }

    /** 292 (dev review 8d) — no lyrics for a song with no singing, even from our own sidecar. */
    @Test
    fun an_instrumental_has_no_lyrics_for_the_viewer() = runBlocking {
        dev.jellystructure.io.FileIo.writeText(kotlinx.io.files.Path(lrc), "[00:01.50]First line\n")
        store.putTracks(listOf(store.track("t-a1")!!.copy(title = "Song of Album 1 (instrumental)", lyricsState = dev.jellystructure.model.MusicLyrics.SYNCED)))
        assertNull(svc.lyrics(onlyA, "t-a1"))
        assertFalse(svc.album(onlyA, "a1")!!.tracks.single().hasLyrics)
        assertEquals(listOf("instrumental"), svc.album(onlyA, "a1")!!.tracks.single().versions)
    }

    @Test
    fun lyrics_fall_back_to_our_sidecar_and_parse_lrc() = runBlocking {
        dev.jellystructure.io.FileIo.writeText(kotlinx.io.files.Path(lrc), "[ar:Harbour Lights]\n[00:01.50]First line\n[00:12.00][01:02.00]Twice\n")
        val l = svc.lyrics(onlyA, "t-a1")!!
        assertEquals(listOf(1500L, 12_000L, 62_000L), l.synced!!.map { it.tMs })
        assertEquals("Twice", l.synced!!.last().line)
        assertNull(svc.lyrics(onlyB, "t-a1"), "not visible ⇒ nothing")
        assertNull(svc.lyrics(onlyA, "t-a2"))
    }
}
