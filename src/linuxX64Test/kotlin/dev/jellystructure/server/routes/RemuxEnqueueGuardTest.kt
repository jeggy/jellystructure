package dev.jellystructure.server.routes

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionData
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.LibraryMapping
import dev.jellystructure.config.QBittorrentConfig
import dev.jellystructure.config.QBittorrentPathMapping
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.createDatabase
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaJobQueue
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.torrent.CoverRepairCost
import dev.jellystructure.torrent.QBTorrent
import dev.jellystructure.torrent.QBittorrentClient
import dev.jellystructure.torrent.SeedingGuard
import dev.jellystructure.torrent.SeedingSnapshot
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getpid
import platform.posix.link
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 315 FR-315-6 / FR-315-7 — the remux routes ask the question the queued job asks. A film whose library file has a second
 * name outside the library (cross-seed's link dir) is queued (202); a library path that is a seeding torrent's content
 * path is refused with the guard's sentence (409). The episode twins call the same helper (`RemuxGuard.kt`);
 * `scripts/test-inplace-guard.sh` holds that. The cost read gives N and the summed size for the same shape of file.
 */
@OptIn(ExperimentalForeignApi::class)
class RemuxEnqueueGuardTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val dir = "/tmp/jellystructure-test-remux-guard-${getpid()}"
    private val lib get() = "$dir/library"
    private lateinit var db: JellystructureDb
    private lateinit var store: MediaStore
    private lateinit var configStore: ConfigStore
    private lateinit var queue: MediaJobQueue
    private lateinit var scope: CoroutineScope
    private var seeding = emptyList<QBTorrent>()

    private val fakeQb = object : QBittorrentClient() {
        override suspend fun login(config: QBittorrentConfig): String = ""
        override suspend fun getTorrents(config: QBittorrentConfig, sid: String): List<QBTorrent> = seeding
    }

    private fun write(path: String) { val f = fopen(path, "w")!!; fputs("x", f); fclose(f) }

    @BeforeTest
    fun setUp() {
        platform.posix.system("rm -rf '$dir'")
        listOf(dir, lib, "$lib/Film (2020)", "$dir/cross-seed-links", "$dir/cross-seed-links/Film.2020.Release").forEach {
            SystemFileSystem.createDirectories(Path(it))
        }
        db = createDatabase("$dir/test.db")
        configStore = ConfigStore("$dir/config.toml")
        scope = CoroutineScope(Dispatchers.Default)
        runBlocking {
            configStore.load()
            configStore.update(AppConfig(
                libraries = listOf(LibraryMapping(name = "Film", localPath = "$lib/")),
                qbittorrent = QBittorrentConfig(url = "http://127.0.0.1:1", enabled = true, noAuth = true,
                    pathMappings = listOf(QBittorrentPathMapping(local = lib, remote = "/media/library"))),
            ))
        }
        store = MediaStore(db, JsTagStore("$dir/tags.json"), configStore)
        queue = MediaJobQueue(
            db, store, WsBroadcaster(), JellyfinClient(), configStore, MediaHistory(db),
            SeedingGuard(SeedingSnapshot(configStore, fakeQb)), null, scope,
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dev.jellystructure.db.closeLastDatabaseForTests()
        platform.posix.system("rm -rf '$dir'")
    }

    private val video = Track(0, "v:0", TrackKind.VIDEO, "h264", null, null, true, false)
    private val cover = Track(1, "v:1", TrackKind.VIDEO, "mjpeg", null, null, false, false)
    private val audio = Track(2, "a:0", TrackKind.AUDIO, "aac", "eng", null, true, false)
    private val audio2 = Track(3, "a:1", TrackKind.AUDIO, "aac", "dan", null, false, false)

    /** A film with a cover track; its library file also has the name [outside] when asked. */
    private fun film(id: String, name: String, outside: Boolean): MediaItem {
        val path = "$lib/Film (2020)/$name.mkv"
        write(path)
        if (outside) link(path, "$dir/cross-seed-links/Film.2020.Release/$name.mkv")
        val item = MediaItem(
            id = id, title = "Film", year = 2020, kind = MediaKind.MOVIE, path = path, tmdbId = null,
            originalLanguage = "en", posterPath = null, overview = null, issueCount = 1, scannedAt = 0L,
            tracks = listOf(video, cover, audio, audio2), fileSizeBytes = 1L,
        )
        runBlocking { store.addOrUpdate(item) }
        return item
    }

    private fun call(block: suspend io.ktor.client.HttpClient.() -> HttpResponse, check: suspend HttpResponse.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(json) }
            intercept(ApplicationCallPipeline.Plugins) {
                call.attributes.put(SessionKey, SessionData("t", "u-root", "root", "jf", Long.MAX_VALUE))
            }
            routing {
                route("/api") {
                    val guard = SeedingGuard(SeedingSnapshot(configStore, fakeQb))
                    trackRoutes(store, configStore, JellyfinClient(), MediaHistory(db), guard, null, scope, WsBroadcaster(), queue)
                }
            }
        }
        client.block().check()
    }

    private fun deleteCover(id: String) = call({ delete("/api/media/$id/tracks/v:1") }) { assertEquals(HttpStatusCode.Accepted, status, bodyAsText()) }
    private fun reorder(id: String) = call({
        post("/api/media/$id/tracks/reorder") { contentType(ContentType.Application.Json); setBody("""{"kind":"audio","order":["a:1","a:0"]}""") }
    }) { assertEquals(HttpStatusCode.Accepted, status, bodyAsText()) }

    @Test fun `a hard link to a copy outside the library is queued not refused for a delete and a reorder`() {
        val item = film("film-linked", "Film (2020)", outside = true)
        assertEquals(2L, dev.jellystructure.torrent.linkInfo(item.path)!!.links)
        deleteCover(item.id)
        reorder(item.id)
    }

    @Test fun `a library path that is a seeding torrent's content path is refused with the guard's sentence`() {
        val item = film("film-seeded", "Film (2020)", outside = false)
        seeding = listOf(QBTorrent(hash = "h", name = "Film.2020.Release", state = "uploading", savePath = "/media/library/Film (2020)",
            contentPath = "/media/library/Film (2020)/Film (2020).mkv"))
        call({ delete("/api/media/${item.id}/tracks/v:1") }) {
            assertEquals(HttpStatusCode.Conflict, status)
            assertTrue(bodyAsText().contains("File is seeded by 'Film.2020.Release'"), bodyAsText())
        }
        call({ post("/api/media/${item.id}/tracks/reorder") { contentType(ContentType.Application.Json); setBody("""{"kind":"audio","order":["a:1","a:0"]}""") } }) {
            assertEquals(HttpStatusCode.Conflict, status)
            assertTrue(bodyAsText().contains("File is seeded by 'Film.2020.Release'"), bodyAsText())
        }
    }

    @Test fun `the cost read counts a film with a name outside the library and its size and nothing for a single-name film`() {
        val shared = film("film-shared", "Film (2020)", outside = true)
        call({ get("/api/media/${shared.id}/cover-repair-cost") }) {
            assertEquals(HttpStatusCode.OK, status)
            // The server leaves a field at its default out of the JSON; the admin's class has the same defaults.
            val c = json.decodeFromString(CoverRepairCost.serializer(), bodyAsText())
            assertEquals(1, c.sharedFiles)
            assertEquals(1L, c.sharedBytes)   // the recorded size (fileSizeBytes = 1) wins over a stat
            assertEquals(0.0, c.sharedGb)
        }
        val single = film("film-single", "Film Single", outside = false)
        call({ get("/api/media/${single.id}/cover-repair-cost") }) {
            assertEquals(CoverRepairCost(), json.decodeFromString(CoverRepairCost.serializer(), bodyAsText()))
        }
    }
}
