package dev.jellystructure.seerr

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.SeerrConfig
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.shared.tv.AcquisitionStatus
import dev.jellystructure.shared.tv.DiscoverConfig
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.SeerrFeed
import dev.jellystructure.tv.RaviloConfigService
import dev.jellystructure.tv.TvEventBus
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
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

/**
 * DEB-2518 (FR-4) — Discover never vouches for a title the viewer cannot see. Against a stand-in Seerr: the library holds
 * three titles, 101 tagged `t`, 102 tagged `u`, and 103 not at all. A device with `allowedTags=[t]` is shown 101 as
 * available, 102 nowhere, and 103 as requestable; a device with no tag list is shown what it was shown before.
 */
class DiscoverTagFilterTest {
    private val suffix = getpid()
    private val dbPath = "/tmp/jellystructure-test-discover-tags-$suffix.db"
    private lateinit var service: SeerrDiscoverService
    private lateinit var configService: RaviloConfigService
    private lateinit var seerrStub: io.ktor.server.engine.EmbeddedServer<*, *>

    private fun movie(id: Int) = """{"id":$id,"mediaType":"movie","title":"Film $id"}"""

    private fun item(id: String, tmdb: Int, tag: String) = MediaItem(
        id = id, title = id, year = 2020, kind = MediaKind.MOVIE, path = "/lib/$id", tmdbId = tmdb,
        originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0,
        scannedAt = 0L, episodes = emptyList(), tags = listOf(tag), jellyfinId = "jf-$id",
    )

    @BeforeTest fun setUp() {
        // The stand-in is started outside runBlocking: an engine started inside it is cancelled when that block returns.
        seerrStub = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/api/v1/search") { call.respondText("""{"results":[${movie(101)},${movie(102)},${movie(103)}]}""", ContentType.Application.Json) }
                get("/api/v1/discover/movies") { call.respondText("""{"results":[${movie(101)},${movie(102)},${movie(103)}]}""", ContentType.Application.Json) }
                get("/api/v1/movie/{id}") { call.respondText("""{"id":${call.parameters["id"]},"title":"Film"}""", ContentType.Application.Json) }
                get("/api/v1/person/{id}/combined_credits") {
                    call.respondText("""{"cast":[${movie(101)},${movie(102)},${movie(103)}],"crew":[]}""", ContentType.Application.Json)
                }
            }
        }.start(wait = false)
        val port = runBlocking { seerrStub.engine.resolvedConnectors().first().port }
        val db = createDatabase(dbPath)
        val configStore = ConfigStore("/tmp/jellystructure-test-discover-tags-$suffix.toml")
        runBlocking { configStore.update(configStore.current.copy(seerr = SeerrConfig(enabled = true, url = "http://127.0.0.1:$port", apiKey = "k"))) }
        val mediaStore = MediaStore(db, JsTagStore("/tmp/jellystructure-test-discover-tags-$suffix-tags.json"), configStore)
        runBlocking {
            mediaStore.addOrUpdate(item("held-t", 101, "t"))
            mediaStore.addOrUpdate(item("held-u", 102, "u"))
        }
        configService = RaviloConfigService(db, TvEventBus(CoroutineScope(SupervisorJob())))
        configService.save("u1", RaviloConfig(discover = DiscoverConfig(enabled = true, feeds = listOf(SeerrFeed(id = "f", name = "Popular")))))
        service = SeerrDiscoverService(configStore, SeerrClient(), configService, mediaStore)
    }

    @AfterTest fun tearDown() {
        runCatching { seerrStub.stop(100, 500) }
        dev.jellystructure.db.closeLastDatabaseForTests()
        for (s in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$s") }
    }

    private fun device(allowed: Set<String> = emptySet()) =
        DeviceData("d", "tok", "u1", "viewer", "", isAdmin = false, allowedTags = allowed, kind = "phone")

    private fun List<dev.jellystructure.shared.tv.DiscoverEntry>.ids() = map { it.entry.tmdbId }

    @Test fun `search drops a held title the device cannot see and keeps the visible one available`() = runBlocking<Unit> {
        val found = service.search("film", device(setOf("t")))
        assertEquals(listOf(101, 103), found.ids())
        assertEquals(AcquisitionStatus.AVAILABLE, found.first { it.entry.tmdbId == 101 }.acquisition.status)
        assertNotNull(found.first { it.entry.tmdbId == 101 }.acquisition.itemId)
        assertEquals(AcquisitionStatus.NOT_REQUESTED, found.first { it.entry.tmdbId == 103 }.acquisition.status)
    }

    @Test fun `a row drops it too`() = runBlocking<Unit> {
        val rows = service.getRequestFeeds("u1", isAdmin = false, device = device(setOf("t"))).rows
        assertEquals(listOf(101, 103), rows.single().entries.ids())
    }

    @Test fun `the person overflow neither lists it as requestable nor lists the held title`() = runBlocking<Unit> {
        assertEquals(listOf(103), service.getPersonOverflow(7, device(setOf("t"))).ids())
    }

    @Test fun `its item route answers nothing while the visible title still answers`() = runBlocking<Unit> {
        assertNull(service.getEntry("movie", 102, "u1", device = device(setOf("t"))))
        assertNotNull(service.getEntry("movie", 101, "u1", device = device(setOf("t"))))
    }

    @Test fun `a device with no tag list sees every title as before`() = runBlocking<Unit> {
        val d = device()
        assertEquals(listOf(101, 102, 103), service.search("film", d).ids())
        assertEquals(listOf(103), service.getPersonOverflow(7, d).ids())
        assertNotNull(service.getEntry("movie", 102, "u1", device = d))
    }
}
