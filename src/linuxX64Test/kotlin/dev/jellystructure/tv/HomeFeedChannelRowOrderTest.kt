package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
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
import dev.jellystructure.shared.tv.ChannelConfig
import dev.jellystructure.shared.tv.ChannelRowsConfig
import dev.jellystructure.shared.tv.ChannelSystemRows
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.RowConfig
import dev.jellystructure.shared.tv.RowKind
import dev.jellystructure.shared.tv.SystemContinue
import dev.jellystructure.shared.tv.SystemNewly
import dev.jellystructure.tmdb.TmdbClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DEB-2850 (DEB-2848 FR-1..FR-3) — a Custom collection serves its rows in `rows.order`: Continue Watching and Newly
 * Added move among the filter rows, a collection nobody reordered serves today's sequence, and a stale or repeated
 * id never breaks it. Offline: the Continue list is seeded through [HomeFeedService.continueSourceForTest].
 */
class HomeFeedChannelRowOrderTest {
    private lateinit var dbPath: String
    private lateinit var service: HomeFeedService
    private lateinit var configService: RaviloConfigService
    private lateinit var device: DeviceData

    @BeforeTest
    fun setUp() { runBlocking {
        GenreCatalog.replaceForTest(emptyMap()); DeviceIdentityRegistry.clear()
        val suffix = getpid()
        dbPath = "/tmp/jellystructure-test-chorder-$suffix.db"
        val db = createDatabase(dbPath)
        val configStore = ConfigStore("/tmp/jellystructure-test-chorder-$suffix.toml")
        val mediaStore = MediaStore(db, JsTagStore("/tmp/jellystructure-test-chorder-$suffix-tags.json"), configStore)
        val bus = TvEventBus(CoroutineScope(SupervisorJob()))
        configService = RaviloConfigService(db, bus)
        val devices = RaviloDeviceService(db)
        service = HomeFeedService(mediaStore, configService, JellyfinClient(), configStore, bus, ArtworkDownloader(TmdbClient(configStore), Screengrabber()))
        val watched = JellyfinPlayItem(id = "jf1", type = "Movie", name = "jf1", userData = JellyfinUserData(played = true, lastPlayedDate = "2026-09-20T10:00:00.0000000Z"))
        val recs = RecommendationService(db, mediaStore, JellyfinClient(), configStore, devices,
            historySource = { RecommendationService.History(listOf(watched), emptyList(), emptySet()) })
        service.recommendations = recs
        // Films alternate between genres G0 and G1 so row-a / row-b each match; one series so Newly Added splits in two.
        val items = (1..60).map { film(it) } + series()
        for (i in items) mediaStore.addOrUpdate(i)
        service.continueSourceForTest = { items.take(3).mapIndexed { n, mi -> Triple(mi, 10f, 100L - n) } }
        device = devices.loginDevice(deviceId = "tv", deviceName = "TV", jellyfinUserId = "u1", jellyfinUsername = "u1",
            jellyfinUserToken = "t", isAdmin = false, isKids = false, appVersion = null, platform = "tv").first
        service.refreshContinueListFor(device)
        recs.buildAll()
    } }

    @AfterTest
    fun tearDown() {
        for (s in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$s") }
    }

    private fun film(n: Int) = MediaItem(
        id = "m$n", title = "Film $n", year = 2010, kind = MediaKind.MOVIE, path = "/lib/m$n.mkv", tmdbId = 3000 + n,
        originalLanguage = "en", posterPath = null, overview = null, tracks = emptyList(), issueCount = 0, scannedAt = 0L,
        jellyfinId = "jf$n", genres = listOf("Action", "G${n % 2}"),
    )

    private fun series() = MediaItem(
        id = "s1", title = "Show 1", year = 2012, kind = MediaKind.TV_SHOW, path = "/lib/s1", tmdbId = 4001,
        originalLanguage = "en", posterPath = null, overview = null, tracks = emptyList(), issueCount = 0, scannedAt = 0L,
        episodes = emptyList(), jellyfinId = "jfs1", genres = listOf("Action", "G0"),
    )

    private val rowA = RowConfig(id = "row-a", kind = RowKind.GENRE, title = "G0", order = 0)
    private val rowB = RowConfig(id = "row-b", kind = RowKind.GENRE, title = "G1", order = 1)
    private val rec = RowConfig(id = "row-rec", kind = RowKind.RECOMMENDED, order = 2)

    private suspend fun served(
        order: List<String> = emptyList(),
        merge: Boolean = false,
        continueShown: Boolean = true,
    ): List<String> {
        val rows = ChannelRowsConfig(
            mode = "custom", items = listOf(rowA, rowB, rec), order = order,
            system = ChannelSystemRows(cont = SystemContinue(show = continueShown), newly = SystemNewly(merge = merge)),
        )
        configService.save(GLOBAL_USER_ID, RaviloConfig(channels = listOf(ChannelConfig(id = "c", filterGenre = "Action", rows = rows))))
        return service.getChannelFeed(device, "c").rows.map { it.id }
    }

    @Test
    fun `an empty order serves today's sequence`() = runBlocking {
        assertEquals(listOf("continue", "newly-added-movies", "newly-added-series", "row-a", "row-b", "row-rec"), served())
    }

    @Test
    fun `a mixed order moves the system rows among the filter rows and appends an unnamed item`() = runBlocking {
        assertEquals(
            listOf("row-b", "newly-added-movies", "newly-added-series", "row-a", "continue", "row-rec"),
            served(order = listOf("row-b", "system:newly", "row-a", "system:continue")),
        )
    }

    @Test
    fun `a merged Newly Added is one row at its position`() = runBlocking {
        assertEquals(
            listOf("row-b", "newly-added", "row-a", "continue", "row-rec"),
            served(order = listOf("row-b", "system:newly", "row-a", "system:continue"), merge = true),
        )
    }

    @Test
    fun `a missing system token goes to the head and a stale id and a repeat are skipped`() = runBlocking {
        assertEquals(
            listOf("continue", "newly-added-movies", "newly-added-series", "row-b", "row-a", "row-rec"),
            served(order = listOf("row-b", "row-gone", "row-b")),
        )
    }

    @Test
    fun `a hidden Continue row is absent and the others keep their relative order`() = runBlocking {
        assertEquals(
            listOf("row-b", "newly-added-movies", "newly-added-series", "row-a", "row-rec"),
            served(order = listOf("row-b", "system:continue", "system:newly", "row-a", "row-rec"), continueShown = false),
        )
    }

    @Test
    fun `the key is optional on the wire and survives a round trip`() {
        val json = Json { ignoreUnknownKeys = true }
        assertTrue(json.decodeFromString<ChannelRowsConfig>("""{"mode":"custom"}""").order.isEmpty())
        val withOrder = ChannelRowsConfig(mode = "custom", order = listOf("row-b", "system:newly"))
        assertEquals(withOrder, json.decodeFromString<ChannelRowsConfig>(json.encodeToString(ChannelRowsConfig.serializer(), withOrder)))
        assertTrue(Json.encodeToString(ChannelRowsConfig.serializer(), withOrder).contains("\"order\":[\"row-b\",\"system:newly\"]"))
    }

    @Test
    fun `saving a collection keeps its order`() = runBlocking {
        served(order = listOf("row-b", "system:newly"))
        val saved = configService.getConfig("u1").channels.single().rows!!
        assertEquals(listOf("row-b", "system:newly"), saved.order)
    }
}
