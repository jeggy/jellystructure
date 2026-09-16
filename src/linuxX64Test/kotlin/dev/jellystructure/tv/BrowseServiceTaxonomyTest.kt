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
import dev.jellystructure.shared.tv.Condition
import dev.jellystructure.shared.tv.ConditionGroup
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 216 — the arithmetic acceptance test (FR-216-7 / R243 FR-R243-6): for every value the wall
 * shows, the grid it opens returns exactly that many titles, for the same profile. Plus the
 * normalisation (FR-216-9), the once-per-title summary (FR-216-3), the TV-only network rule
 * (FR-216-4), the absent-never-zero rule (FR-216-6) and no logoUrl without a logo (FR-216-5).
 */
class BrowseServiceTaxonomyTest {
    private lateinit var dbPath: String
    private lateinit var service: BrowseService
    private lateinit var mediaStore: MediaStore

    private val device = DeviceData(
        deviceId = "dev1", deviceToken = "tok1", jellyfinUserId = "user1", jellyfinUsername = "user1",
        jellyfinUserToken = "jtok1", isAdmin = false,
    )

    @BeforeTest
    fun setUp() {
        val suffix = getpid()
        dbPath = "/tmp/jellystructure-test-taxonomy-$suffix.db"
        val db = createDatabase(dbPath)
        val configStore = ConfigStore("/tmp/jellystructure-test-taxonomy-$suffix.toml")
        val jsTagStore = JsTagStore("/tmp/jellystructure-test-taxonomy-$suffix-tags.json")
        mediaStore = MediaStore(db, jsTagStore, configStore)
        val tvEventBus = TvEventBus(CoroutineScope(SupervisorJob()))
        val configService = RaviloConfigService(db, tvEventBus)
        val artwork = ArtworkDownloader(TmdbClient(configStore), Screengrabber())
        service = BrowseService(mediaStore, JellyfinClient(), configStore, configService, artwork, logoDownloader = null)
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    private fun item(
        id: String, kind: MediaKind = MediaKind.MOVIE,
        studio: String? = null, secondary: List<String> = emptyList(), network: String? = null,
        genres: List<String> = emptyList(),
    ) = MediaItem(
        id = id, title = id, year = 2020, kind = kind, path = "/lib/$id.mkv", tmdbId = null,
        originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0,
        scannedAt = 0L, episodes = emptyList(), jellyfinId = id,
        studio = studio, secondaryStudios = secondary, network = network, genres = genres,
    )

    private suspend fun seed() {
        mediaStore.addOrUpdate(item("m1", studio = "HBO Nordic", genres = listOf("Drama")))
        mediaStore.addOrUpdate(item("m2", studio = "HBO  nordic", genres = listOf("drama", "Comedy")))   // spelling variant
        mediaStore.addOrUpdate(item("m3", studio = "A24", secondary = listOf("HBO Nordic"), genres = listOf("Comedy")))
        mediaStore.addOrUpdate(item("s1", kind = MediaKind.TV_SHOW, network = "Kringvarp", genres = listOf("Drama")))
        mediaStore.addOrUpdate(item("s2", kind = MediaKind.TV_SHOW, network = "kringvarp ", genres = emptyList()))
        mediaStore.addOrUpdate(item("m4", network = "Kringvarp"))   // a FILM carrying a broadcaster: not a network entry (FR-216-4)
    }

    @Test
    fun `spelling variants are one group counted once per title and the grid returns the same count`() = runBlocking {
        seed()
        val f = service.facets(device, null)

        val hbo = f.studios.single { it.name.lowercase() == "hbo nordic" }
        assertEquals(3, hbo.count, "m1 + m2 (variant spelling) + m3 (secondary studio)")
        assertEquals("HBO Nordic", hbo.name, "the most frequent spelling wins")
        assertEquals(listOf("HBO Nordic", "A24"), f.studios.map { it.name }, "count-descending, name as tie-break")

        val kringvarp = f.networks.single()
        assertEquals("Kringvarp", kringvarp.name)
        assertEquals(2, kringvarp.count, "two series; the film with a network is not counted (FR-216-4)")

        assertEquals(mapOf("studios" to 3, "networks" to 2, "genres" to 4, "tags" to 0), f.titles, "distinct titles per kind, never the sum of counts")
        assertEquals(6, f.library)
        assertFalse(f.scoped, "no allow-list, no tag policy ⇒ not scoped")
        assertTrue(f.studios.all { it.logoUrl == null } && f.networks.all { it.logoUrl == null }, "no logo store ⇒ no logoUrl key, never a placeholder")
        assertTrue(f.genres.all { it.logoUrl == null }, "genres never carry a logo")

        // FR-216-7 — the arithmetic test, through both grid paths the client uses.
        for (studio in f.studios) {
            assertEquals(studio.count, service.browse(device, null, studios = listOf(studio.name)).total, "GET /tv/browse?studio=${studio.name}")
            val seeded = service.browseByQuery(device, ConditionGroup(children = listOf(Condition(facet = "studio", op = "is_any_of", values = listOf(studio.name)))), null)
            assertEquals(studio.count, seeded.total, "seeded browse for studio ${studio.name}")
        }
        for (network in f.networks) {
            val seeded = service.browseByQuery(device, ConditionGroup(children = listOf(Condition(facet = "network", op = "is_any_of", values = listOf(network.name)))), "SERIES")
            assertEquals(network.count, seeded.total, "seeded browse (series) for network ${network.name}")
        }
        for (genre in f.genres) {
            val seeded = service.browseByQuery(device, ConditionGroup(children = listOf(Condition(facet = "genre", op = "is_any_of", values = listOf(genre.name)))), null)
            assertEquals(genre.count, seeded.total, "seeded browse for genre ${genre.name}")
        }
        // FR-216-9's two-sided form: either spelling seeds the group's full count.
        assertEquals(3, service.browse(device, null, studios = listOf("hbo  NORDIC")).total)
    }

    @Test
    fun `kind slices come from the same pass and a value with no titles is absent`() = runBlocking {
        seed()
        val movies = service.facets(device, "movie")
        val series = service.facets(device, "series")
        assertTrue(movies.networks.isEmpty(), "the movie slice has no networks (the film's broadcaster is not counted)")
        assertTrue(series.studios.isEmpty(), "no series carries a studio ⇒ absent, not count: 0")
        assertEquals(2, series.networks.single().count)
        assertEquals(4, movies.library, "m1..m4 are films (m4 carries a broadcaster but is still a film)"); assertEquals(2, series.library)
    }

    @Test
    fun `the cache serves repeat calls and a content change recomputes`() = runBlocking {
        seed()
        val before = service.facets(device, null)
        assertEquals(before, service.facets(device, null), "same feedVersion ⇒ same (cached) answer")
        mediaStore.addOrUpdate(item("m9", studio = "A24"))
        val after = service.facets(device, null)
        assertEquals(2, after.studios.single { it.name == "A24" }.count, "a new title bumps feedVersion and the next call recomputes")
        assertNull(after.studios.single { it.name == "A24" }.logoUrl)
    }
}
