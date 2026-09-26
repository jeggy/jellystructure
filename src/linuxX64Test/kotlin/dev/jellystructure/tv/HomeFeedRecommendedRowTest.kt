package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceIdentityRegistry
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinPlayItem
import dev.jellystructure.auth.JellyfinUserData
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.GenreCatalog
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 269 (FR-269-1/2, acceptance 1 and 5) — the row on Home: sent as a plain CUSTOM row an installed
 *  app draws as it is, marked for R318's See all, showing the row's limit of the stored list. */
class HomeFeedRecommendedRowTest {
    private lateinit var dbPath: String

    @BeforeTest fun setUp() { GenreCatalog.replaceForTest(emptyMap()); DeviceIdentityRegistry.clear(); dbPath = "/tmp/jellystructure-test-recrow-${getpid()}.db" }
    @AfterTest fun tearDown() { for (s in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$s") } }

    private fun film(n: Int) = MediaItem(
        id = "m$n", title = "Film $n", year = 2010, kind = MediaKind.MOVIE, path = "/lib/m$n.mkv", tmdbId = 3000 + n,
        originalLanguage = "en", posterPath = null, overview = null, tracks = emptyList(), issueCount = 0, scannedAt = 0L,
        jellyfinId = "jf$n", genres = listOf("G${n % 2}"),
    )

    @Test
    fun `the row reaches Home as CUSTOM with recommendations and the row's limit`() = runBlocking {
        val db = createDatabase(dbPath)
        val configStore = ConfigStore("/tmp/jellystructure-test-recrow-${getpid()}.toml")
        val mediaStore = MediaStore(db, JsTagStore("/tmp/jellystructure-test-recrow-${getpid()}-tags.json"), configStore)
        val bus = TvEventBus(CoroutineScope(SupervisorJob()))
        val configService = RaviloConfigService(db, bus)
        val devices = RaviloDeviceService(db)
        val home = HomeFeedService(mediaStore, configService, JellyfinClient(), configStore, bus, ArtworkDownloader(TmdbClient(configStore), Screengrabber()))
        val watched = JellyfinPlayItem(id = "jf1", type = "Movie", name = "jf1", userData = JellyfinUserData(played = true, lastPlayedDate = "2026-09-20T10:00:00.0000000Z"))
        val recs = RecommendationService(db, mediaStore, JellyfinClient(), configStore, devices,
            historySource = { RecommendationService.History(listOf(watched), emptyList(), emptySet()) })
        home.recommendations = recs
        (1..60).forEach { mediaStore.addOrUpdate(film(it)) }
        val device = devices.loginDevice(deviceId = "tv", deviceName = "TV", jellyfinUserId = "u1", jellyfinUsername = "u1",
            jellyfinUserToken = "t", isAdmin = false, isKids = false, appVersion = null, platform = "tv").first
        configService.save(GLOBAL_USER_ID, RaviloConfig(rows = listOf(RowConfig(id = "rec", kind = RowKind.RECOMMENDED, limit = 15))))

        // Before any build: nothing stored, no starter list either — the row is simply absent (never an error).
        assertTrue(home.getHomeFeed(device).rows.none { it.recommendations })

        recs.buildAll()
        val row = assertNotNull(home.getHomeFeed(device).rows.singleOrNull { it.id == "rec" }, "a rebuilt list replaces the cached feed")
        assertEquals(RowKind.CUSTOM, row.kind, "an installed app decodes RowKind strictly")
        assertTrue(row.recommendations)
        assertNull(row.seedQuery); assertNull(row.seedMediaKind)
        assertEquals(15, row.items.size, "the row's own limit")
        assertEquals(RecommendationEngine.LIST_SIZE, row.seedTotalCount, "See all has all 50")
        assertTrue(row.items.none { it.id == "jf1" }, "never what they watched")
        assertEquals(RecommendationEngine.LIST_SIZE, home.recommendedItems(device, null).size)
    }
}
