package dev.jellystructure.server.routes

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionData
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.closeLastDatabaseForTests
import dev.jellystructure.db.createDatabase
import dev.jellystructure.io.FileIo
import dev.jellystructure.shared.tv.ChannelConfig
import dev.jellystructure.shared.tv.ChannelLogo
import dev.jellystructure.shared.tv.ChannelLogoInUse
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.tv.ChannelLogoStore
import dev.jellystructure.tv.ChannelLogoUsage
import dev.jellystructure.tv.GLOBAL_USER_ID
import dev.jellystructure.tv.LiveTvChannelOverride
import dev.jellystructure.tv.LiveTvService
import dev.jellystructure.tv.LiveTvStore
import dev.jellystructure.tv.RaviloConfigService
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DEB-1788 (FR-1, FR-2, FR-3) — `DELETE /api/tv/admin/channel-logos/{name}` removes a stored logo nothing uses, and
 * refuses with the users' names when a collection (global or per-user layout) or a Live TV channel override holds it.
 * Real store, real config DB, real Live TV store; only the HTTP edge is the test host's.
 */
class ChannelLogoRemoveTest {
    private val base = "/tmp/jellystructure-test-logo-remove-${getpid()}"
    private val json = Json { ignoreUnknownKeys = true }
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

    @AfterTest fun tearDown() {
        closeLastDatabaseForTests()
        runCatching { SystemFileSystem.list(Path("$base/channel-logos")).forEach { SystemFileSystem.delete(it) } }
        listOf("$base/channel-logos", base).forEach { runCatching { SystemFileSystem.delete(Path(it)) } }
        listOf("$base.db", "$base.db-wal", "$base.db-shm", "$base.toml", "$base-livetv.json").forEach { runCatching { platform.posix.remove(it) } }
    }

    private class Harness(val store: ChannelLogoStore, val config: RaviloConfigService, val liveTv: LiveTvStore)

    private fun routes(body: suspend ApplicationTestBuilder.(Harness) -> Unit) = testApplication {
        val db = createDatabase("$base.db")
        val config = RaviloConfigService(db)
        val store = ChannelLogoStore(base)
        val liveTvStore = LiveTvStore("$base-livetv.json")
        val cs = ConfigStore("$base.toml").also { runBlocking { it.load() } }
        val liveTv = LiveTvService(base, liveTvStore, JellyfinClient(), cs)
        val usage = ChannelLogoUsage(config, liveTv, cs, JellyfinClient())
        application {
            install(ContentNegotiation) { json(json) }
            intercept(ApplicationCallPipeline.Plugins) {
                if (call.request.headers["X-Test-Admin"] != null) call.attributes.put(SessionKey, SessionData("t", "u-root", "root", "jf", Long.MAX_VALUE))
            }
            routing { route("/api") { channelLogoAdminRoutes(store, usage) } }
        }
        body(Harness(store, config, liveTvStore))
    }

    private fun Harness.upload(name: String): String = runBlocking { store.save(name, png)!!.url.substringAfterLast('/') }
    private fun Harness.onDisk(file: String) = SystemFileSystem.exists(Path("$base/channel-logos/$file"))
    private fun Harness.layout(userId: String, vararg channels: ChannelConfig) =
        config.save(userId, RaviloConfig(channels = channels.toList()))
    private fun collection(name: String, logo: String?) = ChannelConfig(id = "c-$name", name = name, logoUrl = logo)

    private suspend fun ApplicationTestBuilder.del(name: String, admin: Boolean = true) =
        client.delete("/api/tv/admin/channel-logos/$name") { if (admin) header("X-Test-Admin", "1") }

    private suspend fun ApplicationTestBuilder.list(): List<ChannelLogo> =
        json.decodeFromString(ListSerializer(ChannelLogo.serializer()), client.get("/api/tv/admin/channel-logos") { header("X-Test-Admin", "1") }.bodyAsText())

    @Test fun `an unused logo is removed with 204 and the file is gone`() = routes { h ->
        val f = h.upload("spare.png")
        assertEquals(HttpStatusCode.NoContent, del(f).status)
        assertTrue(!h.onDisk(f))
        assertEquals(emptyList(), list())
    }

    @Test fun `no session is 401 and the file stays`() = routes { h ->
        val f = h.upload("spare.png")
        assertEquals(HttpStatusCode.Unauthorized, del(f, admin = false).status)
        assertTrue(h.onDisk(f))
    }

