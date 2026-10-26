package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.DeviceIdentityRegistry
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.shared.tv.ClientCapabilities
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2520 (restores R177 *restricted viewers see only permitted channels*, and the 2026-08-02 review's H2: a device is
 * never handed the server's Jellyfin key). A fake Jellyfin over the loopback: `/Users/{id}` is the paired-token check,
 * `/LiveTv/Channels?userId=` lists per user, the tuner routes answer. The real [LiveTvService] and token check.
 */
class LiveTvTuneTokenTest {

    private class FakeJellyfin(val serverKey: String) {
        /** user id → channel ids Jellyfin lists for that account. */
        val channelsByUser = mutableMapOf<String, List<String>>()
        /** token → what `/Users/{id}` answers for it. */
        val userStatus = mutableMapOf<String, Int>()
        val listCalls = mutableListOf<String>()      // "<userId> <token>" per /LiveTv/Channels?userId=
        val tuneTokens = mutableListOf<String>()     // the token each tuner call carried
    }

    private fun tokenOf(authorization: String?): String? =
        Regex("Token=\"([^\"]+)\"").find(authorization.orEmpty())?.groupValues?.get(1)

    private fun device(run: String, user: String, token: String, tags: Set<String> = emptySet()) = DeviceData(
        deviceId = "dev-$user-$run", deviceToken = "dt-$user-$run", jellyfinUserId = user, jellyfinUsername = user,
        jellyfinUserToken = token, isAdmin = false, allowedTags = tags,
    )

    private fun harness(body: suspend (FakeJellyfin, LiveTvService, String) -> Unit) = runBlocking {
        DeviceIdentityRegistry.clear()
        val run = getpid().toString()
        val fake = FakeJellyfin("server-key-$run")
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/Users/{id}") {
                    val status = fake.userStatus[tokenOf(call.request.headers["Authorization"])] ?: 401
                    if (status in 200..299) call.respondText("{}", ContentType.Application.Json) else call.respond(HttpStatusCode.fromValue(status))
                }
                get("/LiveTv/Channels") {
                    val user = call.request.queryParameters["userId"]
                    fake.listCalls += "$user ${tokenOf(call.request.headers["Authorization"])}"
                    val ids = if (user == null) fake.channelsByUser.values.flatten().distinct() else fake.channelsByUser[user].orEmpty()
                    val items = ids.joinToString(",") { """{"Id":"$it","Name":"Channel $it"}""" }
                    call.respondText("""{"Items":[$items]}""", ContentType.Application.Json)
                }
                post("/Items/{id}/PlaybackInfo") {
                    fake.tuneTokens += tokenOf(call.request.headers["Authorization"]).orEmpty()
                    call.respondText("""{"PlaySessionId":"ps1","MediaSources":[{"Id":"ms1","OpenToken":"open1"}]}""", ContentType.Application.Json)
                }
                post("/LiveStreams/Open") {
                    fake.tuneTokens += tokenOf(call.request.headers["Authorization"]).orEmpty()
                    call.respondText("""{"MediaSource":{"Id":"ms1","Path":"/live/stream.m3u8","LiveStreamId":"ls1"}}""", ContentType.Application.Json)
                }
            }
        }.start(wait = false)
        val base = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
        val cfgPath = "/tmp/jellystructure-test-livetv-tune-$run.toml"
        val storePath = "/tmp/jellystructure-test-livetv-tune-$run-store.json"
        val configStore = ConfigStore(cfgPath)
        configStore.update(configStore.current.copy(apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = base, jellyfinToken = fake.serverKey)))
        val service = LiveTvService("/tmp/jellystructure-test-livetv-tune-$run", LiveTvStore(storePath), JellyfinClient(), configStore)
        try {
            body(fake, service, run)
        } finally {
            server.stop(100, 500)
            for (f in listOf(cfgPath, storePath)) platform.posix.remove(f)
            runCatching { kotlinx.io.files.SystemFileSystem.delete(kotlinx.io.files.Path("/tmp/jellystructure-test-livetv-tune-$run/artwork/livetv-logos")) }
            DeviceIdentityRegistry.clear()
        }
    }

    @Test
    fun `a tune with a valid paired token hands the device its own token and never the server key`() = harness { fake, service, run ->
        val tok = "viewer-valid-$run"
        fake.userStatus[tok] = 200
        fake.channelsByUser["u1"] = listOf("c1")
        val ticket = assertNotNull(service.tune(device(run, "u1", tok), "c1", ClientCapabilities()))
        assertEquals(tok, ticket.accessToken)
        assertEquals(listOf(tok, tok), fake.tuneTokens, "Jellyfin was asked as the viewer, not as the server")
    }

    @Test
    fun `a tune with an unknown token check hands the device its own token and never the server key`() = harness { fake, service, run ->
        val tok = "viewer-unknown-$run"
        fake.userStatus[tok] = 503   // Jellyfin declined to answer: UNKNOWN
        fake.channelsByUser["u1"] = listOf("c1")
        val ticket = assertNotNull(service.tune(device(run, "u1", tok), "c1", ClientCapabilities()))
        assertEquals(tok, ticket.accessToken)
        assertTrue(fake.serverKey != ticket.accessToken)
        assertTrue(fake.serverKey !in fake.tuneTokens)
    }

    @Test
    fun `a tune with a rejected token is the re-pair error and the server key goes nowhere`() = harness { fake, service, run ->
        val tok = "viewer-rejected-$run"
        fake.userStatus[tok] = 401
        fake.channelsByUser["u1"] = listOf("c1")
        assertFailsWith<JellyfinReauthRequiredException> { service.tune(device(run, "u1", tok), "c1", ClientCapabilities()) }
        assertTrue(fake.tuneTokens.isEmpty(), "no tuner call was made with any token")
    }

    @Test
    fun `a channel outside the viewer's Jellyfin list is absent and cannot be tuned`() = harness { fake, service, run ->
        val tok = "viewer-filtered-$run"
        fake.userStatus[tok] = 200
        fake.channelsByUser["admin"] = listOf("c1", "c2")
        fake.channelsByUser["u1"] = listOf("c2")
        val viewer = device(run, "u1", tok)
        assertEquals(setOf("c2"), service.visibleChannelIds(viewer))
        assertNull(service.tune(viewer, "c1", ClientCapabilities()), "c1 is not in u1's list")
        assertTrue(fake.tuneTokens.isEmpty(), "Jellyfin was never asked to open it")
        assertNotNull(service.tune(viewer, "c2", ClientCapabilities()))
        // The list is fetched with the server key and the viewer's id — Jellyfin's policy decides.
        assertEquals(listOf("u1 ${fake.serverKey}"), fake.listCalls.distinct())
    }

    @Test
    fun `the channel list is cut to the viewer's channels while the admin lineup keeps all of them`() = harness { fake, service, run ->
        fake.channelsByUser["admin"] = listOf("c1", "c2")
        fake.channelsByUser["u1"] = listOf("c2")
        service.sync()
        service.bulkShowNew()
        val viewer = device(run, "u1", "viewer-cut-$run")
        assertEquals(listOf("c2"), service.homeChannels(viewer).map { it.channelId })
        assertEquals(setOf("c1", "c2"), service.lineup().map { it.channelId }.toSet())
        assertEquals(setOf("c1", "c2"), service.homeChannels(device(run, "admin", "admin-tok-$run")).map { it.channelId }.toSet())
    }

    @Test
    fun `an account that Jellyfin lists no channels for sees none`() = harness { fake, service, run ->
        fake.channelsByUser["admin"] = listOf("c1", "c2", "c3", "c4", "c5")
        fake.channelsByUser["u2"] = emptyList()
        val viewer = device(run, "u2", "viewer-none-$run", tags = setOf("household"))
        assertEquals(emptySet(), service.visibleChannelIds(viewer))
        assertEquals(emptyList(), service.homeChannels(viewer))
        assertEquals(emptyList(), service.guide(viewer))
    }

    @Test
    fun `the list is cached per user and a change in the device's tag lists asks Jellyfin again`() = harness { fake, service, run ->
        fake.channelsByUser["u3"] = listOf("c1")
        val before = device(run, "u3", "viewer-cache-$run", tags = setOf("a"))
        service.visibleChannelIds(before); service.visibleChannelIds(before)
        assertEquals(1, fake.listCalls.size, "the second read is served from the cache")
        fake.channelsByUser["u3"] = emptyList()
        assertEquals(emptySet(), service.visibleChannelIds(before.copy(allowedTags = setOf("b"))), "a new policy hash misses the cache")
        assertEquals(2, fake.listCalls.size)
    }

    @Test
    fun `when Jellyfin cannot be asked the viewer has no channels and the failure is not cached`() = harness { fake, service, run ->
        val viewer = device(run, "u4", "viewer-down-$run")
        // No /LiveTv/Channels answer for u4 exists at the fake yet, so point the service at a closed port.
        val cs = ConfigStore("/tmp/jellystructure-test-livetv-down-$run.toml")
        cs.update(cs.current.copy(apiKeys = cs.current.apiKeys.copy(jellyfinUrl = "http://127.0.0.1:1", jellyfinToken = fake.serverKey)))
        val down = LiveTvService("/tmp/jellystructure-test-livetv-tune-$run", LiveTvStore("/tmp/jellystructure-test-livetv-down-$run.json"), JellyfinClient(), cs)
        try {
            assertEquals(emptySet(), down.visibleChannelIds(viewer))
            assertNull(down.tune(viewer, "c1", ClientCapabilities()))
        } finally {
            for (f in listOf("/tmp/jellystructure-test-livetv-down-$run.toml", "/tmp/jellystructure-test-livetv-down-$run.json")) platform.posix.remove(f)
        }
    }
}
