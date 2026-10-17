package dev.jellystructure.music

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.Screengrabber
import dev.jellystructure.model.MusicArtist
import dev.jellystructure.model.MusicMatch
import dev.jellystructure.tmdb.TmdbClient
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 277 (FR-277-1a) — a matched artist with no folder in the library is left alone by the artwork and NFO
 * steps: no fanart.tv lookup (so no download), no `artist.nfo`. A matched artist with a folder is the control.
 */
class MusicFolderlessArtistTest {
    private val base = "/tmp/jellystructure-test-folderless-artist-${getpid()}"
    private val folder = "$base-artists/Harbour Lights"
    private lateinit var store: MusicStore
    private lateinit var media: MusicMediaService
    private var keyReads = 0

    private fun artist(id: String, path: String?) = MusicArtist(
        id = id, libraryId = "l", name = id, path = path, matchState = MusicMatch.MATCHED, mbid = "mb-$id", bioFetchedAt = 1L,
    )

    @BeforeTest fun setUp() = runBlocking {
        for (s in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$base.db$s") }
        SystemFileSystem.createDirectories(Path(folder))
        val cfg = ConfigStore("$base.toml").also { it.load() }
        store = MusicStore(createDatabase("$base.db"))
        store.replaceLibrary(MusicLibraryRows("l", listOf(artist("folderless", null), artist("foldered", folder)), emptyList(), emptyList()))
        media = MusicMediaService(store, ArtworkDownloader(TmdbClient(cfg), Screengrabber()), cfg, JellyfinClient(), FanartTvClient { keyReads++; "" })
    }

    @AfterTest fun tearDown() {
        dev.jellystructure.db.closeLastDatabaseForTests()
        listOf("$base.db", "$base.db-wal", "$base.db-shm", "$base.toml", "$folder/artist.nfo").forEach { runCatching { platform.posix.remove(it) } }
    }

    @Test
    fun fetch_artwork_asks_fanart_for_no_folderless_artist() = runBlocking {
        // Only the folderless artist is left, so a key read — `available` or `artist(...)` — would be a lookup for it.
        store.putArtist(artist("foldered", folder).copy(missingSince = 1L))
        media.fetchArtwork(scopeAll = false)
        assertEquals(0, keyReads)
    }

    @Test
    fun write_nfos_writes_none_for_a_folderless_artist_and_one_for_a_foldered() = runBlocking {
        val summary = media.writeNfos().sentence()
        assertEquals("1 artist.nfo written", summary)
        assertTrue(SystemFileSystem.exists(Path("$folder/artist.nfo")))
        assertFalse(store.snapshot().artists.getValue("folderless").nfoHash != null)
    }
}
