package dev.jellystructure.auth

import dev.jellystructure.media.FakeJellyfinCollections
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-4074 (DEB-4032, FR-1, FR-2) — the Jellyfin client's collection calls against a fake Jellyfin that records every request.
 * FR-2 is the media-safety line: `DELETE /Items/{id}` deletes a title's files, so the one thing that matters here is which ids get one.
 */
class JellyfinCollectionsClientTest {
    private val fake = FakeJellyfinCollections().apply {
        film("film-a", "Film A"); film("film-b", "Film B"); series("series-a", "Series A")
        collection("box-1", "Collection A", "film-a", "series-a")
    }
    private val client = JellyfinClient()

    @AfterTest fun tearDown() { fake.stop() }

    @Test
    fun `a film's id a series' id and an unknown id send no DELETE`() = runBlocking {
        for (id in listOf("film-a", "series-a", "no-such-id")) {
            val result = client.deleteCollection(fake.url, "key", id)
            assertEquals(if (id == "no-such-id") CollectionDeleteResult.NOT_FOUND else CollectionDeleteResult.NOT_A_COLLECTION, result, id)
        }
        assertEquals(emptyList(), fake.deletes(), "no DELETE of any kind: ${fake.requests()}")
        assertEquals(setOf("film-a", "film-b", "series-a", "series-a-ep1", "box-1"), fake.items().map { it.id }.toSet(), "nothing was removed")
    }

    @Test
    fun `a blank id sends nothing at all`() = runBlocking {
        assertEquals(CollectionDeleteResult.NOT_FOUND, client.deleteCollection(fake.url, "key", ""))
        assertEquals(emptyList(), fake.requests())
    }

    @Test
    fun `an id Jellyfin's list does not hold is never deleted even when its Ids filter answers with the whole library`() = runBlocking {
        fake.ignoreIdsFilter = true   // the answer carries a film and a collection, but not the id asked for
        assertEquals(CollectionDeleteResult.NOT_FOUND, client.deleteCollection(fake.url, "key", "no-such-id"))
        assertEquals(CollectionDeleteResult.NOT_A_COLLECTION, client.deleteCollection(fake.url, "key", "film-a"))
        assertEquals(emptyList(), fake.deletes())
    }

    @Test
    fun `a collection's id sends exactly one DELETE and leaves its members`() = runBlocking {
        assertEquals(CollectionDeleteResult.DELETED, client.deleteCollection(fake.url, "key", "box-1"))
        assertEquals(listOf("DELETE /Items/box-1"), fake.deletes())
        assertEquals(setOf("film-a", "film-b", "series-a", "series-a-ep1"), fake.items().map { it.id }.toSet())
    }

    @Test
    fun `a Jellyfin that cannot be read sends no DELETE`() = runBlocking {
        fake.stop()
        assertEquals(CollectionDeleteResult.FAILED, client.deleteCollection(fake.url, "key", "box-1"))
    }

    @Test
    fun `list asks for BoxSet items and members ask for the titles under a collection recursively`() = runBlocking {
        assertEquals(listOf("box-1" to "Collection A"), client.getCollections(fake.url, "key")!!.map { it.id to it.name })
        // 12.1 answers a collection's ParentId list empty without Recursive, and with it also lists a member series' episodes.
        val members = client.getCollectionMembers(fake.url, "key", "box-1")!!
        assertEquals(listOf("film-a" to "Movie", "series-a" to "Series"), members.map { it.id to it.type })
        val get = fake.requests().first { "ParentId" in it }
        assertTrue("Recursive=true" in get && "IncludeItemTypes=Movie,Series,MusicVideo" in get, get)
    }

    @Test
    fun `a failed read is null never an empty list`() = runBlocking {
        fake.failList = true
        assertNull(client.getCollections(fake.url, "key"))
        fake.failMembers = true
        assertNull(client.getCollectionMembers(fake.url, "key", "box-1"))
    }

    @Test
    fun `create answers the new id and puts a long list of ids in batches`() = runBlocking {
        val ids = (1..250).map { "m$it" }
        val id = client.createCollection(fake.url, "key", "Franchise B", ids)!!
        val box = fake.items().first { it.id == id }
        assertEquals("Franchise B", box.name)
        assertEquals(ids, box.members)
        val posts = fake.requests().filter { it.startsWith("POST /Collections") }
        assertEquals(3, posts.size, posts.toString())
        assertTrue(posts[0].startsWith("POST /Collections?Name=Franchise%20B&Ids="), posts[0])
        assertTrue(posts[1].startsWith("POST /Collections/$id/Items?Ids=") && posts[2].startsWith("POST /Collections/$id/Items?Ids="), posts.toString())
    }

    @Test
    fun `create with no ids is an empty collection`() = runBlocking {
        val id = client.createCollection(fake.url, "key", "Collection C", emptyList())!!
        assertEquals(emptyList(), fake.items().first { it.id == id }.members)
    }

    @Test
    fun `add and remove touch the links and nothing else`() = runBlocking {
        assertTrue(client.addToCollection(fake.url, "key", "box-1", listOf("film-b")))
        assertEquals(listOf("film-a", "series-a", "film-b"), fake.items().first { it.id == "box-1" }.members)
        assertTrue(client.removeFromCollection(fake.url, "key", "box-1", listOf("film-a", "film-b")))
        assertEquals(listOf("series-a"), fake.items().first { it.id == "box-1" }.members)
        assertTrue(fake.items().any { it.id == "film-a" } && fake.items().any { it.id == "film-b" }, "the films stay")
        assertEquals(listOf("DELETE /Collections/box-1/Items?Ids=film-a,film-b"), fake.requests().filter { it.startsWith("DELETE") })
    }

    @Test
    fun `rename reads the whole item with a user and sends the whole item back not a partial body`() = runBlocking {
        assertTrue(client.renameCollection(fake.url, "key", "user-1", "box-1", "Collection A renamed"))
        assertEquals("Collection A renamed", fake.items().first { it.id == "box-1" }.name)
        assertEquals(listOf("film-a", "series-a"), fake.items().first { it.id == "box-1" }.members)
        val body = fake.bodiesPosted().single()
        for (kept in listOf("\"Overview\":\"kept overview\"", "\"ProviderIds\":{\"Tmdb\":\"7\"}", "\"LockData\":false", "\"Tags\":[\"a\"]", "\"Type\":\"BoxSet\"", "\"Name\":\"Collection A renamed\""))
            assertTrue(kept in body, "$kept missing from $body")
        assertTrue(fake.requests().any { it == "GET /Items/box-1?userId=user-1" }, fake.requests().toString())
    }

    @Test
    fun `rename never touches a title`() = runBlocking {
        assertEquals(false, client.renameCollection(fake.url, "key", "user-1", "film-a", "Renamed"))
        assertEquals(emptyList(), fake.bodiesPosted())
        assertEquals("Film A", fake.items().first { it.id == "film-a" }.name)
    }
}
