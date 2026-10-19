package dev.jellystructure.server.routes

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionData
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.createDatabase
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaJobQueue
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.SubtitleReconciliation
import dev.jellystructure.media.SubtitleReconciliationReport
import dev.jellystructure.torrent.QBittorrentClient
import dev.jellystructure.torrent.SeedingGuard
import dev.jellystructure.torrent.SeedingSnapshot
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
import io.ktor.server.routing.routing
import io.ktor.server.routing.route
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DEB-1906 (FR-260-1) — `POST /api/jobs/empty` with an unknown queue name answers 400 `{error, unknown}`, not a 500.
 * DEB-1906 (FR-200-6) — the subtitle reconciliation report, with its `Divergence`, encodes.
 */
class EmptyQueuesRejectedTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val dir = "/tmp/jellystructure-test-empty-queues-${getpid()}"
    private lateinit var db: JellystructureDb
    private lateinit var queue: MediaJobQueue
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        SystemFileSystem.createDirectories(Path(dir))
        db = createDatabase("$dir/test.db")
        val configStore = ConfigStore("$dir/config.toml")
        scope = CoroutineScope(Dispatchers.Default)
        queue = MediaJobQueue(
            db, MediaStore(db, JsTagStore("$dir/tags.json"), configStore), WsBroadcaster(), JellyfinClient(), configStore, MediaHistory(db),
            SeedingGuard(SeedingSnapshot(configStore, QBittorrentClient())), null, scope,
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dev.jellystructure.db.closeLastDatabaseForTests()
        platform.posix.system("rm -rf '$dir'")
    }

    private fun empty(requestBody: String, check: suspend io.ktor.client.statement.HttpResponse.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(json) }
            intercept(ApplicationCallPipeline.Plugins) {
                call.attributes.put(SessionKey, SessionData("t", "u-root", "root", "jf", Long.MAX_VALUE))
            }
            routing { route("/api") { jobsRoutes(queue) } }
        }
        client.post("/api/jobs/empty") {
            header("Content-Type", "application/json")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }.check()
    }

    @Test fun `an unknown queue name is 400 naming it`() = empty("""{"queues":["nope"]}""") {
        assertEquals(HttpStatusCode.BadRequest, status)
        val body = json.decodeFromString(EmptyQueuesRejected.serializer(), bodyAsText())
        assertEquals(listOf("nope"), body.unknown)
        assertEquals("queues must be a non-empty subset of ${MediaJobQueue.QUEUE_NAMES}", body.error)
    }

    @Test fun `a known and an unknown name is 400 naming only the unknown one`() = empty("""{"queues":["media","nope"]}""") {
        assertEquals(HttpStatusCode.BadRequest, status)
        assertEquals(listOf("nope"), json.decodeFromString(EmptyQueuesRejected.serializer(), bodyAsText()).unknown)
    }

    @Test fun `an empty list is 400 with no unknown names`() = empty("""{"queues":[]}""") {
        assertEquals(HttpStatusCode.BadRequest, status)
        assertEquals(emptyList(), json.decodeFromString(EmptyQueuesRejected.serializer(), bodyAsText()).unknown)
    }

    @Test fun `the reconciliation report encodes its divergences and leaves agrees off the wire`() {
        val d = SubtitleReconciliation.Divergence("jf-1", onlyJellyfin = setOf("fo"), onlyOurs = setOf("de"))
        val encoded = json.encodeToString(SubtitleReconciliationReport.serializer(), SubtitleReconciliationReport(5, 1, listOf(d)))
        assertEquals("""{"scanned":5,"diverged":1,"divergences":[{"jellyfinId":"jf-1","onlyJellyfin":["fo"],"onlyOurs":["de"]}]}""", encoded)
    }
}
