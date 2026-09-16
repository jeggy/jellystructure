package dev.jellystructure.tv

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.Screengrabber
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.tmdb.TmdbClient
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Phase 220 — verification 1: under a saturated process gate the request path serves the ORIGINAL
 * still rather than a 503 (FR-220-2), and the cache key the pre-sizer writes is the one `serveStill`
 * computes for the same episode (FR-220-1).
 */
class RaviloArtworkServiceTest {
    private lateinit var root: String
    private lateinit var dbPath: String
    private lateinit var store: MediaStore
    private lateinit var service: RaviloArtworkService

    @BeforeTest
    fun setUp() {
        val suffix = getpid()
        root = "/tmp/jellystructure-test-artwork-$suffix"
        SystemFileSystem.createDirectories(Path("$root/media/show"))
        dbPath = "$root/db.sqlite"
        val db = createDatabase(dbPath)
        val configStore = ConfigStore("$root/config.toml")
        store = MediaStore(db, JsTagStore("$root/tags.json"), configStore)
        service = RaviloArtworkService("$root/data", configStore, store, ArtworkDownloader(TmdbClient(configStore), Screengrabber()))
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    private fun write(path: String, bytes: ByteArray) {
        SystemFileSystem.sink(Path(path)).buffered().use { it.write(bytes, 0, bytes.size) }
    }

    private fun series(): MediaItem {
        val ep = Episode(filename = "ep1.mkv", path = "$root/media/show/ep1.mkv", seasonNumber = 1, episodeNumber = 1, tracks = emptyList(), issueCount = 0)
        return MediaItem(
            id = "show", title = "Show", year = 2020, kind = MediaKind.TV_SHOW, path = "$root/media/show", tmdbId = null,
            originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0,
            scannedAt = 0L, episodes = listOf(ep), jellyfinId = "jf-show",
        )
    }

    @Test
    fun `a saturated gate serves the original still instead of a 503`() = runBlocking {
        val item = series()
        store.addOrUpdate(item)
        val original = ByteArray(1_500) { (it % 251).toByte() }
        write("$root/media/show/ep1-thumb.jpg", original)
        service.forceGateTimeoutForTests = true

        val served = assertNotNull(service.serveStill("show", "ep1.mkv"), "an image is never worth a 503")
        assertContentEquals(original, served.first)
        assertEquals("image/jpeg", served.second)
        assertEquals(0, service.stats().resizesOnRequestLastHour, "a fallback is not a resize on the request path")
    }

    @Test
    fun `the pre-sizer and the request path derive the same still key`() {
        val ep = series().episodes.single()
        val key = service.stillCacheKey("show", ep)
        assertEquals("show-still-${"ep1.mkv".hashCode().toUInt()}-e1", key)
        assertEquals(key, service.stillCacheKey("show", ep, width = 640), "the default width adds no suffix")
        assertEquals("$key-320", service.stillCacheKey("show", ep, width = 320))
        assertEquals("show-season1-poster", service.seasonPosterCacheKey("show", 1))
    }
}
