package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinPolicy
import dev.jellystructure.auth.JellyfinUser
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.Screengrabber
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.tmdb.TmdbClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DEB-2675 (DEB-2672, FR-3, FR-7, FR-8) — the background refreshers against a fake Jellyfin over the loopback: a marked user is not
 * asked for, a failed read is a failed refresh that keeps the previous value, and a 404 asks for one check and decides nothing.
 */
class GoneUserRefreshersTest {
    private val run = getpid()
    private lateinit var dbPath: String
    private lateinit var cfgPath: String
    private lateinit var devices: RaviloDeviceService
    private lateinit var mediaStore: MediaStore
    private lateinit var configStore: ConfigStore
    private lateinit var home: HomeFeedService
    private val jellyfin = JellyfinClient()
    private var itemsStatus = HttpStatusCode.OK
    private val asked = ArrayList<String>()   // the userId of every /Items and /Shows/NextUp request
    private lateinit var server: io.ktor.server.engine.EmbeddedServer<*, *>

    /** The reconciler's role, reduced to what the refreshers see. */
    private class FakePresence(val marked: Set<String> = emptySet()) : UserPresence {
        val hints = ArrayList<String>()
        override fun isMarked(userId: String) = userId in marked
        override suspend fun notFoundHint(userId: String) { hints += userId }
    }

