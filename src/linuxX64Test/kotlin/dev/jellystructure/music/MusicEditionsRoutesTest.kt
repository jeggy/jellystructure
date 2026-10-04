package dev.jellystructure.music

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.MusicPipeline
import dev.jellystructure.media.Screengrabber
import dev.jellystructure.model.MusicCopiesDto
import dev.jellystructure.model.MusicPressingDto
import dev.jellystructure.server.routes.musicRoutes
import dev.jellystructure.tmdb.TmdbClient
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.application.install
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Phase 305 (test 17) — the admin routes' contracts, in-process (`testApplication`), against a fake MusicBrainz. */
class MusicEditionsRoutesTest {
    private val base = "/tmp/jellystructure-test-editions-routes-${getpid()}"
    private val json = Json { ignoreUnknownKeys = true }
    @AfterTest fun tearDown() { listOf("$base.db", "$base.db-wal", "$base.db-shm", "$base.toml").forEach { runCatching { platform.posix.remove(it) } } }

    private class Mb(cfg: ConfigStore) : MusicBrainzClient(cfg) {
        val jp = Pressings.release("kw-rel-jp", Pressings.ELEVEN + "kw-r12", "2006-03-08", country = "JP").copy(releaseGroup = MbReleaseGroup("rg-kw", "Kite Weather"))
        override suspend fun release(mbid: String): MbRelease? = when (mbid) { "kw-rel-jp" -> jp; "sf-rel" -> jp.copy(id = "sf-rel", releaseGroup = MbReleaseGroup("rg-sf")); else -> null }
        override suspend fun releaseGroup(mbid: String): MbReleaseGroup? = Pressings.group()
        override suspend fun releasesOf(releaseGroupMbid: String): List<MbRelease> = (1..7).map { Pressings.release("kw-gb-$it", Pressings.ELEVEN) } + jp
        override suspend fun recording(mbid: String): MbRecording? = null
    }

    private fun app(block: suspend io.ktor.server.testing.ApplicationTestBuilder.(MusicStore) -> Unit) = testApplication {
        runCatching { platform.posix.remove("$base.db") }
        val store = MusicStore(createDatabase("$base.db"))
        runBlocking { store.replaceLibrary(EditionsFixture.library().rows()) }
        val cfg = ConfigStore("$base.toml").also { it.load() }
        val jf = JellyfinClient()
        val pipeline = MusicPipeline(
            scanner = MusicScanner(cfg, jf, store), store = store,
            matcher = MusicMatchService(store, Mb(cfg), AcoustIdClient({ "" }), cfg, { null }),
            media = MusicMediaService(store, ArtworkDownloader(TmdbClient(cfg), Screengrabber()), cfg, jf, FanartTvClient { "" }),
        )
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing { route("/api") { musicRoutes(cfg, pipeline, CoroutineScope(Dispatchers.Default)) } }
        }
        block(store)
    }

    @Test
    fun releases_official_lists_every_pressing_with_against_the_official() = app {
        val r = client.get("/api/music/album/kw/releases?official=1")
        assertEquals(HttpStatusCode.OK, r.status)
        val list = json.decodeFromString(ListSerializer(MusicPressingDto.serializer()), r.bodyAsText())
        assertEquals(8, list.size)
        assertEquals("+1 · −0", list.single { it.option.mbid == "kw-rel-jp" }.against)
        assertTrue(list.all { it.pickable })
    }

    @Test
    fun put_official_rejects_a_release_not_of_this_album() = app {
        val bad = client.put("/api/music/album/kw/official") { contentType(ContentType.Application.Json); setBody("""{"release":"sf-rel"}""") }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        val ok = client.put("/api/music/album/kw/official") { contentType(ContentType.Application.Json); setBody("""{"release":"kw-rel-jp"}""") }
        assertEquals(HttpStatusCode.OK, ok.status)
    }

    @Test
    fun put_home_takes_an_album_of_the_same_artist_or_no_album() = app { store ->
        fun body(id: String?) = if (id == null) """{"album_id":null}""" else """{"album_id":"$id"}"""
        assertEquals(HttpStatusCode.OK, client.put("/api/music/album/s-hr/home") { contentType(ContentType.Application.Json); setBody(body("kw")) }.status)
        assertEquals("kw", store.snapshot().editions.homes["s-hr"]?.albumId)
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/music/album/s-hr/home") { contentType(ContentType.Application.Json); setBody(body("lk-st")) }.status)
        assertEquals(HttpStatusCode.OK, client.put("/api/music/album/s-nl/home") { contentType(ContentType.Application.Json); setBody(body(null)) }.status)
        assertEquals(MusicSingleHome.Home(null, MusicSingleHome.USER), store.snapshot().editions.homes["s-nl"])
    }

    @Test
    fun same_song_rejects_a_equal_b_and_unknown_tracks() = app { store ->
        fun put(b: String) = """{"a":"kw-5","b":"$b","state":"same"}"""
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/music/same-song") { contentType(ContentType.Application.Json); setBody(put("kw-5")) }.status)
        assertEquals(HttpStatusCode.NotFound, client.put("/api/music/same-song") { contentType(ContentType.Application.Json); setBody(put("nope")) }.status)
        assertEquals(HttpStatusCode.OK, client.put("/api/music/same-song") { contentType(ContentType.Application.Json); setBody(put("s-lt-1")) }.status)
        assertEquals(MusicSameSong.SAME, store.snapshot().sameSong["kw-5" to "s-lt-1"])
    }

    @Test
    fun copies_lists_every_copy_with_its_reason_and_shown_in_lists() = app {
        val dto = json.decodeFromString(MusicCopiesDto.serializer(), client.get("/api/music/track/s-nl-1/copies").bodyAsText())
        assertEquals(listOf("kw-2", "s-nl-1", "tt-1"), dto.copies.map { it.id })
        assertTrue(dto.copies.first().shown)
        assertEquals(listOf(null, "recording", "recording"), dto.copies.map { it.reason })
        assertEquals(listOf("album", "single", "compilation"), dto.copies.map { it.kind })
    }
}
