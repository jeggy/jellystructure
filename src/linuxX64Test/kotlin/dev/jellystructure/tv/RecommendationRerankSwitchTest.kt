package dev.jellystructure.tv

import dev.jellystructure.ai.AiJobs
import dev.jellystructure.ai.AiRequests
import dev.jellystructure.ai.AiViewers
import dev.jellystructure.auth.DeviceIdentityRegistry
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinPlayItem
import dev.jellystructure.auth.JellyfinUserData
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.GenreCatalog
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DEB-2826 (DEB-2823, FR-1, FR-4.5, FR-4.6) — AI re-rank is switched per viewer: the queue is written only for users switched on, on
 * every path, and a run started by hand asks for no AI unless it was told to. The hook here is [AiJobs.enqueueRerank] itself, so the
 * gate under test is the real one.
 */
class RecommendationRerankSwitchTest {
    private lateinit var dbPath: String
    private lateinit var db: JellystructureDb
    private lateinit var configStore: ConfigStore
    private lateinit var mediaStore: MediaStore
    private lateinit var devices: RaviloDeviceService
    private lateinit var ai: AiJobs
    private val histories = HashMap<String, RecommendationService.History>()

    @BeforeTest
    fun setUp() {
        GenreCatalog.replaceForTest(emptyMap())
        DeviceIdentityRegistry.clear()
        dbPath = "/tmp/jellystructure-test-rerank-switch-${getpid()}.db"
        db = createDatabase(dbPath)
        configStore = ConfigStore("/tmp/jellystructure-test-rerank-switch-${getpid()}.toml")
        mediaStore = MediaStore(db, JsTagStore("/tmp/jellystructure-test-rerank-switch-${getpid()}-tags.json"), configStore)
        devices = RaviloDeviceService(db)
        ai = AiJobs(db, configStore, library = { mediaStore.liveItems() }, client = dev.jellystructure.ai.AnthropicClient(NoSend), clock = { 1_790_000_000L })
        runBlocking {
            configStore.update(configStore.current.copy(ai = dev.jellystructure.config.AiConfig(
                enabled = true, apiKey = "sk-ant-test-0000abcd",
                rerank = dev.jellystructure.config.AiJobConfig(enabled = true, model = "claude-opus-5", effort = "medium", monthlyLimitUsd = 5.0),
            )))
            (1..70).forEach { mediaStore.addOrUpdate(film(it)) }
        }
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    private object NoSend : dev.jellystructure.ai.AnthropicClient.Transport {
        override suspend fun get(url: String, apiKey: String) = error("nothing may be sent")
        override suspend fun post(url: String, apiKey: String, body: String) = error("nothing may be sent")
    }

    /** A service over the shared database; its hook only queues, it never sends. */
    private fun newService() = RecommendationService(db, mediaStore, JellyfinClient(), configStore, devices, historySource = { d -> histories[d.jellyfinUserId] })
        .also { s -> s.afterBuild = { inputs, reason, by -> ai.enqueueRerank(inputs, reason, by); Unit } }

    private fun film(n: Int) = MediaItem(
        id = "m$n", title = "Film $n", year = 2000 + n % 20, kind = MediaKind.MOVIE, path = "/lib/m$n.mkv", tmdbId = 2000 + n,
        originalLanguage = "en", posterPath = null, overview = null, tracks = emptyList(), issueCount = 0, scannedAt = 0L,
        jellyfinId = "jf$n", genres = listOf("G${n % 3}"),
    )

    private fun login(user: String) = devices.loginDevice(
        deviceId = "tv-$user", deviceName = "TV", jellyfinUserId = user, jellyfinUsername = user,
        jellyfinUserToken = "jf-$user", isAdmin = false, isKids = false, appVersion = null, platform = "tv",
    ).first

    private fun people() {
        for (u in listOf("ann", "ben", "qa-1029-pixel", "Test Stream")) {
            login(u)
            histories[u] = RecommendationService.History(listOf(JellyfinPlayItem(id = "jf1", type = "Movie", name = "x", userData = JellyfinUserData(played = true, lastPlayedDate = "2026-09-20T10:00:00.0000000Z"))), emptyList(), emptySet())
        }
    }

    private fun waiting(): List<String> = db.aiQueries.waitingFor(AiRequests.RERANK_JOB).executeAsList().map { it.label }.sorted()

    @Test
    fun `qa- and Test users are off by default and people are on`() {
        val sw = newService().rerankSwitch
        assertTrue(sw.isOn("u1", "Ann"))
        assertTrue(!sw.isOn("u2", "qa-1029-pixel"))
        assertTrue(!sw.isOn("u3", "QA-Bob"))
        assertTrue(!sw.isOn("u4", "Test Stream"))
        assertTrue(sw.isOn("u5", "Tester"))
        sw.set("u2", true, "admin", 1L)
        assertTrue(sw.isOn("u2", "qa-1029-pixel"), "a stored choice beats the name rule")
        assertEquals("U-1".lowercase().replace("-", ""), dev.jellystructure.ai.RerankSwitch.key("U-1"))
    }

    @Test
    fun `the list names its default and shows every user`() {
        people()
        val s = newService()
        s.rerankSwitch.set("ben", false, "Jeggy", 1_790_000_000L)
        val rows = AiViewers.rows(listOf("ann" to "ann", "ben" to "ben", "x1" to "qa-1029-pixel", "x2" to "Nobody"), devices.allDevices(), s.rerankSwitch)
        val byName = rows.associateBy { it.name }
        assertEquals("on by default", byName.getValue("ann").reason)
        assertEquals("off — test account", byName.getValue("qa-1029-pixel").reason)
        assertEquals("off — switched off by Jeggy on 2026-09-21", byName.getValue("ben").reason)
        assertTrue(!byName.getValue("ben").rerank && byName.getValue("ben").explicit)
        assertEquals("never seen on a Ravilo device", byName.getValue("Nobody").lastSeen)
        assertEquals("no AI order yet", byName.getValue("Nobody").aiOrder)
    }

    @Test
    fun `a scheduled build queues only the users switched on`() = runBlocking {
        people()
        val s = newService()
        s.buildAll()
        assertEquals(listOf("ann", "ben"), waiting())
        db.aiQueries.deleteQueuedForJob(AiRequests.RERANK_JOB)
        s.rerankSwitch.set("ben", false, "admin", 1L)
        s.buildAll()
        assertEquals(listOf("ann"), waiting())
    }

    @Test
    fun `a run started by hand asks for no AI unless told to and then only for users switched on`() = runBlocking {
        people()
        val s = newService()
        s.rerankSwitch.set("qa-1029-pixel", true, "admin", 1L)
        s.buildAll(RecommendationService.REASON_RUN_BY_HAND, askAi = false)
        assertEquals(emptyList(), waiting(), "no ai_queue row without the AI ticked")
        assertTrue(s.lastBuiltAt() != null, "the free lists were rebuilt")
        s.buildAll(RecommendationService.REASON_RUN_BY_HAND, askAi = true)
        assertEquals(listOf("ann", "ben", "qa-1029-pixel"), waiting(), "one per user switched on, Test Stream is off by its name")
    }

    @Test
    fun `switching a user off deletes their waiting row and AI order and rebuilds their list without AI`() = runBlocking {
        people()
        val s = newService()
        val ben = devices.allDevices().first { it.jellyfinUserId == "ben" }
        s.buildAll()
        val scope = RecommendationEngine.scopeKey(ben)
        db.aiQueries.putOrder("ben", scope, 0L, "jf5", "because", 1_790_000_000L)
        s.buildAll(RecommendationService.REASON_RUN_BY_HAND, askAi = false)   // the list now follows the AI order
        assertTrue(db.recommendationQueries.forViewer("ben", scope).executeAsList().any { it.source == RecommendationService.SOURCE_AI })
        s.setRerank("ben", false, "admin")
        assertEquals(listOf("ann"), waiting())
        assertTrue(db.aiQueries.orderFor("ben", scope).executeAsList().isEmpty())
        val list = db.recommendationQueries.forViewer("ben", scope).executeAsList()
        assertTrue(list.isNotEmpty() && list.none { it.source == RecommendationService.SOURCE_AI }, "rebuilt in the standard order at once")
        // Switching on queues nothing by itself.
        s.setRerank("ben", true, "admin")
        assertEquals(listOf("ann"), waiting())
    }

    @Test
    fun `Rebuild now rebuilds a user switched off and queues no AI`() = runBlocking {
        people()
        val s = newService()
        s.rerankSwitch.set("ben", false, "admin", 1L)
        val ann = devices.allDevices().first { it.jellyfinUserId == "ann" }
        val ben = devices.allDevices().first { it.jellyfinUserId == "ben" }
        assertTrue(s.rebuildViewer(ben, by = "admin"))
        assertEquals(emptyList(), waiting())
        assertTrue(db.recommendationQueries.forViewer("ben", RecommendationEngine.scopeKey(ben)).executeAsList().isNotEmpty(), "their list is rebuilt")
        assertTrue(s.rebuildViewer(ann, by = "admin"))
        assertEquals(listOf("ann"), waiting())
    }

    @Test
    fun `a first build asked within the day is not asked again even by a fresh service`() = runBlocking {
        people()
        val ann = devices.allDevices().first { it.jellyfinUserId == "ann" }
        assertTrue(newService().rebuildViewer(ann))
        assertEquals(listOf("ann"), waiting())
        db.aiQueries.deleteQueuedForJob(AiRequests.RERANK_JOB)
        // A restart: a new service over the same database does not re-ask.
        assertTrue(newService().rebuildViewer(ann))
        assertEquals(emptyList(), waiting())
        // A day later it does.
        db.aiQueries.putViewer("ann", null, null, null, RecommendationService.nowSec() - 25 * 3_600L)
        assertTrue(newService().rebuildViewer(ann))
        assertEquals(listOf("ann"), waiting())
    }
}
