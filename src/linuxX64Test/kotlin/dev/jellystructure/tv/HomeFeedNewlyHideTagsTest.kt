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
 * DEB-5003 (FR-1..FR-6) — the Newly Added rows leave out every title carrying one of the section's `hide_tags`, on Home,
 * on a Same-as-Home collection, on a Custom collection and on a user's own layout, before the cap of 30.
 * Mirror of [HomeFeedContinueHideTagsTest]; the stand-in tags are `kids-tv` and `cartoons`.
 */
class HomeFeedNewlyHideTagsTest {
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
        dbPath = "/tmp/jellystructure-test-newlyhide-$suffix.db"
        val db = createDatabase(dbPath)
        val configStore = ConfigStore("/tmp/jellystructure-test-newlyhide-$suffix.toml")
        val jsTagStore = JsTagStore("/tmp/jellystructure-test-newlyhide-$suffix-tags.json")
        mediaStore = MediaStore(db, jsTagStore, configStore)
        val tvEventBus = TvEventBus(CoroutineScope(SupervisorJob()))
        configService = RaviloConfigService(db, tvEventBus)
        val artwork = ArtworkDownloader(TmdbClient(configStore), Screengrabber())
        service = HomeFeedService(mediaStore, configService, JellyfinClient(), configStore, tvEventBus, artwork)
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    /** The store stamps `createdAt` itself on insert, so every seeded title ties on recency and a row lists them by
     *  title, ascending. The expectations below follow that; the ids are chosen so the order is easy to read. */
    private fun item(id: String, kind: MediaKind = MediaKind.MOVIE, tags: List<String> = emptyList()) = MediaItem(
        id = id, title = id, year = 2020, kind = kind, path = "/lib/$id", tmdbId = null,
        originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0,
        scannedAt = 0L, episodes = emptyList(), genres = listOf("Action"), tags = tags, jellyfinId = id,
    )

    private fun newly(hide: List<String> = emptyList(), order: Int = 0, enabled: Boolean = true, mediaKind: String? = null, id: String = "newly-all") =
        RowConfig(id = id, kind = RowKind.NEWLY_ADDED, enabled = enabled, order = order, mediaKind = mediaKind, hideTags = hide)

    private fun cont(hide: List<String> = emptyList()) = RowConfig(id = "continue", kind = RowKind.CONTINUE, order = 9, hideTags = hide)

    private fun collection(id: String, custom: Boolean = false, hide: List<String> = emptyList(), merge: Boolean = false, show: Boolean = true) = ChannelConfig(
        id = id, filterGenre = "Action",
        rows = if (custom) ChannelRowsConfig(mode = "custom", system = ChannelSystemRows(cont = SystemContinue(show = false), newly = SystemNewly(show = show, merge = merge, hideTags = hide))) else null,
    )

    private suspend fun seed(vararg items: MediaItem) { for (i in items) mediaStore.addOrUpdate(i) }

    private suspend fun home() = service.getHomeFeed(device).rows.filter { it.kind == RowKind.NEWLY_ADDED }.associate { it.id to it.items.map { c -> c.id } }
    private suspend fun channel(id: String) = service.getChannelFeed(device, id).rows.filter { it.kind == RowKind.NEWLY_ADDED }.associate { it.id to it.items.map { c -> c.id } }

    private suspend fun stand() = seed(
        item("kidshow1", MediaKind.TV_SHOW, listOf("kids-tv")), item("kidshow2", MediaKind.TV_SHOW, listOf("kids-tv")),
        item("kidfilm", tags = listOf("kids-tv")),
        item("drama", MediaKind.TV_SHOW), item("film"),
    )

    @Test
    fun `Home split rows leave out the hidden titles and the others stay in order`() = runBlocking {
        stand()
        configService.save(GLOBAL_USER_ID, RaviloConfig(rows = listOf(newly(listOf("kids-tv")), cont())))

        assertEquals(mapOf("newly-all-movies" to listOf("film"), "newly-all-series" to listOf("drama")), home())
    }