    @BeforeTest
    fun setUp() {
        dbPath = "/tmp/jellystructure-test-gone-refreshers-$run.db"
        cfgPath = "/tmp/jellystructure-test-gone-refreshers-$run.toml"
        server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/Users/{id}") { call.respondText("{}", ContentType.Application.Json) }
                get("/Items") { answer(call.request.queryParameters["userId"].orEmpty(), call) }
                get("/Shows/NextUp") { answer(call.request.queryParameters["UserId"].orEmpty(), call) }
            }
        }.start(wait = false)
        val db = createDatabase(dbPath)
        configStore = ConfigStore(cfgPath)
        runBlocking {
            val base = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
            configStore.update(configStore.current.copy(apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = base, jellyfinToken = "server-token-$run")))
        }
        devices = RaviloDeviceService(db)
        mediaStore = MediaStore(db, JsTagStore("/tmp/jellystructure-test-gone-refreshers-$run-tags.json"), configStore)
        val bus = TvEventBus(CoroutineScope(SupervisorJob()))
        home = HomeFeedService(mediaStore, RaviloConfigService(db, bus), jellyfin, configStore, bus, ArtworkDownloader(TmdbClient(configStore), Screengrabber()))
        runBlocking { mediaStore.addOrUpdate(movie("m1")) }
        PlaystateCache.presence = null
    }

    @AfterTest
    fun tearDown() {
        PlaystateCache.presence = null
        server.stop(0, 0)
        for (s in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$s") }
    }

    private suspend fun answer(userId: String, call: io.ktor.server.application.ApplicationCall) {
        asked += userId
        if (itemsStatus == HttpStatusCode.OK) {
            // The bulk playstate read names its ids; every other read (resume, finished, touched, next-up) finds nothing.
            val body = if (call.request.queryParameters["Ids"] != null) """{"Items":[{"Id":"jf-m1","Type":"Movie","UserData":{"Played":true}}],"TotalRecordCount":1}"""
            else """{"Items":[],"TotalRecordCount":0}"""
            call.respondText(body, ContentType.Application.Json)
        } else call.respondText("Error processing request.", status = itemsStatus)
    }

    private fun movie(id: String) = MediaItem(
        id = id, title = id, year = 2020, kind = MediaKind.MOVIE, path = "/movies/$id.mkv", tmdbId = null,
        originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0,
        scannedAt = 0L, episodes = emptyList(), jellyfinId = "jf-$id",
    )

    private fun signIn(user: String): DeviceData {
        val (_, token) = devices.loginDevice(
            deviceId = "tv-$user", deviceName = "TV $user", jellyfinUserId = user, jellyfinUsername = "name-$user",
            jellyfinUserToken = "jf-$user-$run", isAdmin = false, isKids = false, allowedLibraries = null,
        )
        return checkNotNull(devices.validateDeviceToken(token))
    }

    /** A Jellyfin user id: 32 hex digits, unique to this process and to [n] (0-9). */
    private fun id(n: Int) = (run.toString(16) + n.toString(16)).padStart(32, '0')

    private val played = CardPlayState(played = true)

    @Test
    fun `a failed bulk read counts as failed and keeps the previous map`() = runBlocking {
        val d = signIn(id(1))
        PlaystateCache.replaceForTest(d.jellyfinUserId, mapOf("jf-old" to played))
        itemsStatus = HttpStatusCode.InternalServerError
        assertFalse(PlaystateCache.refreshOne(d, mediaStore, jellyfin, configStore), "a 500 is a failed refresh")
        assertEquals(setOf("jf-old"), PlaystateCache.get(d.jellyfinUserId).keys, "R231: the previous value stays")
        itemsStatus = HttpStatusCode.OK
        assertTrue(PlaystateCache.refreshOne(d, mediaStore, jellyfin, configStore))
        assertEquals(setOf("jf-old", "jf-m1"), PlaystateCache.get(d.jellyfinUserId).keys)
    }

    @Test
    fun `a 404 asks for one check and a 500 does not`() = runBlocking {
        val d = signIn(id(2))
        val presence = FakePresence()
        PlaystateCache.presence = presence
        itemsStatus = HttpStatusCode.InternalServerError
        PlaystateCache.refreshOne(d, mediaStore, jellyfin, configStore)
        assertEquals(emptyList(), presence.hints)
        itemsStatus = HttpStatusCode.NotFound
        assertFalse(PlaystateCache.refreshOne(d, mediaStore, jellyfin, configStore))
        assertEquals(listOf(d.jellyfinUserId), presence.hints)
    }

    @Test
    fun `a 404 for a user who is in the users list triggers a pass and neither marks nor skips them`() = runBlocking {
        val d = signIn(id(3))
        var fetches = 0
        val admin = JellyfinUser(id = "ad".padStart(32, '0'), name = "admin", policy = JellyfinPolicy(isAdministrator = true, enableAllFolders = true))
        val me = JellyfinUser(id = d.jellyfinUserId, name = "me", policy = JellyfinPolicy(isAdministrator = false, enableAllFolders = true))
        val reconciler = DevicePolicyReconciler(devices, { fetches++; listOf(admin, me) }, {}, onUserGone = { error("must not remove anyone") })
        PlaystateCache.presence = reconciler
        itemsStatus = HttpStatusCode.NotFound
        repeat(3) { PlaystateCache.refreshOne(d, mediaStore, jellyfin, configStore) }
        assertEquals(1, fetches, "three 404s cost one /Users read")
        assertFalse(reconciler.isMarked(d.jellyfinUserId))
        // …and the refresher does not skip them: the next cycle asks again.
        asked.clear()
        PlaystateCache.refreshAll(devices, mediaStore, jellyfin, configStore, TvEventBus(CoroutineScope(SupervisorJob())))
        assertTrue(asked.isNotEmpty(), "a user who is listed is still refreshed")
    }

    @Test
    fun `the playstate refresh skips a marked user and still serves the others`() = runBlocking {
        val gone = signIn(id(4)); val here = signIn(id(5))
        PlaystateCache.presence = FakePresence(marked = setOf(gone.jellyfinUserId))
        asked.clear()
        PlaystateCache.refreshAll(devices, mediaStore, jellyfin, configStore, TvEventBus(CoroutineScope(SupervisorJob())))
        assertFalse(gone.jellyfinUserId in asked, "no request for the marked user")
        assertTrue(here.jellyfinUserId in asked, "the other user is refreshed")
    }

    @Test
    fun `the summary clause appears only when someone was skipped`() {
        assertEquals("", skippedClause(0))
        assertEquals(" · 3 skipped (not in Jellyfin)", skippedClause(3))
    }

    @Test
    fun `the Continue Watching refresh skips a marked user`() = runBlocking {
        val gone = signIn(id(6)); val here = signIn(id(7))
        home.presence = FakePresence(marked = setOf(gone.jellyfinUserId))
        asked.clear()
        home.refreshAllContinueLists(devices)
        assertFalse(gone.jellyfinUserId in asked)
        assertTrue(here.jellyfinUserId in asked)
        assertTrue(home.continueRefreshAges().containsKey(here.jellyfinUserId))
        assertFalse(home.continueRefreshAges().containsKey(gone.jellyfinUserId), "a skipped user is not refreshed")
    }

    @Test
    fun `a failed read leaves the previous Continue Watching list and a 404 asks for a check`() = runBlocking {
        val d = signIn(id(8))
        val presence = FakePresence()
        home.presence = presence
        home.refreshContinueListFor(d)   // a good build: the list is stored
        val stored = home.continueRefreshAges().getValue(d.jellyfinUserId)
        delay(150)
        itemsStatus = HttpStatusCode.InternalServerError
        home.refreshContinueListFor(d)
        assertTrue(home.continueRefreshAges().getValue(d.jellyfinUserId) >= stored + 100, "a failed build stores nothing")
        assertEquals(emptyList(), presence.hints)
        delay(100)
        itemsStatus = HttpStatusCode.NotFound
        home.refreshContinueListFor(d)
        assertTrue(home.continueRefreshAges().getValue(d.jellyfinUserId) >= 200, "a 404 build stores nothing either")
        assertEquals(listOf(d.jellyfinUserId), presence.hints.distinct())
        itemsStatus = HttpStatusCode.OK
        home.refreshContinueListFor(d)
        assertTrue(home.continueRefreshAges().getValue(d.jellyfinUserId) < 200, "a good build stores again")
    }
}
