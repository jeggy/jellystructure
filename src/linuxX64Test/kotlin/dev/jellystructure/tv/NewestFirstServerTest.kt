package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.autofetch.AutofetchStore
import dev.jellystructure.autofetch.Fetcher
import dev.jellystructure.autofetch.RetentionSpec
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.closeLastDatabaseForTests
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaSegmentStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.Screengrabber
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.server.routes.episodeOrderRoutes
import dev.jellystructure.server.routes.mediaItemWithEpisodeOrder
import dev.jellystructure.server.routes.playstateAnswer
import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.tmdb.TmdbClient
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2916 (FR-1, FR-2, FR-3, FR-5, FR-7) — the mark, the admin's route, the series detail, Play's pointer and the push's
 * missing next, on a real store. A stand-in daily programme fed by a date-numbered page-only fetcher.
 */
class NewestFirstServerTest {
    private val dir = "/tmp/jellystructure-test-deb2916-${getpid()}"
    private lateinit var db: JellystructureDb
    private lateinit var store: MediaStore
    private lateinit var autofetch: AutofetchStore
    private lateinit var history: MediaHistory
    private lateinit var detail: DetailService
    private val json = Json { ignoreUnknownKeys = true }

    private val device = DeviceData(
        deviceId = "dev1", deviceToken = "tok1", jellyfinUserId = "user1", jellyfinUsername = "user1",
        jellyfinUserToken = "jtok1", isAdmin = false,
    )

    @BeforeTest
    fun setUp() {
        platform.posix.system("mkdir -p '$dir'")
        db = createDatabase("$dir/test.db")
        val config = ConfigStore("$dir/config.toml")
        store = MediaStore(db, JsTagStore("$dir/tags.json"), config)
        autofetch = AutofetchStore(db)
        history = MediaHistory(db)
        detail = DetailService(store, JellyfinClient(), config, ArtworkDownloader(TmdbClient(config), Screengrabber()), MediaSegmentStore(db),
            RaviloDeviceService(db), PlaybackStartSampleStore(db))
    }

    @AfterTest
    fun tearDown() {
        closeLastDatabaseForTests()
        platform.posix.system("rm -rf '$dir'")
    }

    private fun recipe(numbering: String) =
        """{"source":{"url":"https://varpid.example.fo/x"},"list":{"item":".a","fields":{}},"media":{},"numbering":{"kind":"$numbering"}}"""

    /** A fetcher feeding /tv/Daily, with the page-only mark, numbered by [numbering]. */
    private fun feed(path: String, numbering: String, id: String = "f1") {
        autofetch.insert(Fetcher(id, "Daily", "https://varpid.example.fo/x", "varpid.example.fo", null, true, "/tv", path, recipe(numbering),
            false, null, null, RetentionSpec(), "daily@04:00", true, "ok", "", 1, null, null))
        autofetch.mark(path, id)
    }

    private fun ep(n: Int, id: String = "d$n", path: String = "/tv/Daily/S2026E$n.mkv", part: Int = 0, count: Int = 1) = Episode(
        filename = path.substringAfterLast('/'), path = path, seasonNumber = 2026, episodeNumber = n, tracks = emptyList(), issueCount = 0,
        title = "Day $n", jellyfinId = id, partIndex = part, partCount = count, runtime = 30,
    )

    private fun series(id: String = "daily", path: String = "/tv/Daily", episodes: List<Episode> = (1..12).map { ep(290 + it) }, kind: MediaKind = MediaKind.TV_SHOW,
                       newestFirst: Boolean? = null, jf: String = "daily-jf") = MediaItem(
        id = id, title = "Daily", year = 2026, kind = kind, path = path, tmdbId = null, originalLanguage = null, posterPath = null,
        overview = null, tracks = emptyList(), issueCount = 0, scannedAt = 0L, episodes = episodes, jellyfinId = jf, newestFirst = newestFirst,
    )

    // ── FR-1 ────────────────────────────────────────────────────────────────────

    @Test fun `FR-1 — null with a date fetcher is newest first by auto and null without one is off`() = runBlocking {
        feed("/tv/Daily", "date")
        val dated = series(); store.addOrUpdate(dated)
        val plain = series(id = "plain", path = "/tv/Plain", jf = "plain-jf"); store.addOrUpdate(plain)
        assertTrue(store.isNewestFirst(dated)); assertEquals(NewestFirstSource.AUTO, store.newestFirstSource(dated))
        assertFalse(store.isNewestFirst(plain)); assertEquals(NewestFirstSource.OFF, store.newestFirstSource(plain))
    }

