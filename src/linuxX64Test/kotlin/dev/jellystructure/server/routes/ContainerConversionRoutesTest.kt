package dev.jellystructure.server.routes

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionData
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.createDatabase
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.media.ContainerConversion
import dev.jellystructure.media.ContainerConverter
import dev.jellystructure.media.ConversionIdentityResult
import dev.jellystructure.media.ConversionPorts
import dev.jellystructure.media.ConversionPreview
import dev.jellystructure.media.FfprobeRunner
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaJobQueue
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.StartConversionResponse
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.torrent.QBittorrentClient
import dev.jellystructure.torrent.SeedingCheckResult
import dev.jellystructure.torrent.SeedingGuard
import dev.jellystructure.torrent.SeedingSnapshot
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-2051 (FR-4, FR-6) — the preview is the server's, and Start asks FR-5 again and never trusts it. */
class ContainerConversionRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val dir = "/tmp/jellystructure-test-convert-routes-${getpid()}"
    private val folder = "$dir/Film (2020)"
    private lateinit var db: JellystructureDb
    private lateinit var store: MediaStore
    private lateinit var configStore: ConfigStore
    private lateinit var history: MediaHistory
    private lateinit var queue: MediaJobQueue
    private lateinit var scope: CoroutineScope

    private class Ports(var seeding: SeedingCheckResult = SeedingCheckResult.Allowed) : ConversionPorts {
        override suspend fun seeding(path: String) = seeding
        override suspend fun jellyfinPath(item: MediaItem): String? = null
        override fun freeBytes(dir: String): Long? = Long.MAX_VALUE
        override suspend fun moveIdentity(item: MediaItem, oldPath: String, newPath: String) = ConversionIdentityResult(true, null, emptyList())
    }

    @BeforeTest fun setUp() {
        SystemFileSystem.createDirectories(Path(folder))
        db = createDatabase("$dir/test.db")
        configStore = ConfigStore("$dir/config.toml")
        scope = CoroutineScope(Dispatchers.Default)
        store = MediaStore(db, JsTagStore("$dir/tags.json"), configStore)
        history = MediaHistory(db)
        queue = MediaJobQueue(db, store, WsBroadcaster(), JellyfinClient(), configStore, history, SeedingGuard(SeedingSnapshot(configStore, QBittorrentClient())), null, scope)
        queue.containerConverter = ContainerConverter(Ports())
    }

    @AfterTest fun tearDown() {
        scope.cancel()
        dev.jellystructure.db.closeLastDatabaseForTests()
        platform.posix.system("rm -rf '$dir'")
    }

    private fun film(ext: String): MediaItem {
        val path = "$folder/Film (2020).$ext"
        val codecs = if (ext == "avi") "-c:v mpeg4 -c:a mp3" else "-c:v libx264 -c:a aac"
        assertEquals(0, platform.posix.system("ffmpeg -v error -y -f lavfi -i testsrc=size=64x48:rate=10:duration=1 -f lavfi -i sine=duration=1 $codecs '$path'"))
        val item = MediaItem(
            id = "film-2020", title = "Film", year = 2020, kind = MediaKind.MOVIE, path = path, tmdbId = null,
            originalLanguage = "en", posterPath = null, overview = null, issueCount = 1, scannedAt = 0L, tracks = runBlocking { FfprobeRunner.probe(path) },
        )
        runBlocking { store.addOrUpdate(item) }
        return item
    }

    private fun app(block: suspend io.ktor.client.HttpClient.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(json) }
            intercept(ApplicationCallPipeline.Plugins) { call.attributes.put(SessionKey, SessionData("t", "u-root", "root", "jf", Long.MAX_VALUE)) }
            routing { route("/api") { containerConversionRoutes(store, queue, history) } }
        }
        client.block()
    }

    private suspend fun io.ktor.client.HttpClient.start(body: String) =
        post("/api/media/film-2020/container-conversion") { header("Content-Type", "application/json"); contentType(ContentType.Application.Json); setBody(body) }

    private val faroese = """{"requested":[{"specifier":"0:a:0","language":"fao"}]}"""

    @Test fun `the preview names the paths the streams and the requested language and says the estimate is unavailable`() {
        val item = film("avi")
        app {
            val r = get("/api/media/${item.id}/container-conversion?track=0:a:0&language=fao")
            assertEquals(HttpStatusCode.OK, r.status)
            val p = json.decodeFromString(ConversionPreview.serializer(), r.bodyAsText())
            assertTrue(p.available)
            assertEquals(item.path, p.sourcePath)
            assertEquals("$folder/Film (2020).mkv", p.destinationPath)
            assertEquals("$folder/.jellystructure/Film (2020).avi", p.keptPath)
            assertEquals(listOf("video" to "mpeg4", "audio" to "mp3"), p.streams.map { it.kind to it.codec })
            assertEquals("fao", p.requested.single().language)
            assertNull(p.estimateSeconds, "no remux-class job has finished, so there is nothing to base an estimate on")
            assertNull(p.block)
            assertNull(p.latest)
        }
    }

    @Test fun `a film that is not an AVI has nothing to offer`() {
        val item = film("mkv")
        app {
            val p = json.decodeFromString(ConversionPreview.serializer(), get("/api/media/${item.id}/container-conversion").bodyAsText())
            assertFalse(p.available)
            assertEquals(HttpStatusCode.UnprocessableEntity, start(faroese).status)
        }
    }

    @Test fun `Start asks FR-5 again and a refusal is a 409 with the reason and no job`() {
        val item = film("avi")
        sh("touch '$folder/Film (2020).mkv'")
        app {
            val r = start(faroese)
            assertEquals(HttpStatusCode.Conflict, r.status)
            val body = json.decodeFromString(StartConversionResponse.serializer(), r.bodyAsText())
            assertEquals(ContainerConversion.NAME_TAKEN, body.block?.reason)
            assertNull(body.jobId)
            assertEquals(0, db.mediaJobQueries.listQueued().executeAsList().size)
            val p = json.decodeFromString(ConversionPreview.serializer(), get("/api/media/${item.id}/container-conversion").bodyAsText())
            assertEquals(ContainerConversion.NAME_TAKEN, p.block?.reason, "the preview shows the same verdict")
        }
    }

    @Test fun `Start queues one job and a second press returns it`() {
        film("avi")
        app {
            val first = start(faroese)
            assertEquals(HttpStatusCode.Accepted, first.status)
            val id = assertNotNull(json.decodeFromString(StartConversionResponse.serializer(), first.bodyAsText()).jobId)
            val again = json.decodeFromString(StartConversionResponse.serializer(), start(faroese).bodyAsText())
            assertEquals(id, again.jobId)
            val rows = db.mediaJobQueries.listQueued().executeAsList().filter { it.type == ContainerConversion.JOB_TYPE }
            assertEquals(1, rows.size)
            assertEquals(1L, rows.single().defer_while_playing, "it waits while a TV plays")
            assertEquals("media", rows.single().lane)
        }
    }

    @Test fun `a conversion with no requested language or an unknown track is a 400`() {
        film("avi")
        app {
            assertEquals(HttpStatusCode.BadRequest, start("""{"requested":[]}""").status)
            assertEquals(HttpStatusCode.BadRequest, start("""{"requested":[{"specifier":"0:a:9","language":"fao"}]}""").status)
            assertEquals(HttpStatusCode.BadRequest, start("""{"requested":[{"specifier":"0:a:0","language":"xx-not-a-language"}]}""").status)
        }
    }

    private fun sh(cmd: String) = assertEquals(0, platform.posix.system(cmd), cmd)
}
