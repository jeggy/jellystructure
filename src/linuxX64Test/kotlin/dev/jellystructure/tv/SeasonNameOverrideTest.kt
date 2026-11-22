package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
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
import dev.jellystructure.server.routes.mediaItemWithEpisodeOrder
import dev.jellystructure.server.routes.seasonNameRoutes
import dev.jellystructure.server.routes.seasonSheet
import dev.jellystructure.tmdb.TmdbClient
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-5321 (FR-1 – FR-5) — the admin's season name: stored, carried through a rescan at both store write choke points,
 * applied by the one naming function to the series detail and the segments sheet, set by the route and read as
 * `season_names`. A stand-in series with Season 1 (named by Jellyfin) and Season 7 (named by the admin).
 */
class SeasonNameOverrideTest {
    private val dir = "/tmp/jellystructure-test-deb5321-${getpid()}"
    private lateinit var db: JellystructureDb
    private lateinit var store: MediaStore
    private lateinit var history: MediaHistory
    private lateinit var detail: DetailService
    private lateinit var segments: MediaSegmentStore
    private val json = Json { ignoreUnknownKeys = true }
    private val danish = "Danish dub 🇩🇰"

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
        history = MediaHistory(db)
        segments = MediaSegmentStore(db)
        detail = DetailService(store, JellyfinClient(), config, ArtworkDownloader(TmdbClient(config), Screengrabber()), segments,
            RaviloDeviceService(db), PlaybackStartSampleStore(db))
    }

    @AfterTest
    fun tearDown() {
        closeLastDatabaseForTests()
        platform.posix.system("rm -rf '$dir'")
    }

    private fun ep(season: Int, n: Int) = Episode(
        filename = "S${season}E$n.mkv", path = "/tv/Club/S${season}E$n.mkv", seasonNumber = season, episodeNumber = n, tracks = emptyList(),
        issueCount = 0, title = "Episode $n", jellyfinId = "j$season-$n", runtime = 30,
    )

    private fun series(
        id: String = "club", kind: MediaKind = MediaKind.TV_SHOW, seasonNames: Map<Int, String> = mapOf(1 to "Original run"),
        overrides: Map<Int, String> = emptyMap(), episodes: List<Episode> = listOf(ep(1, 1), ep(1, 2), ep(7, 1)),
    ) = MediaItem(
        id = id, title = "The Lighthouse Club", year = 2020, kind = kind, path = "/tv/Club", tmdbId = null, originalLanguage = null,
        posterPath = null, overview = null, tracks = emptyList(), issueCount = 0, scannedAt = 0L, episodes = episodes, jellyfinId = "$id-jf",
        seasonNames = seasonNames, seasonNameOverrides = overrides,
    )

    private fun route(block: suspend io.ktor.client.HttpClient.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(json) }
            routing { route("/api/media/{id}") { seasonNameRoutes(store, history) } }
        }
        client.block()
    }

    private suspend fun io.ktor.client.HttpClient.name(id: String, season: Int, body: String) =
        put("/api/media/$id/season-names/$season") { contentType(ContentType.Application.Json); setBody(body) }

    private fun nameBody(n: String) = """{"name":${JsonPrimitive(n)}}"""

    private fun seasonsOf() = runBlocking { detail.getSeriesDetail(device, "club-jf")!!.seasons }

    // ── FR-4, FR-2, FR-3 ────────────────────────────────────────────────────────

    @Test fun `FR-4 — the route stores the name and the detail sends it as name and custom_name`() {
        runBlocking { store.addOrUpdate(series()) }
        route {
            val r = name("club", 7, nameBody(danish))
            assertEquals(HttpStatusCode.OK, r.status)
            val list = json.parseToJsonElement(r.bodyAsText()).jsonArray
            assertEquals(listOf(1, 7), list.map { it.jsonObject["season"]!!.jsonPrimitive.int })
            assertEquals(danish, list[1].jsonObject["name"]!!.jsonPrimitive.content)
            assertEquals("admin", list[1].jsonObject["source"]!!.jsonPrimitive.content)
            assertEquals("jellyfin", list[0].jsonObject["source"]!!.jsonPrimitive.content)
            assertEquals(mapOf(7 to danish), runBlocking { store.get("club")!!.seasonNameOverrides })
            assertTrue(history.forItem("club").any { it.action == "season_name_set" && danish in it.detail && "default" in it.detail })
        }
        val seasons = seasonsOf().associateBy { it.index }
        assertEquals(danish, seasons[7]!!.name); assertEquals(danish, seasons[7]!!.customName)
        assertEquals("Original run", seasons[1]!!.name); assertNull(seasons[1]!!.customName)
    }

    @Test fun `FR-4 — the name is trimmed and a blank one clears`() {
        runBlocking { store.addOrUpdate(series()) }
        route {
            name("club", 7, nameBody("  Dub  "))
            assertEquals("Dub", runBlocking { store.get("club")!!.seasonNameOverrides[7] })
            name("club", 7, nameBody("   "))
            assertTrue(runBlocking { store.get("club")!!.seasonNameOverrides }.isEmpty())
        }
    }

    @Test fun `FR-4 — an empty name brings back the scanned name  or Season N with none`() {
        runBlocking { store.addOrUpdate(series(overrides = mapOf(1 to "Mine", 7 to "Mine too"))) }
        route {
            val after = json.parseToJsonElement(name("club", 1, nameBody("")).bodyAsText()).jsonArray
            assertEquals("Original run", after[0].jsonObject["name"]!!.jsonPrimitive.content)
            assertEquals("jellyfin", after[0].jsonObject["source"]!!.jsonPrimitive.content)
            val seven = json.parseToJsonElement(name("club", 7, nameBody("")).bodyAsText()).jsonArray[1].jsonObject
            assertEquals("Season 7", seven["name"]!!.jsonPrimitive.content); assertEquals("default", seven["source"]!!.jsonPrimitive.content)
        }
        val seasons = seasonsOf().associateBy { it.index }
        assertEquals("Season 7", seasons[7]!!.name); assertNull(seasons[7]!!.customName)
        assertEquals("Original run", seasons[1]!!.name)
    }

    @Test fun `FR-4 — the route refuses a film an absent season 101 characters and a line break`() {
        runBlocking {
            store.addOrUpdate(series(id = "film", kind = MediaKind.MOVIE, episodes = emptyList()))
            store.addOrUpdate(series())
        }
        route {
            assertEquals(HttpStatusCode.BadRequest, name("film", 1, nameBody("x")).status)
            assertEquals(HttpStatusCode.NotFound, name("club", 3, nameBody("x")).status)
            assertEquals(HttpStatusCode.NotFound, name("nope", 7, nameBody("x")).status)
            assertEquals(HttpStatusCode.BadRequest, name("club", 7, nameBody("a".repeat(101))).status)
            assertEquals(HttpStatusCode.OK, name("club", 7, nameBody("a".repeat(100))).status)
            assertEquals(HttpStatusCode.BadRequest, name("club", 7, nameBody("one\ntwo")).status)
            assertEquals(HttpStatusCode.BadRequest, name("club", 7, nameBody("tab\there")).status)
            assertEquals(HttpStatusCode.BadRequest, name("club", 7, """{"nom":"x"}""").status)
            assertEquals(HttpStatusCode.BadRequest, name("club", 7, nameBody("a".repeat(100) + "\uD83C\uDDE9")).status)
        }
        assertTrue(runBlocking { store.get("film")!!.seasonNameOverrides }.isEmpty())
        assertEquals("a".repeat(100), runBlocking { store.get("club")!!.seasonNameOverrides[7] })
    }

    @Test fun `FR-4 — an emoji counts as one character`() {
        runBlocking { store.addOrUpdate(series()) }
        route { assertEquals(HttpStatusCode.OK, name("club", 7, nameBody("a".repeat(99) + "🇩")).status) }
    }

    @Test fun `FR-4 — Specials can be named and an unchanged name writes no history line`() {
        runBlocking { store.addOrUpdate(series(episodes = listOf(ep(0, 1), ep(1, 1)))) }
        route {
            assertEquals(HttpStatusCode.OK, name("club", 0, nameBody("Extras")).status)
            name("club", 0, nameBody("Extras"))
        }
        assertEquals("Extras", seasonsOf().single { it.index == 0 }.customName)
        assertEquals(1, history.forItem("club").count { it.action == "season_name_set" })
    }

    // ── FR-1 ────────────────────────────────────────────────────────────────────

    @Test fun `FR-1 — a rescan through addOrUpdate and a plain updateOne both keep the name`() = runBlocking {
        store.addOrUpdate(series(overrides = mapOf(7 to danish)))
        store.addOrUpdate(series(seasonNames = mapOf(1 to "Renamed by Jellyfin")))   // a scan builds the item with the default
        assertEquals(mapOf(7 to danish), store.get("club")!!.seasonNameOverrides)
        assertEquals("Renamed by Jellyfin", store.get("club")!!.seasonNames[1], "the scanned map is still the scan's")
        store.updateOne(series().copy(title = "Club 2"))
        assertEquals(mapOf(7 to danish), store.get("club")!!.seasonNameOverrides)
        assertEquals("Club 2", store.get("club")!!.title)
        // The route's own write changes it.
        store.updateOne(series(), respectSeasonNameOverrides = false)
        assertTrue(store.get("club")!!.seasonNameOverrides.isEmpty())
    }

    @Test fun `FR-1 — the stored JSON without the field reads as no names`() {
        val old = json.encodeToString(MediaItem.serializer(), series()).replace(Regex(",?\"seasonNameOverrides\":\\{[^}]*\\}"), "")
        assertTrue("seasonNameOverrides" !in old)
        assertTrue(json.decodeFromString(MediaItem.serializer(), old).seasonNameOverrides.isEmpty())
    }

    // ── FR-2 ────────────────────────────────────────────────────────────────────

    @Test fun `FR-2 — the segments sheet reads the admin's name  then the scanned one then Season N`() {
        val item = series(overrides = mapOf(7 to danish), seasonNames = mapOf(1 to "Original run", 7 to "Scanned seven"))
        assertEquals(danish, seasonSheet(item, 7, segments).seasonName)
        assertEquals("Original run", seasonSheet(item, 1, segments).seasonName)
        assertEquals("Season 2", seasonSheet(series(episodes = listOf(ep(2, 1))), 2, segments).seasonName)
        // A blank value counts as absent.
        assertEquals("Original run", seasonSheet(series(overrides = mapOf(1 to "  ")), 1, segments).seasonName)
        assertEquals("Season 1", seasonSheet(series(seasonNames = mapOf(1 to " "), overrides = mapOf(1 to "")), 1, segments).seasonName)
    }

    // ── FR-5 ────────────────────────────────────────────────────────────────────

    @Test fun `FR-5 — GET lists one entry per season with its source ascending for a series only`() {
        runBlocking { store.addOrUpdate(series(overrides = mapOf(7 to danish), episodes = listOf(ep(7, 1), ep(2, 1), ep(1, 1), ep(2, 2)))) }
        val obj = mediaItemWithEpisodeOrder(store, runBlocking { store.get("club")!! }).jsonObject
        val list = obj["season_names"] as JsonArray
        assertEquals(listOf(1, 2, 7), list.map { it.jsonObject["season"]!!.jsonPrimitive.int })
        assertEquals(listOf("jellyfin", "default", "admin"), list.map { it.jsonObject["source"]!!.jsonPrimitive.content })
        assertEquals(listOf("Original run", "Season 2", danish), list.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals("club", obj["id"]!!.jsonPrimitive.content, "the item itself is unchanged")
        val film = mediaItemWithEpisodeOrder(store, series(kind = MediaKind.MOVIE, episodes = emptyList())).jsonObject
        assertTrue("season_names" !in film)
    }
}
