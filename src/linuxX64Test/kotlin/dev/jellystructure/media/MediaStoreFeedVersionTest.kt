package dev.jellystructure.media

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 204 (FR-204-6) — the regression this phase exists to prevent: Phase 182 shipped the correct
 * atomic [MediaStore.libraryVersion] increment and, with it, created the bug where a poster path
 * written for one title discarded HomeFeedService's assembled feed for every user — without a single
 * test going red. [MediaStore.feedVersion] is the fix (HomeFeedService now keys its caches on it, not
 * [MediaStore.libraryVersion]); this pins down that it only advances when a write's own content-signature
 * comparison says something a card could show actually changed.
 */
class MediaStoreFeedVersionTest {
    private lateinit var dbPath: String
    private lateinit var store: MediaStore

    private fun movie(id: String, title: String, posterPath: String? = null) = MediaItem(
        id = id, title = title, year = 2020, kind = MediaKind.MOVIE, path = "/movies/$id.mkv", tmdbId = null,
        originalLanguage = null, posterPath = posterPath, overview = null, tracks = emptyList(), issueCount = 0,
        scannedAt = 0L, episodes = emptyList(),
    )

    @BeforeTest
    fun setUp() {
        dbPath = "/tmp/jellystructure-test-feedversion-${getpid()}.db"
        val db = createDatabase(dbPath)
        val configStore = ConfigStore("/tmp/jellystructure-test-feedversion-${getpid()}.toml")
        val jsTagStore = JsTagStore("/tmp/jellystructure-test-feedversion-${getpid()}-tags.json")
        store = MediaStore(db, jsTagStore, configStore)
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) {
            runCatching { platform.posix.remove("$dbPath$suffix") }
        }
    }

    @Test
    fun `re-adding an item with no real content change does not advance feedVersion`() = runBlocking {
        store.addOrUpdate(movie("a", "A Movie"))
        val libVerAfterFirst = store.libraryVersion
        val feedVerAfterFirst = store.feedVersion

        // A scan re-finding the exact same content (the "500 unchanged titles" case FR-204-1 targets).
        store.addOrUpdate(movie("a", "A Movie"))

        assertTrue(store.libraryVersion > libVerAfterFirst, "libraryVersion must still advance on every write — other caches (Triage, facets) depend on that")
        assertEquals(feedVerAfterFirst, store.feedVersion, "an unrelated/unchanged re-write must not invalidate the feed cache")
    }

    @Test
    fun `a real content change does advance feedVersion`() = runBlocking {
        store.addOrUpdate(movie("a", "A Movie"))
        val feedVerBefore = store.feedVersion

        store.addOrUpdate(movie("a", "A Movie", posterPath = "/poster.jpg"))

        assertTrue(store.feedVersion > feedVerBefore, "a poster path change is card-visible and must invalidate the feed cache")
    }

    @Test
    fun `deleting a missing item advances feedVersion`() = runBlocking {
        store.addOrUpdate(movie("a", "A Movie").copy(missingFromSource = true))
        val feedVerBefore = store.feedVersion

        store.deleteItem("a")

        assertTrue(store.feedVersion > feedVerBefore, "a title disappearing must invalidate the feed cache")
    }
}
