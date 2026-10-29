package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.Screengrabber
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.shared.tv.ChannelConfig
import dev.jellystructure.shared.tv.ChannelRowsConfig
import dev.jellystructure.shared.tv.ChannelSystemRows
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.RaviloWireJson
import dev.jellystructure.shared.tv.RowConfig
import dev.jellystructure.shared.tv.RowKind
import dev.jellystructure.shared.tv.SystemContinue
import dev.jellystructure.shared.tv.SystemNewly
import dev.jellystructure.tmdb.TmdbClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2847 (FR-172-1..FR-172-3) — a collection keeps its own Include. The wire round-trip, then the
 * membership check every collection site shares: a SERIES collection leaves a matching film out of its
 * content and out of the rail's non-empty verdict, a `null` collection still has it, a Movies row inside a
 * Series collection is empty, and a row's See-all seed carries the collection's kind.
 */
class ChannelMediaKindTest {
    private lateinit var dbPath: String
    private lateinit var service: HomeFeedService
    private lateinit var mediaStore: MediaStore
    private lateinit var configService: RaviloConfigService

    private val device = DeviceData(
        deviceId = "dev1", deviceToken = "tok1", jellyfinUserId = "user1", jellyfinUsername = "user1",
        jellyfinUserToken = "jtok1", isAdmin = false,
    )

    @BeforeTest
    fun setUp() {
        val suffix = getpid()
        dbPath = "/tmp/jellystructure-test-channelkind-$suffix.db"
        val db = createDatabase(dbPath)
        val configStore = ConfigStore("/tmp/jellystructure-test-channelkind-$suffix.toml")
        val jsTagStore = JsTagStore("/tmp/jellystructure-test-channelkind-$suffix-tags.json")
        mediaStore = MediaStore(db, jsTagStore, configStore)
        val tvEventBus = TvEventBus(CoroutineScope(SupervisorJob()))
        configService = RaviloConfigService(db, tvEventBus)
        val artwork = ArtworkDownloader(TmdbClient(configStore), Screengrabber())
        service = HomeFeedService(mediaStore, configService, JellyfinClient(), configStore, tvEventBus, artwork)
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) {
            runCatching { platform.posix.remove("$dbPath$suffix") }
        }
    }

    private fun item(id: String, kind: MediaKind) = MediaItem(
        id = id, title = id, year = 2020, kind = kind, path = "/lib/$id", tmdbId = null,
        originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0,
        scannedAt = 0L, episodes = emptyList(), genres = listOf("Action"), jellyfinId = id,
    )

    private fun channel(id: String, mediaKind: String?, rows: List<RowConfig> = emptyList()) = ChannelConfig(
        id = id, filterGenre = "Action", mediaKind = mediaKind,
        rows = ChannelRowsConfig(
            mode = "custom", items = rows,
            system = ChannelSystemRows(cont = SystemContinue(show = false), newly = SystemNewly(show = false)),
        ),
    )

    private fun customRow(id: String, mediaKind: String?) = RowConfig(id = id, kind = RowKind.CUSTOM, title = id, mediaKind = mediaKind)

    @Test
    fun `media_kind round-trips through the config JSON and a missing key reads as null`() {
        val json = RaviloWireJson.encodeToString(ChannelConfig.serializer(), ChannelConfig(id = "c", mediaKind = "SERIES"))
        assertTrue(json.contains("\"media_kind\":\"SERIES\""), json)
        assertEquals("SERIES", RaviloWireJson.decodeFromString(ChannelConfig.serializer(), json).mediaKind)
        assertNull(RaviloWireJson.decodeFromString(ChannelConfig.serializer(), """{"id":"c","name":"x"}""").mediaKind)
    }

    @Test
    fun `an unknown media_kind admits every item like null`() {
        val film = item("f", MediaKind.MOVIE)
        assertTrue(ConditionEvaluator.kindAdmits(film, "WHATEVER"))
        assertTrue(ConditionEvaluator.kindAdmits(film, null))
        assertTrue(ConditionEvaluator.kindAdmits(film, "MOVIE"))
        assertTrue(!ConditionEvaluator.kindAdmits(film, "SERIES"))
        assertTrue(!ConditionEvaluator.kindAdmits(film, "MUSIC_VIDEO"))
    }

    @Test
    fun `a SERIES collection leaves a matching film out of its rail and its content while a null one keeps it`() = runBlocking {
        mediaStore.addOrUpdate(item("film", MediaKind.MOVIE))
        val series = channel("series-only", "SERIES", listOf(customRow("all-rows", null)))
        val any = channel("any-kind", null, listOf(customRow("all-rows", null)))
        configService.save(GLOBAL_USER_ID, RaviloConfig(channels = listOf(series, any)))

        assertEquals(listOf("any-kind"), service.getChannels(device).map { it.id }, "the film is the only title: only the collection without a kind has any content")
        assertTrue(service.getChannelFeed(device, "series-only").rows.all { it.items.isEmpty() })
        assertEquals(listOf("film"), service.getChannelFeed(device, "any-kind").rows.flatMap { it.items }.map { it.id })
    }

    @Test
    fun `a SERIES collection with only a kind and no query admits the series and not the film`() = runBlocking {
        mediaStore.addOrUpdate(item("film", MediaKind.MOVIE))
        mediaStore.addOrUpdate(item("show", MediaKind.TV_SHOW))
        val cfg = ChannelConfig(
            id = "kind-only", mediaKind = "SERIES",
            rows = ChannelRowsConfig(
                mode = "custom", items = listOf(customRow("r", null)),
                system = ChannelSystemRows(cont = SystemContinue(show = false), newly = SystemNewly(show = false)),
            ),
        )
        configService.save(GLOBAL_USER_ID, RaviloConfig(channels = listOf(cfg)))

        val ids = service.getChannelFeed(device, "kind-only").rows.flatMap { it.items }.map { it.id }
        assertTrue("show" in ids, "the series is admitted: $ids")
        assertTrue("film" !in ids, "the film is not: $ids")
    }

    @Test
    fun `a Movies row inside a Series collection is empty and a row with no kind seeds the collection's kind`() = runBlocking {
        mediaStore.addOrUpdate(item("film", MediaKind.MOVIE))
        mediaStore.addOrUpdate(item("show", MediaKind.TV_SHOW))
        val cfg = channel("series-only", "SERIES", listOf(customRow("movies-row", "MOVIE"), customRow("plain-row", null)))
        configService.save(GLOBAL_USER_ID, RaviloConfig(channels = listOf(cfg)))

        val rows = service.getChannelFeed(device, "series-only").rows
        assertTrue(rows.none { it.id == "movies-row" }, "a Movies row in a Series collection is hidden: ${rows.map { it.id }}")
        val plain = rows.single { it.id == "plain-row" }
        assertEquals("SERIES", plain.seedMediaKind)
        assertTrue(plain.items.none { it.id == "film" })
    }
}