    @Test fun `FR-1 — a fetcher numbered by source does not make a series newest first and a trailing slash still matches`() = runBlocking {
        feed("/tv/Daily/", "source")
        assertFalse(store.isNewestFirst(series()))
        autofetch.delete("f1"); feed("/tv/Daily/", "date")
        assertTrue(store.isNewestFirst(series(path = "/tv/Daily")))
        assertTrue(store.isNewestFirst(series(path = "/tv/Daily/")))
    }

    @Test fun `FR-1 — the admin's true and false outrank Auto-fetch and a film is never newest first`() = runBlocking {
        feed("/tv/Daily", "date")
        assertFalse(store.isNewestFirst(series(newestFirst = false))); assertEquals(NewestFirstSource.ADMIN, store.newestFirstSource(series(newestFirst = false)))
        assertTrue(store.isNewestFirst(series(path = "/tv/Plain", newestFirst = true)))
        assertFalse(store.isNewestFirst(series(kind = MediaKind.MOVIE, newestFirst = true)))
    }

    @Test fun `FR-1 — the mark survives a rescan and a plain write in both choke points`() = runBlocking {
        store.addOrUpdate(series(newestFirst = true))
        store.addOrUpdate(series(newestFirst = null))        // a scan builds the item with the default
        assertEquals(true, store.get("daily")!!.newestFirst)
        store.updateOne(series(newestFirst = null).copy(title = "Daily 2"))
        assertEquals(true, store.get("daily")!!.newestFirst)
        assertEquals("Daily 2", store.get("daily")!!.title)
        // An admin's off survives too.
        store.updateOne(series(newestFirst = false), respectNewestFirst = false)
        store.addOrUpdate(series(newestFirst = null))
        assertEquals(false, store.get("daily")!!.newestFirst)
    }

    // ── FR-2 ────────────────────────────────────────────────────────────────────

    @Test fun `FR-2 — GET carries newest_first and its source for a series and nothing extra for a film`() = runBlocking {
        feed("/tv/Daily", "date")
        val obj = mediaItemWithEpisodeOrder(store, series()).jsonObject
        assertEquals(true, obj["newest_first"]!!.jsonPrimitive.boolean)
        assertEquals("auto", obj["newest_first_source"]!!.jsonPrimitive.content)
        assertEquals("daily", obj["id"]!!.jsonPrimitive.content, "the item itself is unchanged")
        assertFalse("newestFirst" in obj, "null stays out of the item's own JSON")
        val off = mediaItemWithEpisodeOrder(store, series(path = "/tv/Plain")).jsonObject
        assertEquals(false, off["newest_first"]!!.jsonPrimitive.boolean); assertEquals("off", off["newest_first_source"]!!.jsonPrimitive.content)
        val film = mediaItemWithEpisodeOrder(store, series(kind = MediaKind.MOVIE)).jsonObject
        assertFalse("newest_first" in film); assertFalse("newest_first_source" in film)
    }

