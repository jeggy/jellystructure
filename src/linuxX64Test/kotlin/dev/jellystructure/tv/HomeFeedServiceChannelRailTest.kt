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
import kotlin.test.assertTrue

/**
 * Phase 206 (FR-206-6) — pins down the one property that matters for this phase's own regression: the
 * cheap [HomeFeedService] channel-rail verdict ([channelHasAnyMatch], reached via [HomeFeedService.getChannels])
 * must agree with what a full [buildChannelContent] build would say, for both the trivially-empty case
 * (channel-level [MediaItem.matchesChannel] scoping excludes everything) and the non-trivial case (the
 * channel's own scoped list is non-empty but every configured row's predicate — [matchesConfiguredRow],
 * shared with [buildFilterRow] — still excludes it). Both channels below share the exact same
 * channel-level filter, so channel-level scoping cannot be what tells them apart — only the row-level
 * predicate refactor can, which is the part this phase actually changed.
 *
 * Not covered here (and not claimed): a direct assertion on `MediaCard`/sort/`take(30)` construction
 * counts. `matchesConfiguredRow`'s extraction is what makes the verdict and the real content share one
 * definition rather than two hand-kept-in-sync copies (FR-206-1's own wording) — a future edit that
 * reintroduces a second copy would very likely also break this test's correctness assertion, but this
 * test does not instrument or bound the actual work performed.
 */
class HomeFeedServiceChannelRailTest {
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
        dbPath = "/tmp/jellystructure-test-channelrail-$suffix.db"
        val db = createDatabase(dbPath)
        val configStore = ConfigStore("/tmp/jellystructure-test-channelrail-$suffix.toml")
        val jsTagStore = JsTagStore("/tmp/jellystructure-test-channelrail-$suffix-tags.json")
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

    private fun movie(id: String, genres: List<String>) = MediaItem(
        id = id, title = id, year = 2020, kind = MediaKind.MOVIE, path = "/movies/$id.mkv", tmdbId = null,
        originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0,
        scannedAt = 0L, episodes = emptyList(), genres = genres, jellyfinId = id,
    )

    // Both channels share this exact channel-level filter — genre "Action" — so their scoped item sets
    // are IDENTICAL (both non-empty). Only their per-row predicate differs.
    private fun channel(id: String, rowGenreTitle: String) = ChannelConfig(
        id = id,
        filterGenre = "Action",
        rows = ChannelRowsConfig(
            mode = "custom",
            items = listOf(RowConfig(id = "$id-row", kind = RowKind.GENRE, title = rowGenreTitle)),
            // Never touch Jellyfin — this test is entirely offline.
            system = ChannelSystemRows(cont = SystemContinue(show = false), newly = SystemNewly(show = false)),
        ),
    )

    @Test
    fun `a channel whose row predicate matches nothing is excluded while a matching one is included`() = runBlocking {
        mediaStore.addOrUpdate(movie("m1", genres = listOf("Action")))

        val matching = channel("has-match", rowGenreTitle = "")       // blank title -> genreTermsOf empty -> matches everything in scope
        val nonMatching = channel("no-match", rowGenreTitle = "Zzz-Nonexistent")

        configService.save(GLOBAL_USER_ID, RaviloConfig(channels = listOf(matching, nonMatching)))

        val rail = service.getChannels(device)

        assertEquals(listOf("has-match"), rail.map { it.id }, "the rail must include exactly the channel whose row predicate actually matches")
        assertTrue(rail.none { it.id == "no-match" }, "a channel with a non-empty scoped list but zero matching rows must not appear on the rail")
    }

    @Test
    fun `a channel with no scoped items at all is excluded regardless of its rows`() = runBlocking {
        mediaStore.addOrUpdate(movie("m1", genres = listOf("Comedy")))  // no item has genre "Action"

        val cfg = channel("has-match", rowGenreTitle = "")  // row would match everything IF the channel scope were non-empty
        configService.save(GLOBAL_USER_ID, RaviloConfig(channels = listOf(cfg)))

        val rail = service.getChannels(device)

        assertTrue(rail.isEmpty(), "the cheap outer test (filtered.isEmpty()) must exclude a channel whose channel-level filter matches nothing")
    }
}
