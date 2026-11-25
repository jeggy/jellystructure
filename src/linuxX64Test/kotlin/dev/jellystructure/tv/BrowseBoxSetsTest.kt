package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.FakeJellyfinCollections
import dev.jellystructure.media.JellyfinCollections
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.Screengrabber
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.shared.tv.BoxSetFacet
import dev.jellystructure.shared.tv.Condition
import dev.jellystructure.shared.tv.ConditionGroup
import dev.jellystructure.shared.tv.SeededBrowseResponse
import dev.jellystructure.tmdb.TmdbClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DEB-4074 (DEB-4032, FR-4; Acceptance 5 and 7) — `BrowseCard.box_sets` and `SeededBrowseResponse.box_sets`, filled from the cache, and
 * only for what the viewer's own `items` hold. The test reads the response's `box_sets`, not a screen.
 */
class BrowseBoxSetsTest {
    private val dir = "/tmp/jellystructure-test-browse-box-sets-${getpid()}"
    private val fake = FakeJellyfinCollections()
    private lateinit var service: BrowseService
    private lateinit var collections: JellyfinCollections
    private lateinit var mediaStore: MediaStore

    private val viewer = DeviceData(
        deviceId = "dev1", deviceToken = "tok1", jellyfinUserId = "user1", jellyfinUsername = "user1",
        jellyfinUserToken = "jtok1", isAdmin = false,
    )

    @BeforeTest fun setUp() = runBlocking {
        platform.posix.system("mkdir -p '$dir'")
        val db = createDatabase("$dir/test.db")
        val configStore = ConfigStore("$dir/config.toml")
        configStore.update(configStore.current.copy(apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = fake.url, jellyfinToken = "tok")))
        mediaStore = MediaStore(db, JsTagStore("$dir/tags.json"), configStore)
        collections = JellyfinCollections(JellyfinClient(), configStore, mediaStore)
        val config = RaviloConfigService(db, TvEventBus(CoroutineScope(SupervisorJob())))
        service = BrowseService(mediaStore, JellyfinClient(), configStore, config, ArtworkDownloader(TmdbClient(configStore), Screengrabber()), logoDownloader = null, jellyfinCollections = collections)
        for (id in listOf("a", "b", "c", "d")) fake.film("jf-$id")
        fake.collection("box-1", "Collection A", "jf-a", "jf-b")
        fake.collection("box-2", "Collection B", "jf-b", "jf-c")
        fake.collection("box-3", "Collection C")   // no members: never offered
        for ((id, tags) in listOf("a" to listOf("hidden"), "b" to listOf("seen"), "c" to listOf("seen"), "d" to emptyList())) mediaStore.addOrUpdate(film(id, tags))
        collections.refresh()
        Unit
    }

    @AfterTest fun tearDown() {
        fake.stop()
        dev.jellystructure.db.closeLastDatabaseForTests()
        platform.posix.system("rm -rf '$dir'")
    }

    private fun film(id: String, tags: List<String>) = MediaItem(
        id = id, title = id, year = 2020, kind = MediaKind.MOVIE, path = "/lib/$id.mkv", tmdbId = null, originalLanguage = "en",
        resolvedLanguage = "en", posterPath = null, overview = null, tracks = emptyList(), issueCount = 0, scannedAt = 0L,
        jellyfinId = "jf-$id", tags = tags, genres = if (id == "c") listOf("Drama") else emptyList(),
    )

    @Test
    fun `a card carries the ids of the collections that hold it and the response names each one once`() = runBlocking {
        val r = service.browseByQuery(viewer, null, "MOVIE")
        val cards = r.items.associate { it.card.id to it.boxSets }
        assertEquals(listOf("box-1"), cards.getValue("jf-a"))
        assertEquals(setOf("box-1", "box-2"), cards.getValue("jf-b").toSet())
        assertEquals(listOf("box-2"), cards.getValue("jf-c"))
        assertEquals(emptyList(), cards.getValue("jf-d"))
        assertEquals(listOf(BoxSetFacet("box-1", "Collection A"), BoxSetFacet("box-2", "Collection B")), r.boxSets, "the empty collection is not named")
    }

    @Test
    fun `only collections on the response's own items are named`() = runBlocking {
        val onlyC = ConditionGroup(children = listOf(Condition(facet = "genre", op = "is_any_of", values = listOf("Drama"))))
        val r = service.browseByQuery(viewer, onlyC, "MOVIE")
        assertEquals(listOf("jf-c"), r.items.map { it.card.id })
        assertEquals(listOf(BoxSetFacet("box-2", "Collection B")), r.boxSets, "Collection A holds nothing on this page")
    }

    @Test
    fun `a viewer whose tag filter hides every member of a collection is not shown its name`() = runBlocking {
        val seeOnlySeen = viewer.copy(allowedTags = setOf("seen"))
        val r = service.browseByQuery(seeOnlySeen, null, "MOVIE")
        assertEquals(setOf("jf-b", "jf-c"), r.items.map { it.card.id }.toSet())
        // Collection A holds one hidden film (a) and one visible (b): named, and its card list carries b only.
        assertEquals(listOf("box-1", "box-2"), r.boxSets.map { it.id })
        val hideA = viewer.copy(blockedTags = setOf("seen"))   // sees a and d: Collection B's members are b and c, both hidden
        val r2 = service.browseByQuery(hideA, null, "MOVIE")
        assertEquals(setOf("jf-a", "jf-d"), r2.items.map { it.card.id }.toSet())
        assertEquals(listOf("box-1"), r2.boxSets.map { it.id }, "Collection B is not named to this viewer")
        assertEquals(emptyList(), r2.items.single { it.card.id == "jf-a" }.boxSets.filter { it == "box-2" })
    }

    @Test
    fun `the new fields are additions - an old server's body decodes with none and a new one's round-trips`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        assertEquals(emptyList(), json.decodeFromString<SeededBrowseResponse>("""{"items":[],"total":0}""").boxSets)
        val r = runBlocking { service.browseByQuery(viewer, null, "MOVIE") }
        assertEquals(r.boxSets, json.decodeFromString<SeededBrowseResponse>(json.encodeToString(r)).boxSets)
        assertEquals(true, "\"box_sets\"" in json.encodeToString(r))
    }
}
