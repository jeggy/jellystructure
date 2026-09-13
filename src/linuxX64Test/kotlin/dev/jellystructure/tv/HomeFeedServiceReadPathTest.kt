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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phase 205 (FR-205-1) — the invariant this phase exists for: a viewer read must never wait on Jellyfin.
 * `apiKeys.jellyfinUrl` is deliberately left blank here (never configured) and no background refresh is
 * ever run — before this phase, `getHomeFeed`'s Continue Watching row built live on exactly this kind of
 * cache miss, so a blank/unreachable Jellyfin would have made the whole call fail or hang. Proving
 * `getHomeFeed`/`getChannelFeed` complete cleanly with a genuinely empty Continue Watching cache pins
 * down that the reader path ([HomeFeedService.canonicalContinueList]) never falls back to building live.
 */
class HomeFeedServiceReadPathTest {
    private lateinit var dbPath: String
    private lateinit var service: HomeFeedService
    private lateinit var mediaStore: MediaStore
    private lateinit var configService: RaviloConfigService

    private val device = DeviceData(
        deviceId = "dev1", deviceToken = "tok1", jellyfinUserId = "user1", jellyfinUsername = "user1",
        jellyfinUserToken = "jtok1", isAdmin = false,
    )

    @BeforeTest
    fun setUp() {
        val suffix = getpid()
        dbPath = "/tmp/jellystructure-test-readpath-$suffix.db"
        val db = createDatabase(dbPath)
        val configStore = ConfigStore("/tmp/jellystructure-test-readpath-$suffix.toml")
        val jsTagStore = JsTagStore("/tmp/jellystructure-test-readpath-$suffix-tags.json")
        mediaStore = MediaStore(db, jsTagStore, configStore)
        val tvEventBus = TvEventBus(CoroutineScope(SupervisorJob()))
        configService = RaviloConfigService(db, tvEventBus)
        val artwork = ArtworkDownloader(TmdbClient(configStore), Screengrabber())
        service = HomeFeedService(mediaStore, configService, JellyfinClient(), configStore, tvEventBus, artwork)
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) {
            runCatching { platform.posix.remove("$dbPath$suffix") }
        }
    }

    private fun movie(id: String) = MediaItem(
        id = id, title = id, year = 2020, kind = MediaKind.MOVIE, path = "/movies/$id.mkv", tmdbId = null,
        originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0,
        scannedAt = 0L, episodes = emptyList(), jellyfinId = id,
    )

    @Test
    fun `getHomeFeed with Continue Watching configured but never refreshed completes with no Continue row`() = runBlocking {
        mediaStore.addOrUpdate(movie("m1"))
        configService.save(
            GLOBAL_USER_ID,
            RaviloConfig(rows = listOf(RowConfig(id = "continue", kind = RowKind.CONTINUE, title = "Continue Watching"))),
        )

        val feed = service.getHomeFeed(device)  // must not throw, hang, or attempt a Jellyfin call

        assertTrue(feed.rows.none { it.kind == RowKind.CONTINUE }, "an unrefreshed Continue Watching cache must render as an absent row, never a live build")
    }

    @Test
    fun `getChannelFeed for a channel with no configured channels completes safely`() = runBlocking {
        mediaStore.addOrUpdate(movie("m1"))
        configService.save(GLOBAL_USER_ID, RaviloConfig())

        val feed = service.getChannelFeed(device, "does-not-exist")

        assertTrue(feed.heroes.isEmpty() && feed.rows.isEmpty(), "an unknown channel id returns an empty feed, not an error")
    }
}
