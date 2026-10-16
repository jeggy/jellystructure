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
 * DEB-1264 slice 1 (FR-1..FR-6) — a Continue Watching row with `hide_tags` leaves out every title carrying one of
 * those tags, on the row and on its See all, on Home, on a Same-as-Home collection, on a Custom collection and on a
 * user's own layout. Entirely offline: the canonical list is seeded through [HomeFeedService.continueSourceForTest].
 * The stand-in tags are `kids-tv` and `cartoons`.
 */
class HomeFeedContinueHideTagsTest {
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
        dbPath = "/tmp/jellystructure-test-hidetags-$suffix.db"
        val db = createDatabase(dbPath)
        val configStore = ConfigStore("/tmp/jellystructure-test-hidetags-$suffix.toml")
        val jsTagStore = JsTagStore("/tmp/jellystructure-test-hidetags-$suffix-tags.json")
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

    private fun item(id: String, kind: MediaKind = MediaKind.MOVIE, tags: List<String> = emptyList()) = MediaItem(
        id = id, title = id, year = 2020, kind = kind, path = "/lib/$id", tmdbId = null,
        originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0,
        scannedAt = 0L, episodes = emptyList(), genres = listOf("Action"), tags = tags, jellyfinId = id,
    )

    private fun cont(hide: List<String> = emptyList()) = RowConfig(id = "continue", kind = RowKind.CONTINUE, title = "Continue Watching", hideTags = hide)

    private fun collection(id: String, custom: Boolean = false, hide: List<String> = emptyList(), genre: String = "Action") = ChannelConfig(
        id = id, filterGenre = genre,
        rows = if (custom) ChannelRowsConfig(mode = "custom", system = ChannelSystemRows(cont = SystemContinue(show = true, hideTags = hide))) else null,
    )

    /** Seeds the library and the canonical Continue list, in the order given. */
    private suspend fun seed(vararg items: MediaItem) {
        for (i in items) mediaStore.addOrUpdate(i)
        service.continueSourceForTest = { items.mapIndexed { n, mi -> Triple(mi, 10f, 100L - n) } }
        service.refreshContinueListFor(device)
    }

    private suspend fun homeIds() = service.getHomeFeed(device).rows.firstOrNull { it.kind == RowKind.CONTINUE }?.items?.map { it.id }
    private suspend fun channelIds(id: String) = service.getChannelFeed(device, id).rows.firstOrNull { it.kind == RowKind.CONTINUE }?.items?.map { it.id }
    private suspend fun seeAll(channelId: String? = null) = service.continueWatchingAll(device, channelId).map { it.id }

    @Test
    fun `Home hides a tagged series' entry and See all agrees`() = runBlocking {
        seed(item("kidshow", MediaKind.TV_SHOW, listOf("kids-tv")), item("film"), item("drama", MediaKind.TV_SHOW))
        configService.save(GLOBAL_USER_ID, RaviloConfig(rows = listOf(cont(hide = listOf("kids-tv")))))

        assertEquals(listOf("film", "drama"), homeIds())
        assertEquals(listOf("film", "drama"), seeAll())
        assertEquals(2, service.getHomeFeed(device).rows.first { it.kind == RowKind.CONTINUE }.seedTotalCount, "the See all tile counts what is left")
    }

    @Test
    fun `a Same as Home collection uses Home's list on top of its own scoping`() = runBlocking {
        seed(item("kidshow", MediaKind.TV_SHOW, listOf("kids-tv")), item("film"))
        configService.save(GLOBAL_USER_ID, RaviloConfig(rows = listOf(cont(hide = listOf("kids-tv"))), channels = listOf(collection("inh"))))

        assertEquals(listOf("film"), channelIds("inh"))
        assertEquals(listOf("film"), seeAll("inh"))
    }

    @Test
    fun `a Custom collection uses only its own list and never Home's`() = runBlocking {
        seed(item("kidshow", MediaKind.TV_SHOW, listOf("kids-tv")), item("toon", tags = listOf("cartoons")), item("film"))
        configService.save(GLOBAL_USER_ID, RaviloConfig(
            rows = listOf(cont(hide = listOf("kids-tv"))),
            channels = listOf(collection("empty", custom = true), collection("toons", custom = true, hide = listOf("cartoons"))),
        ))

        assertEquals(listOf("toon", "film"), homeIds(), "Home hides kids-tv only")
        assertEquals(listOf("kidshow", "toon", "film"), channelIds("empty"), "a Custom collection with an empty list does not inherit Home's")
        assertEquals(listOf("kidshow", "toon", "film"), seeAll("empty"))
        assertEquals(listOf("kidshow", "film"), channelIds("toons"))
        assertEquals(listOf("kidshow", "film"), seeAll("toons"))
    }

    @Test
    fun `matching is case-insensitive on the whole name and never a substring`() = runBlocking {
        seed(item("upper", tags = listOf("KIDS-TV")), item("prefix", tags = listOf("kids-tv-extra")), item("short", tags = listOf("kids")), item("plain"))
        configService.save(GLOBAL_USER_ID, RaviloConfig(rows = listOf(cont(hide = listOf("Kids-Tv")))))

        assertEquals(listOf("prefix", "short", "plain"), homeIds())
        configService.save(GLOBAL_USER_ID, RaviloConfig(rows = listOf(cont(hide = listOf("kids")))))
        assertEquals(listOf("upper", "prefix", "plain"), homeIds(), "`kids` hides `kids` only, not `kids-tv`")
    }

