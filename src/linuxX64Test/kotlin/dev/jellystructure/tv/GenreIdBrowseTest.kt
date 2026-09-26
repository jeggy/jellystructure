package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
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
import dev.jellystructure.shared.tv.MediaCard
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

/**
 * Phase 271 (FR-271-9, acceptance 1–3 and 6) — the Genres wall counts by id, one tile per genre, named
 * in the viewer's app language; the grid it opens agrees with the count; and an installed app that sends
 * a genre NAME, in any language, gets the same titles.
 */
class GenreIdBrowseTest {
    private lateinit var dbPath: String
    private lateinit var service: BrowseService
    private lateinit var mediaStore: MediaStore
    private lateinit var configService: RaviloConfigService

    private val device = DeviceData(
        deviceId = "dev1", deviceToken = "tok1", jellyfinUserId = "user1", jellyfinUsername = "user1",
        jellyfinUserToken = "jtok1", isAdmin = false,
    )

    @BeforeTest
    fun setUp() {
        GenreCatalog.replaceForTest(mapOf(
            35 to mapOf("en" to "Comedy", "da" to "Komedie", "it" to "Commedia"),
            18 to mapOf("en" to "Drama", "da" to "Drama"),
        ))
        val suffix = getpid()
        dbPath = "/tmp/jellystructure-test-genre-browse-$suffix.db"
        val db = createDatabase(dbPath)
        val configStore = ConfigStore("/tmp/jellystructure-test-genre-browse-$suffix.toml")
        mediaStore = MediaStore(db, JsTagStore("/tmp/jellystructure-test-genre-browse-$suffix-tags.json"), configStore)
        configService = RaviloConfigService(db, TvEventBus(CoroutineScope(SupervisorJob())))
        service = BrowseService(mediaStore, JellyfinClient(), configStore, configService, ArtworkDownloader(TmdbClient(configStore), Screengrabber()), logoDownloader = null)
    }

    @AfterTest
    fun tearDown() {
        GenreCatalog.replaceForTest(emptyMap())
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    private fun item(id: String, genres: List<String>, resolved: String) = MediaItem(
        id = id, title = id, year = 2020, kind = MediaKind.MOVIE, path = "/lib/$id.mkv", tmdbId = null,
        originalLanguage = resolved, resolvedLanguage = resolved, posterPath = null, overview = null,
        tracks = emptyList(), issueCount = 0, scannedAt = 0L, jellyfinId = id, genres = genres,
    )

    private suspend fun seed() {
        mediaStore.addOrUpdate(item("en1", listOf("Comedy", "Drama"), "en"))
        mediaStore.addOrUpdate(item("da1", listOf("Komedie"), "da"))
        mediaStore.addOrUpdate(item("da2", listOf("Komedie", "Drama"), "da"))
        mediaStore.addOrUpdate(item("it1", listOf("Commedia"), "it"))
        mediaStore.addOrUpdate(item("x1", listOf("Anime"), "ja"))   // hand-added: no TMDB id
    }

    @Test
    fun `one tile per genre whatever language the titles came in and the grid agrees`() = runBlocking {
        seed()
        val f = service.facets(device, null)
        assertEquals(listOf("Comedy" to 4, "Drama" to 2, "Anime" to 1), f.genres.map { it.name to it.count })
        assertEquals(listOf(35, 18, null), f.genres.map { it.id })
        for (g in f.genres) {
            val grid = service.browseByQuery(device, ConditionGroup(children = listOf(Condition(facet = "genre", op = "is_any_of", values = listOf(g.name)))), null)
            assertEquals(g.count, grid.total, "tile ${g.name}")
        }
        // An installed app that learnt the Danish or Italian name sends it back: same titles.
        assertEquals(4, service.browse(device, null, genres = listOf("Komedie")).total)
        assertEquals(4, service.browse(device, null, genres = listOf("commedia")).total)
    }

    @Test
    fun `a Danish viewer's wall and cards say Komedie and the cached facets are not keyed by language`() = runBlocking {
        seed()
        assertEquals("Comedy", service.facets(device, null).genres.first().name)
        configService.setAdminUiLanguage(device.jellyfinUserId, "da")
        val f = service.facets(device, null)
        assertEquals("Komedie", f.genres.first().name)
        val cards = service.browseByQuery(device, null, null).items.associateBy { it.card.id }
        assertEquals(listOf("Komedie", "Drama"), cards.getValue("en1").genres, "an English title, a Danish app")
        assertEquals(listOf<Int?>(35, 18), cards.getValue("en1").genreIds)
        assertEquals("Komedie", cards.getValue("it1").card.genre)
        assertEquals(listOf("Anime"), cards.getValue("x1").genres, "a hand-added genre reads as typed")
    }

    @Test
    fun `Home's English cards are relabelled per viewer after the cache`() {
        val card = MediaCard(id = "c", kind = dev.jellystructure.shared.tv.MediaKind.MOVIE, title = "c", year = 2020, genre = "Comedy", rating = null, posterUrl = null, backdropUrl = null)
        assertEquals("Komedie", card.withGenreLabels("da").genre)
        assertEquals("Comedy", card.withGenreLabels("fo").genre, "no Faroese label ⇒ English")
        assertEquals("Comedy", card.withGenreLabels("en").genre)
        assertNull(card.copy(genre = null).withGenreLabels("da").genre)
    }
}
