package dev.jellystructure.tv

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.Screengrabber
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.tmdb.TmdbClient
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * R303 (FR-R303-2, dev review item 2) — the play push names what is playing: an episode's own title (it
 * used to be null), the `S2 · E7` kicker in the shape the detail screens build, the series' name, and a
 * logo only when one is really on disk (so a receiver's `<img>` never flashes a 404).
 */
class PlayPushResolverTest {
    private lateinit var dbPath: String
    private lateinit var mediaStore: MediaStore
    private lateinit var resolver: PlayPushResolver

    @BeforeTest
    fun setUp() {
        val suffix = getpid()
        dbPath = "/tmp/jellystructure-test-playpush-$suffix.db"
        val db = dev.jellystructure.db.createDatabase(dbPath)
        val configStore = ConfigStore("/tmp/jellystructure-test-playpush-$suffix.toml")
        mediaStore = MediaStore(db, JsTagStore("/tmp/jellystructure-test-playpush-$suffix-tags.json"), configStore)
        resolver = PlayPushResolver(mediaStore, ArtworkDownloader(TmdbClient(configStore), Screengrabber()))
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    private fun movie(id: String, title: String) = MediaItem(
        id = id, title = title, year = 2020, kind = MediaKind.MOVIE, path = "/movies/$id.mkv", tmdbId = null,
        originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0,
        scannedAt = 0L, episodes = emptyList(), jellyfinId = "jf-$id",
    )

    private fun series(id: String, title: String, episodes: List<Episode>) = MediaItem(
        id = id, title = title, year = 2020, kind = MediaKind.TV_SHOW, path = "/tv/$id", tmdbId = null,
        originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0,
        scannedAt = 0L, episodes = episodes, jellyfinId = "jf-$id",
    )

    private fun episode(jf: String, s: Int?, e: Int?, title: String?) = Episode(
        filename = "e.mkv", path = "/tv/x/e.mkv", seasonNumber = s, episodeNumber = e, tracks = emptyList(), issueCount = 0,
        title = title, jellyfinId = jf,
    )

    @Test
    fun `an episode resolves to its own title the S·E kicker and the series' name`() = runBlocking {
        mediaStore.addOrUpdate(series("hh", "Havets Hjarta", listOf(episode("jf-ep", 2, 7, "The Storm"))))
        val r = resolver.resolve("jf-ep")
        assertEquals("episode", r.kind)
        assertEquals("The Storm", r.title)
        assertEquals("S2 · E7", r.kicker)
        assertEquals("Havets Hjarta", r.seriesName)
        assertNull(r.logoUrl, "no clearlogo on disk ⇒ no URL, never a URL that 404s")
        assertNull(r.logoInk)
    }

    @Test
    fun `an untitled episode still names itself and a numberless one carries no kicker`() = runBlocking {
        mediaStore.addOrUpdate(series("hh", "Havets Hjarta", listOf(episode("jf-a", 1, 3, ""), episode("jf-b", null, null, null))))
        assertEquals("Episode 3", resolver.resolve("jf-a").title)
        assertEquals("S1 · E3", resolver.resolve("jf-a").kicker)
        val b = resolver.resolve("jf-b")
        assertEquals("Havets Hjarta", b.title); assertNull(b.kicker); assertEquals("Havets Hjarta", b.seriesName)
    }

    @Test
    fun `a film has no series name and a series played as a whole is a series`() = runBlocking {
        mediaStore.addOrUpdate(movie("bbb", "Big Buck Bunny"))
        mediaStore.addOrUpdate(series("hh", "Havets Hjarta", emptyList()))
        val m = resolver.resolve("jf-bbb")
        assertEquals("movie", m.kind); assertEquals("Big Buck Bunny", m.title); assertNull(m.seriesName); assertNull(m.kicker)
        val s = resolver.resolve("jf-hh")
        assertEquals("series", s.kind); assertEquals("Havets Hjarta", s.title); assertNull(s.seriesName)
    }

    @Test
    fun `an id this library does not hold is the pre-R303 push`() = runBlocking {
        assertEquals(PlayPushResolver.Resolved("movie", null, null, null, null, null), resolver.resolve("nope"))
    }
}
