package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceIdentityRegistry
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinPlayItem
import dev.jellystructure.auth.JellyfinUserData
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.GenreCatalog
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.ChannelConfig
import dev.jellystructure.shared.tv.ChannelRowsConfig
import dev.jellystructure.shared.tv.Condition
import dev.jellystructure.shared.tv.ConditionGroup
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.RowConfig
import dev.jellystructure.shared.tv.RowKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 269 (FR-269-11) — the stored list, the request-time skip, and the wire mapping an installed app reads. */
class RecommendationServiceTest {
    private lateinit var dbPath: String
    private lateinit var mediaStore: MediaStore
    private lateinit var devices: RaviloDeviceService
    private lateinit var service: RecommendationService
    private val histories = HashMap<String, RecommendationService.History>()

    @BeforeTest
    fun setUp() {
        GenreCatalog.replaceForTest(emptyMap())
        DeviceIdentityRegistry.clear()
        dbPath = "/tmp/jellystructure-test-recs-${getpid()}.db"
        val db = createDatabase(dbPath)
        val configStore = ConfigStore("/tmp/jellystructure-test-recs-${getpid()}.toml")
        mediaStore = MediaStore(db, JsTagStore("/tmp/jellystructure-test-recs-${getpid()}-tags.json"), configStore)
        devices = RaviloDeviceService(db)
        service = RecommendationService(db, mediaStore, JellyfinClient(), configStore, devices, historySource = { d -> histories[d.jellyfinUserId] })
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    private fun film(n: Int) = MediaItem(
        id = "m$n", title = "Film $n", year = 2000 + n % 20, kind = MediaKind.MOVIE, path = "/lib/m$n.mkv", tmdbId = 2000 + n,
        originalLanguage = "en", posterPath = null, overview = null, tracks = emptyList(), issueCount = 0, scannedAt = 0L,
        jellyfinId = "jf$n", genres = listOf("G${n % 3}"),
    )

    private fun played(id: String) = JellyfinPlayItem(id = id, type = "Movie", name = id, userData = JellyfinUserData(played = true, lastPlayedDate = "2026-09-20T10:00:00.0000000Z"))

    private fun login(user: String) = devices.loginDevice(
        deviceId = "tv-$user", deviceName = "TV", jellyfinUserId = user, jellyfinUsername = user,
        jellyfinUserToken = "jf-$user", isAdmin = false, isKids = false, appVersion = null, platform = "tv",
    ).first

    @Test
    fun `a full build stores fifty per viewer and each list is only their own`() = runBlocking {
        (1..70).forEach { mediaStore.addOrUpdate(film(it)) }
        val a = login("ann"); val b = login("ben")
        histories["ann"] = RecommendationService.History(listOf(played("jf1"), played("jf4")), emptyList(), emptySet())
        histories["ben"] = RecommendationService.History(emptyList(), emptyList(), emptySet())
        service.buildAll()
        val live = mediaStore.liveItems()
        val annList = service.servedFor(a, live)
        val benList = service.servedFor(b, live)
        assertEquals(RecommendationEngine.LIST_SIZE, annList.size)
        assertEquals(RecommendationEngine.LIST_SIZE, benList.size, "no history: the starter list, full")
        assertTrue(annList.none { it.jellyfinId in setOf("jf1", "jf4") })
        assertTrue(benList.first().jellyfinId in setOf("jf1", "jf4"), "Ben's first visit leads with what the household watches")
        assertTrue(service.lastBuiltAt() != null)
    }

    @Test
    fun `a title finished since the build is skipped at request time without a rebuild`() = runBlocking {
        (1..70).forEach { mediaStore.addOrUpdate(film(it)) }
        val a = login("ann")
        histories["ann"] = RecommendationService.History(listOf(played("jf1")), emptyList(), emptySet())
        service.buildAll()
        val live = mediaStore.liveItems()
        val before = service.servedFor(a, live)
        val finished = before.first().jellyfinId!!
        val started = before[1].jellyfinId!!
        PlaystateCache.replaceForTest("ann", mapOf(finished to CardPlayState(played = true), started to CardPlayState(resumeMs = 60_000)))
        val after = service.servedFor(a, live)
        assertFalse(after.any { it.jellyfinId == finished || it.jellyfinId == started })
        assertEquals(before.drop(2).map { it.jellyfinId }, after.map { it.jellyfinId })
        PlaystateCache.replaceForTest("ann", emptyMap())
    }

    /** The row kinds an app installed before this phase knows, decoded strictly the way it decodes. */
    @Serializable private enum class OldRowKind { CONTINUE, NEWLY_ADDED, GENRE, CUSTOM }
    @Serializable private data class OldRow(val kind: OldRowKind)

    @Test
    fun `the config an app reads never says RECOMMENDED anywhere`() {
        val rec = RowConfig(id = "r", kind = RowKind.RECOMMENDED, title = "Recommended for you", limit = 20)
        val cfg = RaviloConfig(
            rows = listOf(rec, RowConfig(id = "c", kind = RowKind.CUSTOM)),
            channels = listOf(ChannelConfig(
                id = "ch", rows = ChannelRowsConfig(mode = "custom", items = listOf(rec)),
                query = ConditionGroup(children = listOf(Condition(facet = "content_row", values = listOf("r"), rows = listOf(rec)))),
            )),
        )
        val wire = Json { encodeDefaults = true }.encodeToString(RaviloConfig.serializer(), cfg.forClients())
        assertFalse("RECOMMENDED" in wire)
        val old = Json { ignoreUnknownKeys = true; isLenient = true }
        val rows = old.parseToJsonElement(wire).jsonObject.getValue("rows").jsonArray
        assertEquals(listOf(OldRowKind.CUSTOM, OldRowKind.CUSTOM), rows.map { old.decodeFromJsonElement(OldRow.serializer(), it).kind })
        assertEquals(RowKind.RECOMMENDED, cfg.rows.first().kind, "the admin's own copy keeps the real kind")
    }
}
