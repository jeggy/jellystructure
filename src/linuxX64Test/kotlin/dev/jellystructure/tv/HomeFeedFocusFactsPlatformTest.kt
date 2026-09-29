package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.Screengrabber
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.shared.tv.HomeFeed
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.RowConfig
import dev.jellystructure.shared.tv.RowKind
import dev.jellystructure.tmdb.TmdbClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * R314 (FR-R314-3/5) — a phone or the web app gets Home without R240's per-title facts; a TV, and a device
 * that never reported a platform, get them. One cached feed serves every platform.
 */
class HomeFeedFocusFactsPlatformTest {
    private lateinit var dbPath: String
    private lateinit var service: HomeFeedService

    private val device = DeviceData(
        deviceId = "dev1", deviceToken = "tok1", jellyfinUserId = "user1", jellyfinUsername = "user1",
        jellyfinUserToken = "jtok1", isAdmin = false,
    )

    @BeforeTest
    fun setUp() {
        val suffix = getpid()
        dbPath = "/tmp/jellystructure-test-r314-$suffix.db"
        val db = createDatabase(dbPath)
        val configStore = ConfigStore("/tmp/jellystructure-test-r314-$suffix.toml")
        val mediaStore = MediaStore(db, JsTagStore("/tmp/jellystructure-test-r314-$suffix-tags.json"), configStore)
        val tvEventBus = TvEventBus(CoroutineScope(SupervisorJob()))
        val configService = RaviloConfigService(db, tvEventBus)
        val artwork = ArtworkDownloader(TmdbClient(configStore), Screengrabber())
        service = HomeFeedService(mediaStore, configService, JellyfinClient(), configStore, tvEventBus, artwork)
        runBlocking {
            mediaStore.addOrUpdate(movie("m1")); mediaStore.addOrUpdate(movie("m2"))
            // Focus detail on (Phase 202's defaults resolve to rowOpen), so the build attaches facts.
            configService.save(GLOBAL_USER_ID, RaviloConfig(rows = listOf(RowConfig(id = "continue", kind = RowKind.CONTINUE, title = "Continue Watching"))))
            service.continueSourceForTest = { listOf(Triple(movie("m1"), 10f, 2L), Triple(movie("m2"), 20f, 1L)) }
            service.refreshContinueListFor(device)
        }
    }

    @AfterTest
    fun tearDown() { for (s in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$s") } }

    private fun movie(id: String) = MediaItem(
        id = id, title = id, year = 2020, kind = MediaKind.MOVIE, path = "/movies/$id.mkv", tmdbId = null,
        originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0,
        scannedAt = 0L, episodes = emptyList(), jellyfinId = id,
    )

    private fun HomeFeed.cards() = rows.flatMap { it.items }
    private suspend fun homeOn(platform: String?) = service.getHomeFeed(device.copy(platform = platform))

    @Test
    fun `a phone the web app and the Mac get no facts but a TV and an unreported platform do`() = runBlocking {
        val tv = homeOn("tv")
        assertEquals(2, tv.cards().size)
        assertTrue(tv.cards().all { it.focusDetail != null }, "a TV keeps R240's facts")
        assertTrue(homeOn(null).cards().all { it.focusDetail != null }, "no reported platform is treated as before")
        for (p in listOf("phone", "web", "mac", "linux")) {   // R328 — the Mac app and its Linux dev build
            val feed = homeOn(p)
            assertEquals(tv.cards().map { it.id }, feed.cards().map { it.id }, p)
            assertTrue(feed.cards().all { it.focusDetail == null }, "$p gets no facts")
            assertEquals(tv.focusDetail, feed.focusDetail, "$p keeps the top-level mode; the app decides")
        }
    }

    @Test
    fun `one cached feed serves every platform`() = runBlocking {
        val first = homeOn("tv")
        homeOn("phone"); homeOn("web")
        assertSame(first, homeOn("tv"), "stripping for a phone never replaces or fragments the per-user cache")
    }
}
