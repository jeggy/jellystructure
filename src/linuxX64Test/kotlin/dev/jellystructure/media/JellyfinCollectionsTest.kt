package dev.jellystructure.media

import dev.jellystructure.auth.CollectionDeleteResult
import dev.jellystructure.auth.JellyfinClient
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DEB-4074 (DEB-4032, FR-3) — the in-memory collections cache: what a member maps to, what it ignores, that a failed refresh keeps the
 * last copy, and that our own writes are re-read. Stand-in names only.
 */
class JellyfinCollectionsTest {
    private val dir = "/tmp/jellystructure-test-jf-collections-${getpid()}"
    private val fake = FakeJellyfinCollections()
    private lateinit var store: MediaStore
    private lateinit var configStore: ConfigStore
    private lateinit var cache: JellyfinCollections

    @BeforeTest fun setUp() = runBlocking {
        platform.posix.system("mkdir -p '$dir'")
        val db = createDatabase("$dir/test.db")
        configStore = ConfigStore("$dir/config.toml")
        configStore.update(configStore.current.copy(apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = fake.url, jellyfinToken = "tok")))
        store = MediaStore(db, JsTagStore("$dir/tags.json"), configStore)
        cache = JellyfinCollections(JellyfinClient(), configStore, store)
        fake.film("jf-a"); fake.film("jf-b"); fake.film("jf-skipped"); fake.series("jf-s")
        store.addOrUpdate(title("title-a", MediaKind.MOVIE, "jf-a"))
        store.addOrUpdate(title("title-b", MediaKind.MOVIE, "jf-b"))
        store.addOrUpdate(title("title-s", MediaKind.TV_SHOW, "jf-s"))
    }

    @AfterTest fun tearDown() {
        fake.stop()
        dev.jellystructure.db.closeLastDatabaseForTests()
        platform.posix.system("rm -rf '$dir'")
    }

    private fun title(id: String, kind: MediaKind, jellyfinId: String?) = MediaItem(
        id = id, title = id, year = 2020, kind = kind, path = "/lib/$id.mkv", tmdbId = null, originalLanguage = "en", posterPath = null,
        overview = null, issueCount = 0, scannedAt = 0L, jellyfinId = jellyfinId, tracks = emptyList(),
    )

    @Test
    fun `before the first refresh a reader gets an empty copy and nothing is asked of Jellyfin`() {
        assertEquals(emptyList(), cache.current().collections)
        assertEquals(emptyList(), cache.current().boxSetsOf("title-a"))
        assertEquals(emptyList(), fake.requests())
    }

    @Test
    fun `a member maps to its title by Jellyfin id and one that is no title is counted and ignored`() = runBlocking {
        fake.collection("box-1", "Collection A", "jf-a", "jf-s", "jf-skipped")
        fake.collection("box-2", "Collection B", "jf-skipped")   // no mapped member: still listed
        assertTrue(cache.refresh())
        val c = cache.current()
        assertEquals(listOf("Collection A", "Collection B"), c.collections.map { it.name })
        assertEquals(listOf("title-a", "title-s"), c.get("box-1")!!.titleIds)
        assertEquals(1, c.get("box-1")!!.unmappedCount, "the film in a skipped library")
        assertEquals(emptyList(), c.get("box-2")!!.titleIds)
        assertEquals(listOf("box-1"), c.boxSetsOf("title-a"))
        assertEquals(listOf("box-1"), c.boxSetsOf("title-s"), "the series, not its episodes, is the member")
        assertEquals(emptyList(), c.boxSetsOf("title-b"))
    }

    @Test
    fun `a failed refresh keeps the last good copy whole`() = runBlocking {
        fake.collection("box-1", "Collection A", "jf-a")
        assertTrue(cache.refresh())
        val before = cache.current()
        fake.collection("box-2", "Collection B", "jf-b")
        fake.failMembers = true
        assertFalse(cache.refresh(), "one collection's members failed")
        assertEquals(before, cache.current(), "the same copy, not a half-built one")
        fake.failMembers = false; fake.failList = true
        assertFalse(cache.refresh(), "the list failed")
        assertEquals(listOf("Collection A"), cache.current().collections.map { it.name })
        fake.failList = false
        assertTrue(cache.refresh())
        assertEquals(listOf("Collection A", "Collection B"), cache.current().collections.map { it.name })
    }

    @Test
    fun `a change made in Jellyfin's own dashboard shows on the next refresh`() = runBlocking {
        fake.collection("box-1", "Collection A", "jf-a")
        cache.refresh()
        assertEquals(listOf("box-1"), cache.current().boxSetsOf("title-a"))
        fake.setMembers("box-1", "jf-b")
        assertEquals(listOf("box-1"), cache.current().boxSetsOf("title-a"), "still the last copy until a refresh")
        cache.refresh()
        assertEquals(emptyList(), cache.current().boxSetsOf("title-a"))
        assertEquals(listOf("box-1"), cache.current().boxSetsOf("title-b"))
    }

    @Test
    fun `no Jellyfin configured is no refresh and no request`() = runBlocking {
        configStore.update(configStore.current.copy(apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = "", jellyfinToken = "")))
        assertFalse(cache.refresh())
        assertEquals(emptyList(), fake.requests())
    }

    @Test
    fun `each write re-reads Jellyfin so the copy is what Jellyfin now holds`() = runBlocking {
        val id = cache.create("Collection A", listOf("jf-a"))!!
        assertEquals(listOf("title-a"), cache.current().get(id)!!.titleIds)
        assertTrue(cache.add(id, listOf("jf-b")))
        assertEquals(listOf("title-a", "title-b"), cache.current().get(id)!!.titleIds)
        assertTrue(cache.remove(id, listOf("jf-a")))
        assertEquals(listOf("title-b"), cache.current().get(id)!!.titleIds)
        assertTrue(cache.rename(id, "user-1", "Collection A renamed"))
        assertEquals("Collection A renamed", cache.current().get(id)!!.name)
        assertEquals(CollectionDeleteResult.NOT_A_COLLECTION, cache.delete("jf-a"))
        assertEquals(emptyList(), fake.deletes())
        assertEquals(CollectionDeleteResult.DELETED, cache.delete(id))
        assertEquals(emptyList(), cache.current().collections)
        assertEquals(listOf("DELETE /Items/$id"), fake.deletes())
    }
}
