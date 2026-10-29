package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.DeviceIdentityRegistry
import dev.jellystructure.auth.DeviceKey
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.closeLastDatabaseForTests
import dev.jellystructure.db.createDatabase
import dev.jellystructure.server.routes.liveTvRoutes
import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.LiveTvHeartbeatRequest
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2627 (FR-5) — live viewing is reported by jellystructure, from [LiveTvService]'s own table: a heartbeat may carry
 * the stream and the rung, a restart does not lose the row, the admin's list shows it, `isPlaybackActive()` counts it,
 * and the apps' list never sees it. A fake Jellyfin over the loopback, the real service, publisher and route.
 */
class LiveTvHeartbeatTest {
    private val run = getpid().toString()
    private val dbPath = "/tmp/jellystructure-test-livehb-$run.db"
    private val closes = mutableListOf<String>()

    @AfterTest fun tearDown() {
        LiveViewing.anyActive = { false }; LiveViewing.names = { emptyList() }
        closeLastDatabaseForTests()
        for (s in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$s") }
    }

    private fun viewer(user: String = "u1") = DeviceData(
        deviceId = "dev-$user-$run", deviceToken = "dt-$user-$run", jellyfinUserId = user, jellyfinUsername = "Anna",
        jellyfinUserToken = "tok-$user", isAdmin = false, displayName = "Stue TV",
    )

    /** [absolute] = the open's `Path` is the broadcaster's URL (a source delivery); otherwise a path on Jellyfin. */
    private fun harness(absolute: Boolean = true, body: suspend (LiveTvService) -> Unit) = runBlocking {
        DeviceIdentityRegistry.clear()
        closes.clear()
        val path = if (absolute) "https://feed.invalid/live/master.m3u8" else "/live/stream.m3u8"
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/Users/{id}") { call.respondText("{}", ContentType.Application.Json) }
                get("/LiveTv/Channels") {
                    val program = """{"Name":"Evening News","StartDate":"2000-01-01T00:00:00Z","EndDate":"2999-01-01T00:00:00Z"}"""
                    call.respondText("""{"Items":[{"Id":"c1","Name":"News 1","CurrentProgram":$program}]}""", ContentType.Application.Json)
                }
                post("/Items/{id}/PlaybackInfo") {
                    call.respondText("""{"PlaySessionId":"ps1","MediaSources":[{"Id":"ms1","OpenToken":"open1"}]}""", ContentType.Application.Json)
                }
                post("/LiveStreams/Open") {
                    call.respondText("""{"MediaSource":{"Id":"ms1","Path":"$path","LiveStreamId":"ls1"}}""", ContentType.Application.Json)
                }
                post("/LiveStreams/Close") { closes += Regex("\"LiveStreamId\":\"([^\"]+)\"").find(call.receiveText())?.groupValues?.get(1).orEmpty(); call.respond(HttpStatusCode.NoContent) }
            }
        }.start(wait = false)
        val base = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
        val cfgPath = "/tmp/jellystructure-test-livehb-$run.toml"
        val storePath = "/tmp/jellystructure-test-livehb-$run-store.json"
        val configStore = ConfigStore(cfgPath)
        configStore.update(configStore.current.copy(apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = base, jellyfinToken = "server-key")))
        val service = LiveTvService("/tmp/jellystructure-test-livehb-$run", LiveTvStore(storePath), JellyfinClient(), configStore)
        try {
            service.sync(); service.bulkShowNew()
            body(service)
        } finally {
            server.stop(100, 500)
            for (f in listOf(cfgPath, storePath)) platform.posix.remove(f)
            DeviceIdentityRegistry.clear()
        }
    }

    /** A second service over the same Jellyfin: the backend after a restart (an empty table). */
    private fun restarted(old: LiveTvService): LiveTvService = runBlocking {
        val cs = ConfigStore("/tmp/jellystructure-test-livehb-$run.toml").also { it.load() }
        LiveTvService("/tmp/jellystructure-test-livehb-$run", LiveTvStore("/tmp/jellystructure-test-livehb-$run-store.json"), JellyfinClient(), cs)
            .also { it.sync(); it.bulkShowNew() }
    }

    @Test fun `a body-less heartbeat keeps the stream and changes nothing`() = harness { service ->
        val tv = viewer()
        service.tune(tv, "c1", ClientCapabilities())
        var told = 0
        service.onLiveChanged = { told++ }
        service.heartbeat(tv)
        service.heartbeat(tv, null)
        assertTrue(service.anyLiveActive())
        assertEquals(0, told, "nothing on the row changed, so the admin is not re-sent")
        assertEquals(1, service.adminRows().size)
    }

    @Test fun `a heartbeat with the stream after a restart re-registers it and the watchdog leaves it open`() = harness { before ->
        val tv = viewer()
        before.tune(tv, "c1", ClientCapabilities())
        val after = restarted(before)
        assertTrue(!after.anyLiveActive(), "the restarted backend holds nothing")
        after.heartbeat(tv, LiveTvHeartbeatRequest(channelId = "c1", liveStreamId = "ls1", variantBandwidthBps = 7_700_000, height = 1080, delivery = "source"))
        assertTrue(after.anyLiveActive())
        after.stopWatchdogTick { true }
        assertTrue(after.anyLiveActive(), "a fresh heartbeat is not stale")
        assertTrue(closes.isEmpty(), "Jellyfin was never told to close it")
        val row = after.adminRows().single()
        assertEquals(1080, row.variantHeight); assertEquals(7_700_000L, row.variantBps); assertEquals("source", row.encoder)
    }

    @Test fun `a heartbeat that names no stream or a channel the viewer cannot watch registers nothing`() = harness { service ->
        val tv = viewer()
        service.heartbeat(tv, LiveTvHeartbeatRequest(height = 1080))
        service.heartbeat(tv, LiveTvHeartbeatRequest(channelId = "c1"))
        service.heartbeat(tv, LiveTvHeartbeatRequest(channelId = "nope", liveStreamId = "ls9"))
        assertTrue(!service.anyLiveActive())
    }

    @Test fun `a heartbeat for a stream the device no longer holds keeps nothing alive`() = harness { service ->
        val tv = viewer()
        service.tune(tv, "c1", ClientCapabilities())
        service.heartbeat(tv, LiveTvHeartbeatRequest(channelId = "c1", liveStreamId = "old", height = 480))
        assertNull(service.adminRows().single().variantHeight, "the old tune's rung is not on the new row")
    }

    @Test fun `isPlaybackActive is true while a live stream is open and false after the stop`() = harness { service ->
        LiveViewing.anyActive = { service.anyLiveActive() }; LiveViewing.names = { service.liveDeviceNames() }
        val tv = viewer()
        val film = playbackTracker.anyActive()   // whatever other tests left in the film tracker; live adds nothing to it
        service.tune(tv, "c1", ClientCapabilities())
        assertTrue(isPlaybackActive())
        assertTrue("Stue TV" in activePlaybackDeviceNames())
        service.stopTune(tv, "ls1")
        assertEquals(film, isPlaybackActive())
        assertTrue(!service.anyLiveActive())
        assertEquals(listOf("ls1"), closes)
    }

    @Test fun `the watchdog closing a silent stream removes its row and tells the admin`() = harness { service ->
        val tv = viewer()
        service.tune(tv, "c1", ClientCapabilities())
        var told = 0
        service.onLiveChanged = { told++ }
        service.stopWatchdogTick { false }   // the device's events socket is gone
        assertTrue(!service.anyLiveActive())
        assertTrue(told > 0)
        assertEquals(listOf("ls1"), closes)
    }

    @Test fun `the admin list carries the live row with channel programme delivery and rung and the apps' list does not`() = harness { service ->
        val db = createDatabase(dbPath)
        val devices = RaviloDeviceService(db)
        val sessions = PlaybackSessions(db)
        val publisher = SessionPublisher(sessions, devices, bus = null, canSee = { _, _, _, _ -> true })
        publisher.liveRowsOf = { service.adminRows() }
        val tv = viewer()
        service.tune(tv, "c1", ClientCapabilities())
        service.heartbeat(tv, LiveTvHeartbeatRequest(channelId = "c1", liveStreamId = "ls1", variantBandwidthBps = 7_700_000, height = 1080, delivery = "source"))

        val row = assertNotNull(publisher.adminList().sessions.singleOrNull { it.kind == "live" })
        assertEquals("Anna", row.ownerName)
        assertEquals("News 1", row.title)
        assertEquals("Evening News", row.subtitle)
        assertEquals("Stue TV", row.targetName)
        assertEquals("source", row.encoder)
        assertEquals(1080, row.variantHeight)
        assertEquals(7_700_000L, row.variantBps)
        assertTrue(row.createdAt > 0 && row.endedAt == null)

        val apps = publisher.listFor(tv)
        assertTrue(apps.sessions.none { it.id.startsWith("live:") || it.kind == "live" }, "the apps never see a live kind")
        assertTrue(apps.sessions.isEmpty())

        service.stopTune(tv, "ls1")
        assertTrue(publisher.adminList().sessions.none { it.kind == "live" }, "the row goes on stop")
    }

    @Test fun `a stream opened from a path on Jellyfin reports jellyfin delivery`() = harness(absolute = false) { service ->
        service.tune(viewer(), "c1", ClientCapabilities())
        assertEquals("jellyfin", service.adminRows().single().encoder)
    }

    @Test fun `the route takes no body and a body and a body it cannot read`() = harness { service ->
        val tv = viewer()
        service.tune(tv, "c1", ClientCapabilities())
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                intercept(ApplicationCallPipeline.Plugins) { call.attributes.put(DeviceKey, tv) }
                routing { route("/api") { liveTvRoutes(service) } }
            }
            assertEquals(HttpStatusCode.OK, client.post("/api/tv/livetv/heartbeat").status)
            val withBody = client.post("/api/tv/livetv/heartbeat") {
                contentType(ContentType.Application.Json)
                setBody("""{"channel_id":"c1","live_stream_id":"ls1","variant_bandwidth_bps":4500000,"height":720,"delivery":"source","from_the_future":1}""")
            }
            assertEquals(HttpStatusCode.OK, withBody.status)
            assertEquals(720, service.adminRows().single().variantHeight)
            val junk = client.post("/api/tv/livetv/heartbeat") { contentType(ContentType.Application.Json); setBody("not json") }
            assertEquals(HttpStatusCode.OK, junk.status, "an unreadable body is a body-less heartbeat")
            assertEquals(720, service.adminRows().single().variantHeight)
        }
    }
}