    private fun route(block: suspend io.ktor.client.HttpClient.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(json) }
            routing { route("/api/media/{id}") { episodeOrderRoutes(store, history) } }
        }
        client.block()
    }

    @Test fun `FR-2 — PUT stores the choice writes a history line and answers both fields`() {
        runBlocking { feed("/tv/Daily", "date"); store.addOrUpdate(series()) }
        route {
            val off = put("/api/media/daily/episode-order") { contentType(ContentType.Application.Json); setBody("""{"newest_first":false}""") }
            assertEquals(HttpStatusCode.OK, off.status)
            val body = json.parseToJsonElement(off.bodyAsText()).jsonObject
            assertEquals(false, body["newest_first"]!!.jsonPrimitive.boolean); assertEquals("admin", body["newest_first_source"]!!.jsonPrimitive.content)
            assertEquals(false, runBlocking { store.get("daily")!!.newestFirst })
            assertTrue(history.forItem("daily").any { it.action == "episode_order_set" })
            val on = put("/api/media/daily/episode-order") { contentType(ContentType.Application.Json); setBody("""{"newest_first":true}""") }
            assertEquals(true, json.parseToJsonElement(on.bodyAsText()).jsonObject["newest_first"]!!.jsonPrimitive.boolean)
            // Once chosen, never back to automatic: a rescan keeps it, with the same source.
            runBlocking { store.addOrUpdate(series(newestFirst = null)) }
            assertEquals("admin", mediaItemWithEpisodeOrder(store, runBlocking { store.get("daily")!! }).jsonObject["newest_first_source"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `FR-2 — PUT refuses a film an unknown id and a bad body`() {
        runBlocking { store.addOrUpdate(series(id = "film", kind = MediaKind.MOVIE, episodes = emptyList(), jf = "film-jf")); store.addOrUpdate(series()) }
        route {
            assertEquals(HttpStatusCode.BadRequest, put("/api/media/film/episode-order") { contentType(ContentType.Application.Json); setBody("""{"newest_first":true}""") }.status)
            assertNull(runBlocking { store.get("film")!!.newestFirst })
            assertEquals(HttpStatusCode.NotFound, put("/api/media/nope/episode-order") { contentType(ContentType.Application.Json); setBody("""{"newest_first":true}""") }.status)
            assertEquals(HttpStatusCode.BadRequest, put("/api/media/daily/episode-order") { contentType(ContentType.Application.Json); setBody("""{"newest":1}""") }.status)
            assertNull(runBlocking { store.get("daily")!!.newestFirst })
            assertTrue(history.forItem("daily").none { it.action == "episode_order_set" })
        }
    }

    // ── FR-3 ────────────────────────────────────────────────────────────────────

    @Test fun `FR-3 — a newest-first series says so offers no Shuffle and keeps its ascending wire order`() = runBlocking {
        feed("/tv/Daily", "date")
        store.addOrUpdate(series())
        val d = detail.getSeriesDetail(device, "daily-jf")!!
        assertTrue(d.newestFirst); assertFalse(d.shuffle)
        assertEquals((291..302).toList(), d.seasons.single().episodes.map { it.episodeNumber }, "ascending, unchanged")
    }

    @Test fun `FR-3 — the same series switched to oldest first has Shuffle back`() = runBlocking {
        feed("/tv/Daily", "date")
        store.addOrUpdate(series(newestFirst = false))
        val d = detail.getSeriesDetail(device, "daily-jf")!!
        assertFalse(d.newestFirst); assertTrue(d.shuffle, "12 counted episodes ≥ the Shuffle floor")
    }

    @Test fun `FR-3 — a series that is not marked is as before`() = runBlocking {
        store.addOrUpdate(series())
        val d = detail.getSeriesDetail(device, "daily-jf")!!
        assertFalse(d.newestFirst); assertTrue(d.shuffle)
    }

    // ── FR-5 ────────────────────────────────────────────────────────────────────

    @Test fun `FR-5 — Play's pointer is the newest for a viewer outside Continue and over a Start-over hold`() = runBlocking {
        feed("/tv/Daily", "date")
        store.addOrUpdate(series())
        val newest = store.newestEpisodeIds(listOf("daily-jf", "unknown"))
        assertEquals(mapOf("daily-jf" to "d302"), newest)
        // Not in this viewer's Continue Watching at all:
        val out = playstateAnswer("u", emptyMap(), listOf("daily-jf"), emptyMap(), newestEpisodes = newest)
        assertEquals("d302", out["daily-jf"]!!.continueEpisodeId)
        // In Continue, pointing at an older episode — and a finished series (R375's re-watch): the newest wins both.
        val older = playstateAnswer("u", mapOf("daily-jf" to CardPlayState(played = true)), listOf("daily-jf"), mapOf("daily-jf" to "d295"), newestEpisodes = newest)
        assertEquals("d302", older["daily-jf"]!!.continueEpisodeId); assertTrue(older["daily-jf"]!!.played)
    }

    @Test fun `FR-5 — a series that is not marked keeps the Continue pointer`() = runBlocking {
        store.addOrUpdate(series())
        val newest = store.newestEpisodeIds(listOf("daily-jf"))
        assertTrue(newest.isEmpty())
        assertEquals("d295", playstateAnswer("u", emptyMap(), listOf("daily-jf"), mapOf("daily-jf" to "d295"), newestEpisodes = newest)["daily-jf"]!!.continueEpisodeId)
    }

    @Test fun `FR-5 — a device that may not see the series is told nothing`() = runBlocking {
        feed("/tv/Daily", "date")
        store.addOrUpdate(series().copy(libraryId = "lib-a"))
        val blocked = device.copy(allowedLibraries = setOf("lib-b"))
        assertTrue(store.newestEpisodeIds(listOf("daily-jf"), blocked).isEmpty())
        assertEquals(1, store.newestEpisodeIds(listOf("daily-jf"), device).size)
    }

    // ── FR-7 ────────────────────────────────────────────────────────────────────

    @Test fun `FR-7 — a push for an episode of a newest-first series has no next an unmarked series still has one`() = runBlocking {
        feed("/tv/Daily", "date")
        store.addOrUpdate(series())
        assertNull(assertNotNull(store.resolvePlayPush("d295")).next)
        store.addOrUpdate(series(id = "plain", path = "/tv/Plain", jf = "plain-jf", episodes = (1..3).map { ep(it, id = "p$it", path = "/tv/Plain/S2026E$it.mkv") }))
        assertEquals("p2", store.resolvePlayPush("p1")!!.next!!.jellyfinId)
    }
}
