package dev.jellystructure.server.routes

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionData
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.FakeJellyfinCollections
import dev.jellystructure.media.JellyfinCollections
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.collectionSuggestions
import dev.jellystructure.model.JellyfinCollectionDto
import dev.jellystructure.model.JellyfinCollectionSuggestionDto
import dev.jellystructure.model.JellyfinCollectionsPageDto
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DEB-4075 (DEB-4032, FR-5..FR-7) — the admin's Jellyfin collections routes against a fake Jellyfin 12.1: every write answers the state
 * re-read from Jellyfin, the refusals (400, 409, 502) leave Jellyfin as it was, a delete can never reach a title, and a suggestion is
 * a franchise nothing covers yet. Stand-in names only.
 */
class JellyfinCollectionsRoutesTest {
    private val dir = "/tmp/jellystructure-test-jf-collections-routes-${getpid()}"
    private val fake = FakeJellyfinCollections()
    private val json = Json { ignoreUnknownKeys = true }
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
        for (id in listOf("jf-a", "jf-b", "jf-c", "jf-d", "jf-e", "jf-c1", "jf-d1")) fake.film(id)
        fake.series("jf-s")
        store.addOrUpdate(title("title-a", MediaKind.MOVIE, "jf-a"))
        store.addOrUpdate(title("title-b", MediaKind.MOVIE, "jf-b"))
        store.addOrUpdate(title("title-s", MediaKind.TV_SHOW, "jf-s"))
        store.addOrUpdate(title("title-nojf", MediaKind.MOVIE, null))
        // Franchise B: three films. Franchise C: one film. Franchise D: two films, one of which Jellyfin does not know.
        store.addOrUpdate(title("f-b1", MediaKind.MOVIE, "jf-c", 1, "Franchise B Collection", 2001))
        store.addOrUpdate(title("f-b2", MediaKind.MOVIE, "jf-d", 1, "Franchise B Collection", 2003))
        store.addOrUpdate(title("f-b3", MediaKind.MOVIE, "jf-e", 1, "Franchise B Collection", 2005))
        store.addOrUpdate(title("f-c1", MediaKind.MOVIE, "jf-c1", 2, "Franchise C Collection", 2010))
        store.addOrUpdate(title("f-d1", MediaKind.MOVIE, "jf-d1", 3, "Franchise D Collection", 2011))
        store.addOrUpdate(title("f-d2", MediaKind.MOVIE, null, 3, "Franchise D Collection", 2012))
        cache.refresh()
        Unit
    }

    @AfterTest fun tearDown() {
        fake.stop()
        dev.jellystructure.db.closeLastDatabaseForTests()
        platform.posix.system("rm -rf '$dir'")
    }

    private fun title(id: String, kind: MediaKind, jellyfinId: String?, franchise: Int? = null, franchiseName: String? = null, year: Int = 2020) = MediaItem(
        id = id, title = id, year = year, kind = kind, path = "/lib/$id.mkv", tmdbId = null, originalLanguage = "en", posterPath = null,
        overview = null, issueCount = 0, scannedAt = 0L, jellyfinId = jellyfinId, tracks = emptyList(), collectionId = franchise, collectionName = franchiseName,
    )

    private fun api(block: suspend HttpClient.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(json) }
            intercept(ApplicationCallPipeline.Plugins) { call.attributes.put(SessionKey, SessionData("t", "u-root", "root", "jf", Long.MAX_VALUE)) }
            routing { route("/api") { jellyfinCollectionsRoutes(cache, store, configStore, JellyfinClient()) } }
        }
        client.block()
    }

    private suspend fun HttpClient.send(method: String, path: String, body: String? = null): Pair<HttpStatusCode, String> {
        val r = when (method) {
            "GET" -> get(path)
            "DELETE" -> delete(path)
            "PATCH" -> patch(path) { contentType(ContentType.Application.Json); setBody(body ?: "{}") }
            else -> post(path) { contentType(ContentType.Application.Json); setBody(body ?: "{}") }
        }
        return r.status to r.bodyAsText()
    }

    private fun one(body: String) = json.decodeFromString(JellyfinCollectionDto.serializer(), body)
    private fun page(body: String) = json.decodeFromString(JellyfinCollectionsPageDto.serializer(), body)
    private fun error(body: String) = json.parseToJsonElement(body).let { (it as kotlinx.serialization.json.JsonObject)["error"]!!.let { e -> (e as kotlinx.serialization.json.JsonPrimitive).content } }

    private suspend fun HttpClient.list() = page(send("GET", "/api/jellyfin-collections").second).collections
    private suspend fun HttpClient.suggestions() =
        json.decodeFromString<List<JellyfinCollectionSuggestionDto>>(send("GET", "/api/jellyfin-collections/suggestions").second)

    // ---- FR-5 create, validation ----------------------------------------------------------------------------------------------

    @Test fun `create makes the collection in Jellyfin and answers what Jellyfin now holds`() = api {
        val (status, body) = send("POST", "/api/jellyfin-collections", """{"name":"  Collection A ","items":["title-a","title-s","title-a"]}""")
        assertEquals(HttpStatusCode.Created, status)
        val c = one(body)
        assertEquals("Collection A", c.name, "trimmed")
        assertEquals(listOf("title-a", "title-s"), c.members.map { it.id }, "named by media id, the repeat once")
        assertEquals(0, c.unmappedCount)
        assertEquals(listOf("jf-a", "jf-s"), fake.items().first { it.id == c.id }.members, "Jellyfin holds the Jellyfin ids")
        assertEquals(listOf(c), list())
    }

    @Test fun `a blank name a name over 200 characters an unknown title and a title Jellyfin lacks each answer 400 and create nothing`() = api {
        assertEquals(HttpStatusCode.BadRequest, send("POST", "/api/jellyfin-collections", """{"name":"   ","items":[]}""").first)
        assertEquals(HttpStatusCode.Created, send("POST", "/api/jellyfin-collections", """{"name":"${"x".repeat(200)}"}""").first, "200 is allowed")
        val long = send("POST", "/api/jellyfin-collections", """{"name":"${"y".repeat(201)}"}""")
        assertEquals(HttpStatusCode.BadRequest, long.first)
        val unknown = send("POST", "/api/jellyfin-collections", """{"name":"Collection A","items":["title-a","no-such-title"]}""")
        assertEquals(HttpStatusCode.BadRequest, unknown.first)
        assertTrue("no-such-title" in error(unknown.second), "names the id: ${unknown.second}")
        val lacking = send("POST", "/api/jellyfin-collections", """{"name":"Collection A","items":["title-nojf"]}""")
        assertEquals(HttpStatusCode.BadRequest, lacking.first)
        assertTrue("title-nojf" in error(lacking.second))
        assertEquals(listOf("x".repeat(200)), list().map { it.name }, "only the 200-character one was made")
    }

    @Test fun `a duplicate name answers 409 whatever its case and sends nothing to Jellyfin`() = api {
        send("POST", "/api/jellyfin-collections", """{"name":"Collection A"}""")
        val before = fake.requests().size
        val (status, body) = send("POST", "/api/jellyfin-collections", """{"name":"collection a"}""")
        assertEquals(HttpStatusCode.Conflict, status)
        assertEquals("A collection called \"collection a\" already exists.", error(body))
        assertEquals(before, fake.requests().size)
        assertEquals(1, list().size)
    }

    // ---- rename, add, remove -------------------------------------------------------------------------------------------------

    @Test fun `rename reads as the signed-in admin answers the re-read name and a name another collection has answers 409`() = api {
        val a = one(send("POST", "/api/jellyfin-collections", """{"name":"Collection A","items":["title-a"]}""").second)
        val b = one(send("POST", "/api/jellyfin-collections", """{"name":"Collection B"}""").second)
        val (status, body) = send("PATCH", "/api/jellyfin-collections/${a.id}", """{"name":"Collection A2"}""")
        assertEquals(HttpStatusCode.OK, status)
        assertEquals("Collection A2", one(body).name)
        assertEquals(listOf("title-a"), one(body).members.map { it.id }, "the members are kept")
        assertTrue(fake.requests().any { it.startsWith("GET /Items/${a.id}?userId=u-root") }, "the admin's Jellyfin user id: ${fake.requests()}")
        assertEquals(HttpStatusCode.Conflict, send("PATCH", "/api/jellyfin-collections/${b.id}", """{"name":"collection a2"}""").first)
        assertEquals("Collection B", list().first { it.id == b.id }.name)
        assertEquals(HttpStatusCode.OK, send("PATCH", "/api/jellyfin-collections/${b.id}", """{"name":"COLLECTION B"}""").first, "its own name in another case is not a duplicate")
        assertEquals(HttpStatusCode.NotFound, send("PATCH", "/api/jellyfin-collections/nope", """{"name":"X"}""").first)
    }

    @Test fun `add and remove each answer the state re-read from Jellyfin`() = api {
        val c = one(send("POST", "/api/jellyfin-collections", """{"name":"Collection A","items":["title-a"]}""").second)
        val added = one(send("POST", "/api/jellyfin-collections/${c.id}/items", """{"items":["title-b","title-s"]}""").second)
        assertEquals(listOf("title-a", "title-b", "title-s"), added.members.map { it.id })
        // A member added in Jellyfin's own dashboard comes back too: the answer is Jellyfin's, not the request's.
        fake.setMembers(c.id, "jf-a", "jf-b", "jf-s", "jf-c")
        val removed = send("DELETE", "/api/jellyfin-collections/${c.id}/items/title-b")
        assertEquals(HttpStatusCode.OK, removed.first)
        assertEquals(listOf("title-a", "title-s", "f-b1"), one(removed.second).members.map { it.id })
        val unknown = send("DELETE", "/api/jellyfin-collections/${c.id}/items/no-such-title")
        assertEquals(HttpStatusCode.BadRequest, unknown.first)
        assertTrue("no-such-title" in error(unknown.second))
        assertEquals(HttpStatusCode.BadRequest, send("POST", "/api/jellyfin-collections/${c.id}/items", """{"items":["nope"]}""").first)
        assertEquals(HttpStatusCode.NotFound, send("POST", "/api/jellyfin-collections/nope/items", """{"items":["title-a"]}""").first)
    }

    @Test fun `the list names each collection's mapped members and counts the ones jellystructure does not hold`() = api {
        fake.film("jf-skipped")
        fake.collection("box-x", "Collection X", "jf-a", "jf-skipped")
        cache.refresh()
        val c = list().single()
        assertEquals("Collection X", c.name)
        assertEquals(listOf("title-a"), c.members.map { it.id })
        assertEquals("title-a", c.members.single().title)
        assertEquals(1, c.unmappedCount)
        assertTrue(page(send("GET", "/api/jellyfin-collections").second).configured)
    }

    @Test fun `with no Jellyfin set up the list says so and a write answers 502`() = api {
        configStore.update(configStore.current.copy(apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = "", jellyfinToken = "")))
        val before = fake.requests()
        assertEquals(false, page(send("GET", "/api/jellyfin-collections").second).configured)
        assertEquals(HttpStatusCode.BadGateway, send("POST", "/api/jellyfin-collections", """{"name":"Collection A"}""").first)
        assertEquals(before, fake.requests(), "nothing is asked of Jellyfin")
    }

    // ---- FR-5 / Acceptance 3 — a Jellyfin failure --------------------------------------------------------------------------

    @Test fun `a Jellyfin failure answers 502 with a sentence and the page still reads the old state`() = api {
        val c = one(send("POST", "/api/jellyfin-collections", """{"name":"Collection A","items":["title-a"]}""").second)
        val before = list()
        fake.failWrites = true
        for ((method, path, body) in listOf(
            Triple("POST", "/api/jellyfin-collections", """{"name":"Collection B"}"""),
            Triple("PATCH", "/api/jellyfin-collections/${c.id}", """{"name":"Renamed"}"""),
            Triple("POST", "/api/jellyfin-collections/${c.id}/items", """{"items":["title-b"]}"""),
            Triple("DELETE", "/api/jellyfin-collections/${c.id}/items/title-a", null),
            Triple("DELETE", "/api/jellyfin-collections/${c.id}", null),
        )) {
            val (status, answer) = send(method, path, body)
            assertEquals(HttpStatusCode.BadGateway, status, "$method $path")
            assertTrue(error(answer).isNotBlank(), "$method $path names what failed")
        }
        assertEquals(before, list(), "nothing changed")
    }

    // ---- FR-2 — a delete can never reach a title ---------------------------------------------------------------------------

    @Test fun `delete removes the collection and leaves every member title`() = api {
        val c = one(send("POST", "/api/jellyfin-collections", """{"name":"Collection A","items":["title-a","title-s"]}""").second)
        assertEquals(HttpStatusCode.NoContent, send("DELETE", "/api/jellyfin-collections/${c.id}").first)
        assertEquals(emptyList(), list())
        assertEquals(listOf("DELETE /Items/${c.id}"), fake.deletes(), "exactly one DELETE, and it is the collection's")
        assertTrue(fake.items().any { it.id == "jf-a" } && fake.items().any { it.id == "jf-s" }, "the film and the series stay in Jellyfin")
        assertTrue(store.get("title-a") != null && store.get("title-s") != null)
    }

    @Test fun `a film's id a series' id and an unknown id sent as a collection send no DELETE`() = api {
        fake.collection("box-1", "Collection A", "jf-a")
        cache.refresh()
        for (id in listOf("jf-a", "jf-s", "title-a", "no-such-id")) {
            assertEquals(HttpStatusCode.NotFound, send("DELETE", "/api/jellyfin-collections/$id").first, id)
        }
        assertEquals(emptyList(), fake.deletes())
        assertEquals(1, list().size)
    }

    // ---- FR-7 / Acceptance 6 — suggestions ---------------------------------------------------------------------------------

    @Test fun `a franchise of three held films is suggested in release order one of one film is not and a film Jellyfin lacks is not offered`() = api {
        val s = suggestions()
        assertEquals(listOf("Franchise B"), s.map { it.name }, "C has one film and D has one that Jellyfin knows")
        assertEquals(listOf("f-b1", "f-b2", "f-b3"), s.single().films.map { it.id })
        assertEquals(HttpStatusCode.OK, send("GET", "/api/jellyfin-collections/suggestions").first)
        assertEquals(emptyList(), fake.requests().filter { it.startsWith("POST") || it.startsWith("DELETE") }, "reading suggestions makes nothing")
    }

    @Test fun `creating a collection from a suggestion makes it with those films and the suggestion goes`() = api {
        val s = suggestions().single()
        val made = send("POST", "/api/jellyfin-collections", json.encodeToString(
            kotlinx.serialization.serializer<dev.jellystructure.model.CreateJellyfinCollectionRequest>(),
            dev.jellystructure.model.CreateJellyfinCollectionRequest(s.name, s.films.map { it.id })))
        assertEquals(HttpStatusCode.Created, made.first)
        assertEquals(listOf("f-b1", "f-b2", "f-b3"), one(made.second).members.map { it.id })
        assertEquals(emptyList(), suggestions())
    }

    @Test fun `a franchise is covered by a collection of its name in any case or by one that holds every film`() {
        fun covered(name: String, vararg members: String): Boolean {
            val fresh = FakeJellyfinCollections()
            try {
                for (id in listOf("jf-c", "jf-d", "jf-e", "jf-x")) fresh.film(id)
                fresh.collection("box-1", name, *members)
                runBlocking {
                    configStore.update(configStore.current.copy(apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = fresh.url)))
                    cache.refresh()
                }
                return runBlocking { collectionSuggestions(store.allItems(), cache.current()) }.none { it.name == "Franchise B" }
            } finally { fresh.stop() }
        }
        assertTrue(covered("franchise b"), "its display name, case-insensitive")
        assertTrue(covered("Anything", "jf-c", "jf-d", "jf-e", "jf-x"), "holds all three, and more")
        assertTrue(!covered("Anything", "jf-c", "jf-d"), "two of three is not covered")
        assertTrue(!covered("Franchise B Extras"), "a different name is not")
    }
}