    @Test fun `an unknown name and a path escape and a tmp file each give 404 and touch nothing`() = routes { h ->
        val f = h.upload("keep.png")
        FileIo.writeBytes(Path("$base/channel-logos/x.png.tmp"), png)
        FileIo.writeBytes(Path("$base/channel-logos/notes.txt"), png)
        for (name in listOf("nope.png", "..%2Fx.png", "..%5Cx.png", "%2E%2E", "x.png.tmp", "notes.txt")) {
            assertEquals(HttpStatusCode.NotFound, del(name).status, name)
        }
        assertTrue(h.onDisk(f)); assertTrue(h.onDisk("x.png.tmp")); assertTrue(h.onDisk("notes.txt"))
    }

    @Test fun `a logo on a global layout collection is refused with 409 naming it`() = routes { h ->
        val f = h.upload("brand.png")
        h.layout(GLOBAL_USER_ID, collection("Collection A", "/api/tv/channel-logos/$f"))
        val r = del(f)
        assertEquals(HttpStatusCode.Conflict, r.status)
        val body = json.decodeFromString(ChannelLogoInUse.serializer(), r.bodyAsText())
        assertEquals(listOf("the collection ‘Collection A’ (everyone's layout)"), body.usedBy)
        assertEquals("In use by the collection ‘Collection A’ (everyone's layout) — pick another logo there first.", body.error)
        assertTrue(h.onDisk(f))
    }

    @Test fun `a logo on a per user layout is refused and the user is named by id when Jellyfin cannot say`() = routes { h ->
        val f = h.upload("brand.png")
        h.layout("u-anna", collection("Mine", "/api/tv/channel-logos/$f?v=2"))
        val r = del(f)
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertEquals(listOf("the collection ‘Mine’ (u-anna's layout)"), json.decodeFromString(ChannelLogoInUse.serializer(), r.bodyAsText()).usedBy)
        assertTrue(h.onDisk(f))
    }

    @Test fun `a Live TV override holds a logo by a relative or an absolute URL and Live TV need not be on`() = routes { h ->
        val rel = h.upload("one.png"); val abs = h.upload("two.png")
        h.liveTv.replaceAll(listOf(
            LiveTvChannelOverride("ch1", number = 3, logoOverrideUrl = "/api/tv/channel-logos/$rel"),
            LiveTvChannelOverride("ch2", number = 4, displayName = "News", logoOverrideUrl = "https://jelly.example.org/api/tv/channel-logos/$abs#x"),
        ))
        val r1 = del(rel)
        assertEquals(HttpStatusCode.Conflict, r1.status)
        assertEquals(listOf("the Live TV channel ‘Channel 3’"), json.decodeFromString(ChannelLogoInUse.serializer(), r1.bodyAsText()).usedBy)
        assertEquals(listOf("the Live TV channel ‘News’"), json.decodeFromString(ChannelLogoInUse.serializer(), del(abs).bodyAsText()).usedBy)
        assertTrue(h.onDisk(rel)); assertTrue(h.onDisk(abs))
    }

    @Test fun `several users make a sentence naming every one`() = routes { h ->
        val f = h.upload("shared.png")
        h.layout(GLOBAL_USER_ID, collection("A", "/api/tv/channel-logos/$f"))
        h.layout("u-bob", collection("B", "/api/tv/channel-logos/$f"))
        h.liveTv.replaceAll(listOf(LiveTvChannelOverride("ch1", number = 1, logoOverrideUrl = "/api/tv/channel-logos/$f")))
        val body = json.decodeFromString(ChannelLogoInUse.serializer(), del(f).bodyAsText())
        assertEquals(3, body.usedBy.size)
        assertTrue(body.error.startsWith("In use by the collection ‘A’ (everyone's layout), ") && " and the Live TV channel ‘Channel 1’ — " in body.error, body.error)
    }

    @Test fun `a logo only a similarly named URL points at is not in use`() = routes { h ->
        val f = h.upload("brand.png")
        h.layout(GLOBAL_USER_ID, collection("Other", "/api/tv/channel-logos/x$f"), collection("Elsewhere", "https://cdn.example.org/$f"))
        assertEquals(HttpStatusCode.NoContent, del(f).status)
    }

    @Test fun `the list fills usedBy on the used logo only`() = routes { h ->
        val used = h.upload("used.png"); val free = h.upload("free.png")
        h.layout(GLOBAL_USER_ID, collection("Collection A", "/api/tv/channel-logos/$used"))
        val byFile = list().associateBy { it.url.substringAfterLast('/') }
        assertEquals(listOf("the collection ‘Collection A’ (everyone's layout)"), byFile.getValue(used).usedBy)
        assertEquals(emptyList(), byFile.getValue(free).usedBy)
    }
}
