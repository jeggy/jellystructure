package dev.jellystructure.server.routes

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionData
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.createDatabase
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.media.ContainerLanguage
import dev.jellystructure.media.FfprobeRunner
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.LanguageRefusal
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaJobQueue
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Track
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2051 (FR-1) — a language edit on an AVI is refused before anything is written: 422 with `reason`/`container`,
 * no remux, no work folder, the file untouched, the refusal in the title's history. An MKV keeps today's path.
 */
class AviLanguageRefusalTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val dir = "/tmp/jellystructure-test-avi-refusal-${getpid()}"
    private lateinit var db: JellystructureDb
    private lateinit var store: MediaStore
    private lateinit var configStore: ConfigStore
    private lateinit var history: MediaHistory
    private lateinit var queue: MediaJobQueue
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        SystemFileSystem.createDirectories(Path("$dir/Film (2020)"))
        db = createDatabase("$dir/test.db")
        configStore = ConfigStore("$dir/config.toml")
        scope = CoroutineScope(Dispatchers.Default)
        store = MediaStore(db, JsTagStore("$dir/tags.json"), configStore)
        history = MediaHistory(db)
        queue = MediaJobQueue(
            db, store, WsBroadcaster(), JellyfinClient(), configStore, history,
            SeedingGuard(SeedingSnapshot(configStore, QBittorrentClient())), null, scope,
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dev.jellystructure.db.closeLastDatabaseForTests()
        platform.posix.system("rm -rf '$dir'")
    }

    private fun sh(cmd: String) = assertEquals(0, platform.posix.system(cmd), cmd)

    /** A one-second AVI with an untagged audio track, made by ffmpeg, and the film row that points at it. */
    private fun film(ext: String): Pair<MediaItem, List<Track>> {
        val path = "$dir/Film (2020)/Film (2020).$ext"
        sh("ffmpeg -v error -y -f lavfi -i testsrc=size=64x48:rate=10:duration=1 -f lavfi -i sine=duration=1 " +
            "-c:v ${if (ext == "avi") "mpeg4" else "libx264"} -c:a ${if (ext == "avi") "mp3" else "aac"} '$path'")
        val tracks = runBlocking { FfprobeRunner.probe(path) }
        val item = MediaItem(
            id = "film-2020", title = "Film", year = 2020, kind = MediaKind.MOVIE, path = path, tmdbId = null,
            originalLanguage = "en", posterPath = null, overview = null, issueCount = 1, scannedAt = 0L, tracks = tracks,
        )
        runBlocking { store.addOrUpdate(item) }
        return item to tracks
    }

    private fun post(path: String, body: String, check: suspend io.ktor.client.statement.HttpResponse.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(json) }
            intercept(ApplicationCallPipeline.Plugins) {
                call.attributes.put(SessionKey, SessionData("t", "u-root", "root", "jf", Long.MAX_VALUE))
            }
            routing {
                route("/api") {
                    val guard = SeedingGuard(SeedingSnapshot(configStore, QBittorrentClient()))
                    trackRoutes(store, configStore, JellyfinClient(), history, guard, null, scope, WsBroadcaster(), queue)
                    triageRoutes(store, JellyfinClient(), configStore, history, guard, dev.jellystructure.media.MediaSegmentStore(db))
                }
            }
        }
        client.post(path) {
            header("Content-Type", "application/json")
            contentType(ContentType.Application.Json)
            setBody(body)
        }.check()
    }

    private fun exists(path: String) = SystemFileSystem.exists(Path(path))
    private fun sizeOf(path: String): Long = SystemFileSystem.metadataOrNull(Path(path))!!.size

    @Test fun `an AVI language edit answers 422 with the reason and writes nothing`() {
        val (item, tracks) = film("avi")
        val audio = tracks.first { it.kind == dev.jellystructure.model.TrackKind.AUDIO }
        val before = sizeOf(item.path)
        post("/api/media/${item.id}/tracks/language", """{"specifier":"${audio.specifier}","language":"fao"}""") {
            assertEquals(HttpStatusCode.UnprocessableEntity, status)
            val body = json.decodeFromString(LanguageRefusal.serializer(), bodyAsText())
            assertEquals(ContainerLanguage.REFUSAL, body.error)
            assertEquals("container_cannot_store_language", body.reason)
            assertEquals("avi", body.container)
        }
        assertFalse(exists("$dir/Film (2020)/.jellystructure"), "no work folder")
        assertEquals(before, sizeOf(item.path), "the file is untouched")
        val stored = runBlocking { store.resolve(item.id)!! }
        assertNull(stored.tracks.first { it.specifier == audio.specifier }.language, "the track stays Unknown")
        assertTrue(history.forItem(item.id).any { "REFUSED" in it.detail }, "the refusal is in the title's history")
    }

    @Test fun `Triage's assign-language route refuses an AVI the same way`() {
        val (item, tracks) = film("avi")
        val audio = tracks.first { it.kind == dev.jellystructure.model.TrackKind.AUDIO }
        val before = sizeOf(item.path)
        post("/api/triage/${item.id}/tracks/${audio.specifier}/language", """{"language":"fao"}""") {
            assertEquals(HttpStatusCode.UnprocessableEntity, status)
            assertEquals("container_cannot_store_language", json.decodeFromString(LanguageRefusal.serializer(), bodyAsText()).reason)
        }
        assertFalse(exists("$dir/Film (2020)/.jellystructure"), "no work folder")
        assertEquals(before, sizeOf(item.path))
    }

    @Test fun `an MKV language edit is not refused`() {
        val (item, tracks) = film("mkv")
        val audio = tracks.first { it.kind == dev.jellystructure.model.TrackKind.AUDIO }
        post("/api/media/${item.id}/tracks/language", """{"specifier":"${audio.specifier}","language":"fao"}""") {
            assertEquals(HttpStatusCode.OK, status, bodyAsText())
        }
    }

    @Test fun `only the file name's own extension counts`() {
        assertEquals("avi", ContainerLanguage.cannotStore("/m/Film.AVI"))
        assertNull(ContainerLanguage.cannotStore("/m/Film.mkv"))
        assertNull(ContainerLanguage.cannotStore("/m/Film.mp4"))
        assertNull(ContainerLanguage.cannotStore("/m/a.avi/Film.mkv"))
        assertNull(ContainerLanguage.cannotStore("/m/Film"))
    }
}