    @Test
    fun `the cap counts after the filter`() = runBlocking {
        val hidden = (1..5).map { item("h$it", tags = listOf("kids-tv")) }
        val shown = (1..25).map { item("s$it") }
        seed(*(hidden + shown).toTypedArray())  // the hidden five are the newest
        configService.save(GLOBAL_USER_ID, RaviloConfig(rows = listOf(cont(hide = listOf("kids-tv")))))

        val row = service.getHomeFeed(device).rows.first { it.kind == RowKind.CONTINUE }
        assertEquals(20, row.items.size)
        assertEquals((1..20).map { "s$it" }, row.items.map { it.id }, "a hidden title takes no slot")
        assertEquals(25, row.seedTotalCount)
        assertEquals(25, seeAll().size, "See all stays uncapped")
    }

    @Test
    fun `a change to hide_tags alone rebuilds the cached feed`() = runBlocking {
        seed(item("kidshow", MediaKind.TV_SHOW, listOf("kids-tv")), item("film"))
        configService.save(GLOBAL_USER_ID, RaviloConfig(rows = listOf(cont())))
        assertEquals(listOf("kidshow", "film"), homeIds())  // now cached

        configService.save(GLOBAL_USER_ID, RaviloConfig(rows = listOf(cont(hide = listOf("kids-tv")))))
        assertEquals(listOf("film"), homeIds(), "the next read after the save reflects it, with no TTL wait")

        configService.save(GLOBAL_USER_ID, RaviloConfig(rows = listOf(cont())))
        assertEquals(listOf("kidshow", "film"), homeIds(), "removing the tag brings the title back at its true position")
    }

    @Test
    fun `a change to a Custom collection's hide_tags alone rebuilds its cached page`() = runBlocking {
        seed(item("toon", tags = listOf("cartoons")), item("film"))
        configService.save(GLOBAL_USER_ID, RaviloConfig(channels = listOf(collection("c", custom = true))))
        assertEquals(listOf("toon", "film"), channelIds("c"))

        configService.save(GLOBAL_USER_ID, RaviloConfig(channels = listOf(collection("c", custom = true, hide = listOf("cartoons")))))
        assertEquals(listOf("film"), channelIds("c"))
    }

    @Test
    fun `a list that hides every entry omits the row and a collection is not non-empty because of it`() = runBlocking {
        seed(item("kidshow", MediaKind.TV_SHOW, listOf("kids-tv")))
        configService.save(GLOBAL_USER_ID, RaviloConfig(
            rows = listOf(cont(hide = listOf("kids-tv"))),
            channels = listOf(collection("inh"), collection("cust", custom = true, hide = listOf("kids-tv"))),
        ))

        assertNull(homeIds(), "the row and its header are omitted")
        assertTrue(seeAll().isEmpty())
        assertNull(channelIds("inh"))
        assertNull(channelIds("cust"))
    }

    @Test
    fun `a collection whose only content is a hidden Continue title is not on the rail`() = runBlocking {
        seed(item("kidshow", MediaKind.TV_SHOW, listOf("kids-tv")))
        fun onlyContinue(hide: List<String>) = ChannelConfig(
            id = "only", filterGenre = "Action",
            rows = ChannelRowsConfig(mode = "custom", system = ChannelSystemRows(cont = SystemContinue(show = true, hideTags = hide), newly = SystemNewly(show = false))),
        )
        configService.save(GLOBAL_USER_ID, RaviloConfig(channels = listOf(onlyContinue(emptyList()))))
        assertEquals(listOf("only"), service.getChannels(device).map { it.id }, "control: the Continue title is what keeps it on the rail")

        configService.save(GLOBAL_USER_ID, RaviloConfig(channels = listOf(onlyContinue(listOf("kids-tv")))))
        assertTrue(service.getChannels(device).isEmpty())
    }

    @Test
    fun `a user's own layout list applies to that user and not to one on the global layout`() = runBlocking {
        seed(item("kidshow", MediaKind.TV_SHOW, listOf("kids-tv")), item("toon", tags = listOf("cartoons")), item("film"))
        configService.save(GLOBAL_USER_ID, RaviloConfig(rows = listOf(cont(hide = listOf("kids-tv")))))
        configService.save("user1", RaviloConfig(rows = listOf(cont(hide = listOf("kids-tv", "cartoons")))))
        assertEquals(listOf("film"), homeIds())
        assertEquals(listOf("film"), seeAll())

        configService.removeCustomConfig("user1")
        assertEquals(listOf("toon", "film"), homeIds(), "back on the global layout, only its own list applies")
    }

    @Test
    fun `saving trims and drops empty entries and collapses case-insensitive duplicates keeping the first spelling`() {
        val saved = configService.normalize(RaviloConfig(
            rows = listOf(cont(hide = listOf(" Kids-TV ", "", "  ", "kids-tv", "Cartoons", "KIDS-TV"))),
            channels = listOf(collection("c", custom = true, hide = listOf("a ", "A", " ", "b"))),
        ))
        assertEquals(listOf("Kids-TV", "Cartoons"), saved.rows.single().hideTags)
        assertEquals(listOf("a", "b"), saved.channels.single().rows!!.system.cont.hideTags)
    }
}