    @Test
    fun `merged row leaves out the hidden titles`() = runBlocking {
        stand()
        configService.save(GLOBAL_USER_ID, RaviloConfig(mergeNewlyAdded = true, rows = listOf(newly(listOf("kids-tv")))))

        assertEquals(mapOf("newly-added" to listOf("drama", "film")), home())
    }

    @Test
    fun `typed rows read the first Newly Added rows list whether on or off and ignore a later one`() = runBlocking {
        stand()
        configService.save(GLOBAL_USER_ID, RaviloConfig(rows = listOf(
            newly(listOf("kids-tv"), order = 0, enabled = false, id = "off"),
            newly(emptyList(), order = 1, mediaKind = "MOVIE", id = "m"),
            newly(listOf("film"), order = 2, mediaKind = "SERIES", id = "s"),
        )))

        assertEquals(mapOf("m" to listOf("film"), "s" to listOf("drama")), home(), "the disabled first row's list covers both; the later row's `film` is ignored")
    }

    @Test
    fun `the Continue list is independent of the Newly Added list`() = runBlocking {
        stand()
        configService.save(GLOBAL_USER_ID, RaviloConfig(rows = listOf(newly(listOf("kids-tv")), cont(hide = listOf("cartoons")))))
        assertEquals(listOf("drama"), home()["newly-all-series"])

        configService.save(GLOBAL_USER_ID, RaviloConfig(rows = listOf(newly(), cont(hide = listOf("kids-tv")))))
        assertEquals(listOf("drama", "kidshow1", "kidshow2"), home()["newly-all-series"], "a Continue list hides nothing in Newly Added")
    }

    @Test
    fun `the cap counts after the filter`() = runBlocking {
        seed(*((1..5).map { item("h$it", tags = listOf("kids-tv")) } + (1..40).map { item("s${it.toString().padStart(2, '0')}") }).toTypedArray())
        configService.save(GLOBAL_USER_ID, RaviloConfig(rows = listOf(newly(listOf("kids-tv")))))

        val row = home().getValue("newly-all-movies")
        assertEquals(30, row.size)
        assertEquals((1..30).map { "s${it.toString().padStart(2, '0')}" }, row, "a hidden title takes no slot (unfiltered, h1..h5 sort first); the next title fills it")
    }

    @Test
    fun `a change to hide_tags alone rebuilds the cached feed`() = runBlocking {
        stand()
        configService.save(GLOBAL_USER_ID, RaviloConfig(rows = listOf(newly())))
        assertEquals(listOf("film", "kidfilm"), home()["newly-all-movies"])  // now cached

        configService.save(GLOBAL_USER_ID, RaviloConfig(rows = listOf(newly(listOf("kids-tv")))))
        assertEquals(listOf("film"), home()["newly-all-movies"], "the next read reflects it, with no TTL wait")

        configService.save(GLOBAL_USER_ID, RaviloConfig(rows = listOf(newly())))
        assertEquals(listOf("film", "kidfilm"), home()["newly-all-movies"], "removing the tag brings the title back at its true position")
    }

    @Test
    fun `a Custom collection uses only its own list and a Same as Home one uses Home's`() = runBlocking {
        seed(item("kidfilm", tags = listOf("kids-tv")), item("toon", tags = listOf("cartoons")), item("film"))
        configService.save(GLOBAL_USER_ID, RaviloConfig(
            rows = listOf(newly(listOf("kids-tv"))),
            channels = listOf(collection("inh"), collection("empty", custom = true), collection("toons", custom = true, hide = listOf("cartoons"))),
        ))

        assertEquals(listOf("film", "toon"), home()["newly-all-movies"], "Home hides kids-tv only")
        assertEquals(listOf("film", "toon"), channel("inh")["newly-all-movies"], "Same as Home hides what Home hides")
        assertEquals(listOf("film", "kidfilm", "toon"), channel("empty")["newly-added-movies"], "a Custom collection with an empty list does not inherit Home's")
        assertEquals(listOf("film", "kidfilm"), channel("toons")["newly-added-movies"])

        configService.save(GLOBAL_USER_ID, RaviloConfig(
            rows = listOf(newly(listOf("kids-tv"))),
            channels = listOf(collection("m", custom = true, hide = listOf("cartoons"), merge = true)),
        ))
        assertEquals(mapOf("newly-added" to listOf("film", "kidfilm")), channel("m"), "the merged row of a Custom collection is filtered too")
    }

