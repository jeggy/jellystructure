package dev.jellystructure.server

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** DEB-5709 (FR-5) — `/api/health`'s `catalog` block counts the visible titles and the flagged ones. */
class CatalogHealthTest {
    private lateinit var dbPath: String
    private lateinit var store: MediaStore

    private fun movie(id: String, missing: Boolean = false) = MediaItem(
        id = id, title = "Title $id", year = 2020, kind = MediaKind.MOVIE, path = "/movies/$id.mkv", tmdbId = null,
        originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0,
        scannedAt = 0L, episodes = emptyList(), missingFromSource = missing,
    )

    @BeforeTest
    fun setUp() {
        dbPath = "/tmp/jellystructure-test-cataloghealth-${getpid()}.db"
        val db = createDatabase(dbPath)
        val configStore = ConfigStore("/tmp/jellystructure-test-cataloghealth-${getpid()}.toml")
        val jsTagStore = JsTagStore("/tmp/jellystructure-test-cataloghealth-${getpid()}-tags.json")
        store = MediaStore(db, jsTagStore, configStore)
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    @Test
    fun `visible and flagged titles are counted apart`() = runBlocking {
        store.addOrUpdate(movie("a"))
        store.addOrUpdate(movie("b"))
        store.addOrUpdate(movie("c"))
        store.addOrUpdate(movie("d", missing = true))
        store.addOrUpdate(movie("e", missing = true))

        assertEquals("""{"titles":3,"missing_from_source":2}""", catalogHealthJson(store))
    }

    @Test
    fun `an empty store says zero and zero`() = runBlocking {
        assertEquals("""{"titles":0,"missing_from_source":0}""", catalogHealthJson(store))
    }
}
