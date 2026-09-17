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
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.RowConfig
import dev.jellystructure.shared.tv.RowKind
import dev.jellystructure.tmdb.TmdbClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Phase 229 (FR-229-5) — a playback stop must never take Continue Watching off Home. Live on the stue
 * TV 2026-09-17: `invalidatePlaystate` removed the user's Continue list BEFORE rebuilding it, a Home
 * request landed inside the rebuild, and the rowless feed was cached for FEED_TTL_MS (5 min).
 */
class HomeFeedServiceStopKeepsContinueRowTest {
    private lateinit var dbPath: String
    private lateinit var service: HomeFeedService
    private lateinit var mediaStore: MediaStore

    private val device = DeviceData(
        deviceId = "dev1", deviceToken = "tok1", jellyfinUserId = "user1", jellyfinUsername = "user1",
        jellyfinUserToken = "jtok1", isAdmin = false,
    )

    @BeforeTest
    fun setUp() {
        val suffix = getpid()
        dbPath = "/tmp/jellystructure-test-stoprow-$suffix.db"
        val db = createDatabase(dbPath)
        val configStore = ConfigStore("/tmp/jellystructure-test-stoprow-$suffix.toml")
        val jsTagStore = JsTagStore("/tmp/jellystructure-test-stoprow-$suffix-tags.json")
        mediaStore = MediaStore(db, jsTagStore, configStore)
        val tvEventBus = TvEventBus(CoroutineScope(SupervisorJob()))
        val configService = RaviloConfigService(db, tvEventBus)
        val artwork = ArtworkDownloader(TmdbClient(configStore), Screengrabber())
        service = HomeFeedService(mediaStore, configService, JellyfinClient(), configStore, tvEventBus, artwork)
        runBlocking {
            mediaStore.addOrUpdate(movie("m1")); mediaStore.addOrUpdate(movie("m2"))
            configService.save(GLOBAL_USER_ID, RaviloConfig(rows = listOf(RowConfig(id = "continue", kind = RowKind.CONTINUE, title = "Continue Watching"))))
        }
    }

    @AfterTest
    fun tearDown() { for (s in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$s") } }

    private fun movie(id: String) = MediaItem(
        id = id, title = id, year = 2020, kind = MediaKind.MOVIE, path = "/movies/$id.mkv", tmdbId = null,
        originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0,
        scannedAt = 0L, episodes = emptyList(), jellyfinId = id,
    )

    private fun entry(id: String, pct: Float = 10f, ts: Long = 1L) = Triple(movie(id), pct, ts)
    private suspend fun continueIds() = service.getHomeFeed(device).rows.firstOrNull { it.kind == RowKind.CONTINUE }?.items?.map { it.id }

    @Test
    fun `a Home request during the stop's rebuild still carries the Continue row`() = runBlocking {
        service.continueSourceForTest = { listOf(entry("m1")) }
        service.refreshContinueListFor(device)
        assertEquals(listOf("m1"), continueIds())

        val gate = CompletableDeferred<Unit>()
        service.continueSourceForTest = { gate.await(); listOf(entry("m2", ts = 2L), entry("m1")) }
        val stop = launch { service.invalidatePlaystate(device) }
        yield()  // the rebuild is now suspended "at Jellyfin"

        assertEquals(listOf("m1"), continueIds(), "mid-rebuild Home must serve the pre-stop row, never a rowless feed")

        gate.complete(Unit); stop.join()
        assertEquals(listOf("m2", "m1"), continueIds(), "and the post-stop list is served the moment the rebuild lands")
    }

    @Test
    fun `a failed rebuild after a stop leaves the previous list standing`() = runBlocking {
        service.continueSourceForTest = { listOf(entry("m1")) }
        service.refreshContinueListFor(device)
        service.continueSourceForTest = { null }  // R231: timeout ⇒ untrustworthy build
        service.invalidatePlaystate(device)
        assertEquals(listOf("m1"), continueIds())
    }

    @Test
    fun `a list changed by the background loop reaches Home without waiting for the feed TTL`() = runBlocking {
        service.continueSourceForTest = { listOf(entry("m1")) }
        service.refreshContinueListFor(device)
        assertEquals(listOf("m1"), continueIds())
        service.continueSourceForTest = { listOf(entry("m2", ts = 2L), entry("m1")) }
        service.refreshContinueListFor(device)
        assertEquals(listOf("m2", "m1"), continueIds())
    }

    @Test
    fun `an unchanged rebuild does not invalidate the cached feed but a changed one does`() = runBlocking {
        service.continueSourceForTest = { listOf(entry("m1")) }
        service.refreshContinueListFor(device)
        val first = service.getHomeFeed(device)
        service.refreshContinueListFor(device)
        assertSame(first, service.getHomeFeed(device), "same content ⇒ same stamp ⇒ cache hit")
        service.continueSourceForTest = { listOf(entry("m1", pct = 55f)) }
        service.refreshContinueListFor(device)
        assertNotSame(first, service.getHomeFeed(device))
    }
}