    @Test
    fun `a user's own layout list applies to that user and not to one on the global layout`() = runBlocking {
        stand()
        seed(item("toon", tags = listOf("cartoons")))
        configService.save(GLOBAL_USER_ID, RaviloConfig(rows = listOf(newly(listOf("kids-tv")))))
        configService.save("user1", RaviloConfig(rows = listOf(newly(listOf("kids-tv", "cartoons")))))
        assertEquals(listOf("film"), home()["newly-all-movies"])

        configService.removeCustomConfig("user1")
        assertEquals(listOf("film", "toon"), home()["newly-all-movies"], "back on the global layout, only its own list applies")
    }

    @Test
    fun `hiding every series leaves the Movies row and hiding all omits the row`() = runBlocking {
        seed(item("kidshow", MediaKind.TV_SHOW, listOf("kids-tv")), item("film", tags = listOf("cartoons")))
        configService.save(GLOBAL_USER_ID, RaviloConfig(rows = listOf(newly(listOf("kids-tv")))))
        assertEquals(mapOf("newly-all-movies" to listOf("film")), home())

        configService.save(GLOBAL_USER_ID, RaviloConfig(rows = listOf(newly(listOf("kids-tv", "cartoons")))))
        assertTrue(home().isEmpty(), "rows and headers are omitted")
    }

    @Test
    fun `a collection whose only titles are hidden shows no Newly Added row and is not on the rail because of one`() = runBlocking {
        seed(item("kidshow", MediaKind.TV_SHOW, listOf("kids-tv")))
        configService.save(GLOBAL_USER_ID, RaviloConfig(channels = listOf(collection("only", custom = true))))
        assertEquals(listOf("only"), service.getChannels(device).map { it.id }, "control: the Newly Added title keeps it on the rail")

        configService.save(GLOBAL_USER_ID, RaviloConfig(channels = listOf(collection("only", custom = true, hide = listOf("kids-tv")))))
        assertTrue(service.getChannels(device).isEmpty())
        assertNull(channel("only")["newly-added-series"])

        configService.save(GLOBAL_USER_ID, RaviloConfig(rows = listOf(newly(listOf("kids-tv"))), channels = listOf(collection("inh"))))
        assertTrue(service.getChannels(device).isEmpty(), "Same as Home: Home's list applies")

        configService.save(GLOBAL_USER_ID, RaviloConfig(mergeNewlyAdded = true, rows = listOf(newly(listOf("kids-tv"))), channels = listOf(collection("inh"))))
        assertTrue(service.getChannels(device).isEmpty(), "Merge on does not count the collection either")
    }

    @Test
    fun `saving trims and drops empty entries and collapses duplicates keeping the first spelling`() {
        val saved = configService.normalize(RaviloConfig(
            rows = listOf(newly(listOf(" Kids-TV ", "", "kids-tv", "Cartoons", "KIDS-TV"))),
            channels = listOf(collection("c", custom = true, hide = listOf("a ", "A", " ", "b"))),
        ))
        assertEquals(listOf("Kids-TV", "Cartoons"), saved.rows.single().hideTags)
        assertEquals(listOf("a", "b"), saved.channels.single().rows!!.system.newly.hideTags)
    }
}
