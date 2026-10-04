package dev.jellystructure.music

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicCredit
import dev.jellystructure.model.MusicTrack
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 305 (test 9) — the real [MusicBrainzClient] against a loopback MusicBrainz answering [MbEditionsFixtures]: the
 * paged pressings (the shipped 25-cap), `release-group-rels`, a recording's `video`. Nothing leaves the machine.
 */
class MusicBrainzEditionsClientTest {
    private val run = getpid().toString()

    private fun <T> withServer(block: suspend (MusicBrainzClient, MutableList<String>) -> T): T = runBlocking {
        val asked = ArrayList<String>()   // one request at a time: the client is paced
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/ws/2/release") {
                    val q = call.request.queryParameters
                    asked += call.request.local.uri
                    val rg = q["release-group"]
                    val offset = q["offset"]?.toIntOrNull() ?: 0
                    val all = when (rg) {
                        "rg-sf", "rg-fail" -> MbEditionsFixtures.SIGNAL_FOUND
                        "rg-kw" -> MbEditionsFixtures.KITE_WEATHER
                        "rg-under" -> MbEditionsFixtures.SIGNAL_FOUND.take(3)
                        else -> emptyList()
                    }
                    if (rg == "rg-fail" && offset > 0) return@get call.respond(HttpStatusCode.InternalServerError)
                    val count = if (rg == "rg-under") 10 else all.size
                    // MusicBrainz answers at most 25 with recordings included, whatever the limit.
                    val page = all.drop(offset).take(25)
                    call.respondText(MbEditionsFixtures.page(count, offset, page), ContentType.Application.Json)
                }
                get("/ws/2/release-group/{id}") {
                    asked += call.request.local.uri
                    val body = when (call.parameters["id"]) {
                        "rg-nl" -> MbEditionsFixtures.NORTHERN_LINE_GROUP
                        "rg-st" -> MbEditionsFixtures.SOUNDTRACK_SINGLE_GROUP
                        "rg-ep" -> MbEditionsFixtures.EP_GROUP
                        else -> return@get call.respond(HttpStatusCode.NotFound)
                    }
                    call.respondText(body, ContentType.Application.Json)
                }
                get("/ws/2/recording/{id}") {
                    asked += call.request.local.uri
                    call.respondText(MbEditionsFixtures.VIDEO_RECORDING, ContentType.Application.Json)
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val cfg = ConfigStore("/tmp/jellystructure-test-mbed-$run.toml").also { it.load() }
        try {
            block(MusicBrainzClient(cfg, FixedRateLimiter(200.0), base = "http://127.0.0.1:$port/ws/2"), asked)
        } finally {
            server.stop(100, 500)
            runCatching { platform.posix.remove("/tmp/jellystructure-test-mbed-$run.toml") }
        }
    }

    @Test
    fun releases_of_pages_past_25_until_release_count() = withServer<Unit> { mb, asked ->
        val r = mb.releasesOf("rg-sf")!!
        assertEquals(30, r.size)
        val pages = asked.filter { it.startsWith("/ws/2/release?") }
        assertEquals(2, pages.size)
        assertTrue(pages.all { "status=official" in it && "inc=recordings+media+labels" in it && "limit=100" in it }, pages.toString())
        assertTrue("offset=0" in pages[0] && "offset=25" in pages[1], pages.toString())
    }

    @Test
    fun an_empty_page_stops_paging() = withServer<Unit> { mb, asked ->
        assertEquals(3, mb.releasesOf("rg-under")!!.size)
        assertEquals(2, asked.count { it.startsWith("/ws/2/release?") }, "one page, then the empty one — no endless loop")
    }

    @Test
    fun a_failed_later_page_answers_null_not_a_short_list() = withServer<Unit> { mb, _ ->
        assertNull(mb.releasesOf("rg-fail"))
    }

    @Test
    fun find_match_lists_the_26th_pressing() = withServer<Unit> { mb, _ ->
        val db = "/tmp/jellystructure-test-mbed-$run.db"
        runCatching { platform.posix.remove(db) }
        val store = MusicStore(createDatabase(db))
        store.replaceLibrary(MusicLibraryRows("lib", emptyList(), listOf(MusicAlbum(id = "sf", libraryId = "lib", title = "Signal Found", albumArtists = listOf(MusicCredit("hl", "Harbour Lights")))),
            (1..14).map { MusicTrack(id = "sf-$it", albumId = "sf", libraryId = "lib", title = "Song sf-r$it", disc = 1, position = it, durationMs = 200_000) }))
        val cfg = ConfigStore("/tmp/jellystructure-test-mbed-$run-2.toml").also { it.load() }
        val options = MusicMatchService(store, mb, AcoustIdClient({ "" }), cfg, { null }).releases("sf", "rg-sf")!!
        assertEquals(30, options.size)
        assertTrue(options.any { it.mbid == "sf-rel-26" })
        listOf(db, "$db-wal", "$db-shm", "/tmp/jellystructure-test-mbed-$run-2.toml").forEach { runCatching { platform.posix.remove(it) } }
    }

    @Test
    fun release_group_asks_for_release_group_rels_and_reads_single_from_forward() = withServer<Unit> { mb, asked ->
        val rg = mb.releaseGroup("rg-nl")!!
        assertTrue(asked.single().contains("release-group-rels"), asked.toString())
        val rel = rg.relations.single()
        assertEquals("single from", rel.type); assertEquals("forward", rel.direction)
        assertEquals("rg-kw", rel.releaseGroup?.id)
        assertTrue(mb.releaseGroup("rg-ep")!!.relations.isEmpty())
    }

    @Test
    fun a_recording_reads_video() = withServer<Unit> { mb, _ ->
        assertTrue(mb.recording("kw-video")!!.video)
    }

    @Test
    fun the_deluxe_fixture_votes_without_its_videos() {
        val r = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(MbRelease.serializer(), MbEditionsFixtures.DELUXE)
        assertEquals(11, MusicOfficial.songsOf(r).size)
    }
}
