package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceIdentityRegistry
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.GenreCatalog
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.Screengrabber
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.shared.tv.Condition
import dev.jellystructure.shared.tv.ConditionGroup
import dev.jellystructure.shared.tv.QueryJoin
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.RowConfig
import dev.jellystructure.shared.tv.RowKind
import dev.jellystructure.shared.tv.RowOrder
import dev.jellystructure.shared.tv.RowSort
import dev.jellystructure.tmdb.TmdbClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-2842's slice 2 (DEB-2837 FR-6, acceptance 6 and 7) — a split template serves one plain CUSTOM row per
 *  franchise of two or more films, in release order, newest franchise first, each seed returning that franchise alone. */
class HomeFeedSplitRowTest {
    private lateinit var dbPath: String
    private lateinit var mediaStore: MediaStore
    private lateinit var configService: RaviloConfigService
    private lateinit var home: HomeFeedService
    private lateinit var device: dev.jellystructure.auth.DeviceData

    @BeforeTest fun setUp() {
        GenreCatalog.replaceForTest(emptyMap()); DeviceIdentityRegistry.clear()
        dbPath = "/tmp/jellystructure-test-splitrow-${getpid()}.db"
        val db = createDatabase(dbPath)
        val configStore = ConfigStore("/tmp/jellystructure-test-splitrow-${getpid()}.toml")
        mediaStore = MediaStore(db, JsTagStore("/tmp/jellystructure-test-splitrow-${getpid()}-tags.json"), configStore)
        val bus = TvEventBus(CoroutineScope(SupervisorJob()))
        configService = RaviloConfigService(db, bus)
        home = HomeFeedService(mediaStore, configService, JellyfinClient(), configStore, bus, ArtworkDownloader(TmdbClient(configStore), Screengrabber()))
        device = RaviloDeviceService(db).loginDevice(deviceId = "tv", deviceName = "TV", jellyfinUserId = "u1", jellyfinUsername = "u1",
            jellyfinUserToken = "t", isAdmin = false, isKids = false, appVersion = null, platform = "tv").first
    }
    @AfterTest fun tearDown() { for (s in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$s") } }

    private val week = 7L * 24 * 3600
    private val now = 1_800_000_000L

    private fun film(id: String, year: Int, franchise: Int?, name: String?, added: Long, release: String? = null, kind: MediaKind = MediaKind.MOVIE) = MediaItem(
        id = id, title = "Film $id", year = year, kind = kind, path = "/lib/$id.mkv", tmdbId = null,
        originalLanguage = "en", posterPath = null, overview = null, tracks = emptyList(), issueCount = 0, scannedAt = 0L,
        jellyfinId = "jf-$id", createdAt = added, collectionId = franchise, collectionName = name, releaseDate = release,
    )

    private suspend fun library() {
        // Franchise 11 "Alpha": 3 films, the newest added last week. Stored out of release order on purpose.
        mediaStore.addOrUpdate(film("a3", 2005, 11, "Alpha Collection", now - week))
        mediaStore.addOrUpdate(film("a1", 2001, 11, "Alpha Collection", now - 400 * week))
        mediaStore.addOrUpdate(film("a2b", 2003, 11, "Alpha Collection", now - 300 * week, release = "2003-09-01"))
        // Franchise 12 "Beta": 2 films, added last year; same year, so the release date decides.
        mediaStore.addOrUpdate(film("b2", 2010, 12, "Beta - Collection", now - 52 * week, release = "2010-11-20"))
        mediaStore.addOrUpdate(film("b1", 2010, 12, "Beta - Collection", now - 60 * week, release = "2010-02-03"))
        // Franchise 13: one film. No franchise: two films, and a series carrying a collection id.
        mediaStore.addOrUpdate(film("c1", 2012, 13, "Gamma Collection", now - 2 * week))
        mediaStore.addOrUpdate(film("n1", 2015, null, null, now))
        mediaStore.addOrUpdate(film("n2", 2016, null, null, now))
        mediaStore.addOrUpdate(film("s1", 2018, 11, "Alpha Collection", now, kind = MediaKind.TV_SHOW))
    }

    private suspend fun feedRows(template: RowConfig) =
        configService.save(GLOBAL_USER_ID, RaviloConfig(rows = listOf(template))).let { home.getHomeFeed(device).rows.filter { it.id.startsWith(template.id) } }

    @Test
    fun `a split template serves one row per franchise of two or more films and newest franchise first`() = runBlocking {   // acceptance 6
        library()
        // sort and pins are present and must be ignored (FR-6.4)
        val rows = feedRows(RowConfig(id = "sag", kind = RowKind.CUSTOM, title = "Sagas", splitBy = "franchise", sort = RowSort("title", true), pinned = listOf("jf-a3")))
        assertEquals(listOf("sag~franchise-11", "sag~franchise-12"), rows.map { it.id })
        assertEquals(listOf("Alpha", "Beta"), rows.map { it.title }, "titled without Collection")
        assertTrue(rows.all { it.kind == RowKind.CUSTOM }, "an installed app decodes RowKind strictly")
        assertEquals(listOf("jf-a1", "jf-a2b", "jf-a3"), rows[0].items.map { it.id }, "year ascending")
        assertEquals(listOf("jf-b1", "jf-b2"), rows[1].items.map { it.id }, "same year: release date ascending")
        assertEquals(listOf(3, 2), rows.map { it.seedTotalCount })
        assertTrue(rows.all { it.sortBy == "year" && it.sortDescending == false })
    }

    @Test
    fun `each row seed returns that franchise alone and the seed round-trips`() = runBlocking {   // acceptance 6 and 7
        library()
        val rows = feedRows(RowConfig(id = "sag", kind = RowKind.CUSTOM, splitBy = "franchise"))
        val all = mediaStore.allItems()
        for ((row, expected) in rows.zip(listOf(setOf("a1", "a2b", "a3"), setOf("b1", "b2")))) {
            val seed = assertNotNull(row.seedQuery)
            assertEquals(expected, all.filter { ConditionEvaluator.matches(it, seed, emptySet()) }.map { it.id }.toSet())
            val json = kotlinx.serialization.json.Json { encodeDefaults = false }
            assertEquals(seed, json.decodeFromString(ConditionGroup.serializer(), json.encodeToString(ConditionGroup.serializer(), seed)))
        }
    }

    @Test
    fun `the template own filter and include still apply and a limit caps each franchise row`() = runBlocking {
        library()
        val onlyAfter2002 = ConditionGroup(QueryJoin.AND, children = listOf(ConditionGroup(QueryJoin.OR, children = listOf(Condition(facet = "year", op = "between", values = listOf("2002", ""))))))
        val rows = feedRows(RowConfig(id = "sag", kind = RowKind.CUSTOM, splitBy = "franchise", query = onlyAfter2002, limit = 3))
        assertEquals(listOf("sag~franchise-11", "sag~franchise-12"), rows.map { it.id })
        assertEquals(listOf("jf-a2b", "jf-a3"), rows[0].items.map { it.id }, "the 2001 film is outside the template's filter")
        assertEquals(listOf(2, 2), rows.map { it.seedTotalCount })
        assertTrue(feedRows(RowConfig(id = "sag", kind = RowKind.CUSTOM, splitBy = "franchise", mediaKind = "SERIES")).isEmpty(), "no films, no franchise, no row")
    }

    @Test
    fun `a franchise whose name leaves nothing is not served and a template with no franchise serves no row`() = runBlocking {
        mediaStore.addOrUpdate(film("x1", 2001, 20, "Collection", now)); mediaStore.addOrUpdate(film("x2", 2002, 20, "Collection", now))
        assertTrue(feedRows(RowConfig(id = "sag", kind = RowKind.CUSTOM, splitBy = "franchise")).isEmpty())
    }

    @Test
    fun `a row without split_by is served as it is`() = runBlocking {   // acceptance 1
        library()
        val rows = feedRows(RowConfig(id = "plain", kind = RowKind.CUSTOM, title = "Plain"))
        assertEquals(listOf("plain"), rows.map { it.id })
        assertTrue(rows[0].items.size >= 8)
    }

    @Test
    fun `content_row membership of a split row is its template filter`() = runBlocking {
        library()
        val template = RowConfig(id = "sag", kind = RowKind.CUSTOM, splitBy = "franchise", mediaKind = "MOVIE")
        val ref = ConditionGroup(QueryJoin.AND, children = listOf(ConditionGroup(QueryJoin.OR, children = listOf(Condition(facet = "content_row", op = "is_any_of", rows = listOf(template))))))
        val matched = mediaStore.allItems().filter { ConditionEvaluator.matches(it, ref, emptySet()) }.map { it.id }.toSet()
        assertEquals(setOf("a1", "a2b", "a3", "b1", "b2", "c1", "n1", "n2"), matched, "every film, franchise or not; no series")
        assertFalse("s1" in matched); assertNull(template.query)
    }

    @Test
    fun `the validator accepts split_by franchise on a content row and refuses anything else`() {   // FR-8
        assertNull(RowOrder.problem(RowConfig(id = "r", kind = RowKind.CUSTOM, splitBy = "franchise")))
        assertNotNull(RowOrder.problem(RowConfig(id = "r", kind = RowKind.CUSTOM, splitBy = "genre")))
        assertNotNull(RowOrder.problem(RowConfig(id = "r", kind = RowKind.CONTINUE, splitBy = "franchise")))
        assertNotNull(RowOrder.problem(RowConfig(id = "r", kind = RowKind.NEWLY_ADDED, splitBy = "franchise")))
        assertNull(RowOrder.problem(RowConfig(id = "r", kind = RowKind.CUSTOM)))
        assertEquals("One row per franchise", RowOrder.summary(RowConfig(id = "r", splitBy = "franchise", sort = RowSort("title", true), pinned = listOf("x"))), "the sort and hand-picks of a split row are not described")
        assertEquals("One row per franchise · shows 10", RowOrder.summary(RowConfig(id = "r", splitBy = "franchise", limit = 10)))
    }
}
