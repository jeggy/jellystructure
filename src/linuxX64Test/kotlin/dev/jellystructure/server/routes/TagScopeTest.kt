package dev.jellystructure.server.routes

import dev.jellystructure.auth.SessionData
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.createDatabase
import dev.jellystructure.io.FileIo
import dev.jellystructure.media.JsTag
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.LogoDownloader
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.TagScope
import dev.jellystructure.tmdb.TmdbClient
import dev.jellystructure.torrent.QBittorrentClient
import dev.jellystructure.torrent.SeedingSnapshot
import dev.jellystructure.ui.tagChipWithScopeHtml
import dev.jellystructure.ui.tagGoneBadgeHtml
import dev.jellystructure.ui.tagGoneText
import dev.jellystructure.ui.tagLibraryHref
import dev.jellystructure.ui.tagOutsideNoticeText
import dev.jellystructure.ui.tagScopeLineHtml
import dev.jellystructure.ui.tagScopeRowHtml
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
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-3701 (FR-1–FR-4, FR-11 `scopes`) — a jellystructure tag has a scope; it is enforced on a newly added tag only,
 * narrowing it removes nothing, and the one explicit removal takes exactly the items outside it.
 */
class TagScopeTest {
    private val dir = "/tmp/jellystructure-test-tag-scope-${getpid()}"
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var db: JellystructureDb
    private lateinit var tags: JsTagStore
    private lateinit var store: MediaStore
    private lateinit var configStore: ConfigStore

    @BeforeTest
    fun setUp() {
        SystemFileSystem.createDirectories(Path(dir))
        db = createDatabase("$dir/test.db")
        configStore = ConfigStore("$dir/config.toml")
        tags = JsTagStore("$dir/js-tags.json")
        store = MediaStore(db, tags, configStore)
    }

    @AfterTest
    fun tearDown() {
        dev.jellystructure.db.closeLastDatabaseForTests()
        platform.posix.system("rm -rf '$dir'")
    }

    private fun film(id: String, kind: MediaKind = MediaKind.MOVIE, vararg itemTags: String) = MediaItem(
        id = id, title = id, year = 2020, kind = kind, path = "$dir/$id", tmdbId = null,
        originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L, tracks = emptyList(), tags = itemTags.toList(),
    )

    private fun api(block: suspend io.ktor.client.HttpClient.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(json) }
            intercept(ApplicationCallPipeline.Plugins) {
                call.attributes.put(SessionKey, SessionData("t", "u-root", "root", "jf", Long.MAX_VALUE))
            }
            routing {
                route("/api") {
                    metadataRoutes(store, tags, LogoDownloader(dir, TmdbClient(configStore)), SeedingSnapshot(configStore, QBittorrentClient()), configStore)
                }
            }
        }
        client.block()
    }

    private suspend fun io.ktor.client.HttpClient.send(method: String, path: String, body: String? = null): Pair<HttpStatusCode, String> {
        val r = when (method) {
            "GET" -> get(path)
            "PATCH" -> patch(path) { contentType(ContentType.Application.Json); setBody(body ?: "{}") }
            else -> post(path) { contentType(ContentType.Application.Json); setBody(body ?: "{}") }
        }
        return r.status to r.bodyAsText()
    }

    // ---- Acceptance 1 -------------------------------------------------------------------------------------------

    @Test fun `an older js-tags file loads every tag as All and is written back unchanged`() {
        // The bytes the store wrote before `scopes` existed: no such key, and a default colour left out.
        val old = """[{"name":"keep","color":"#7b6ef0","description":"The household's"},{"name":"plain","description":"Default colour"}]"""
        FileIo.writeText(Path("$dir/js-tags.json"), old)
        tags.load()
        assertTrue(tags.all().all { it.scopes.isEmpty() }, "every old tag is All")
        assertTrue(tags.all().all { TagScope.applies(it.scopes, "music") && TagScope.applies(it.scopes, "MOVIE") })
        // Any write persists the whole list; an edit that sends no scope must change nothing in the file.
        assertTrue(tags.update("keep", null, null, null))
        assertEquals(old, FileIo.readText(Path("$dir/js-tags.json")))
        assertTrue(tags.update("keep", "#7b6ef0", "The household's", null))
        assertEquals(old, FileIo.readText(Path("$dir/js-tags.json")))
    }

    @Test fun `a scoped tag round-trips and an edit can put it back to All`() {
        tags.create(JsTag("m", scopes = listOf("music", "audiobooks")))
        val fresh = JsTagStore("$dir/js-tags.json").also { it.load() }
        assertEquals(listOf("music", "audiobooks"), fresh.get("m")!!.scopes)
        assertTrue(fresh.update("m", null, null, emptyList()))
        assertTrue(fresh.get("m")!!.scopes.isEmpty())
    }

    // ---- the predicate, the 409 decision and the chip rules (Acceptance 2, 10) -----------------------------------

    @Test fun `one predicate decides - empty is All and otherwise the group must be named`() {
        assertTrue(TagScope.applies(emptyList(), "MOVIE"))
        assertTrue(TagScope.applies(listOf("music"), "music"))
        assertFalse(TagScope.applies(listOf("music"), "MOVIE"))
        assertEquals("All", TagScope.summary(emptyList()))
        assertEquals("Music · Audiobooks", TagScope.summary(listOf("audiobooks", "music")))
        assertNull(TagScope.normalize(listOf("podcasts")))
        assertEquals(listOf("MOVIE", "music"), TagScope.normalize(listOf("music", "MOVIE")))
    }

    @Test fun `only a newly added out-of-scope tag is refused and a kept tag or a removal never is`() {
        tags.create(JsTag("musical", scopes = listOf("music")))
        tags.create(JsTag("any"))
        val scopesOf = { t: String -> tags.get(t)?.scopes }
        assertEquals("musical is not used for Movies", TagScope.firstRefusal(emptyList(), listOf("musical"), "MOVIE", scopesOf))
        assertNull(TagScope.firstRefusal(emptyList(), listOf("any"), "MOVIE", scopesOf), "an All tag is fine anywhere")
        assertNull(TagScope.firstRefusal(emptyList(), listOf("not a js tag"), "MOVIE", scopesOf), "other tags are not this check's business")
        // The film already carries it: the whole list comes back on every edit, and the kept tag is not new.
        assertNull(TagScope.firstRefusal(listOf("musical"), listOf("musical", "any"), "MOVIE", scopesOf))
        // A removal.
        assertNull(TagScope.firstRefusal(listOf("musical", "any"), listOf("any"), "MOVIE", scopesOf))
        assertNull(TagScope.firstRefusal(emptyList(), listOf("musical"), "music", scopesOf), "in scope for music")
    }

    @Test fun `the modal's chip rules - All clears groups and a group clears All and the last untick is All`() {
        assertEquals(emptyList(), TagScope.toggle(listOf("MOVIE", "music"), null))
        assertEquals(listOf("MOVIE"), TagScope.toggle(emptyList(), "MOVIE"))
        assertEquals(listOf("MOVIE", "music"), TagScope.toggle(listOf("music"), "MOVIE"))
        assertEquals(listOf("music"), TagScope.toggle(listOf("MOVIE", "music"), "MOVIE"))
        assertEquals(emptyList(), TagScope.toggle(listOf("music"), "music"), "unticking the last group is All again")

        val all = tagScopeRowHtml(emptyList())
        assertTrue("""data-scope="" role="checkbox" aria-checked="true"""" in all)
        assertEquals(6, Regex("""<span class="mu-gc""").findAll(all).count(), "All + the five groups")
        assertEquals(1, Regex("""mu-gc on""").findAll(all).count(), "only All is ticked")
        // The order the spec gives.
        assertEquals(listOf("All", "Movies", "TV", "Music videos", "Music", "Audiobooks"),
            Regex("""</span>([A-Za-z ]+)</span>""").findAll(all).map { it.groupValues[1] }.toList())
        val two = tagScopeRowHtml(listOf("music", "MOVIE"))
        assertEquals(2, Regex("""mu-gc on""").findAll(two).count())
        assertTrue("""data-scope="" role="checkbox" aria-checked="false"""" in two, "All is not ticked next to a group")
        assertEquals("""<span class="tiny muted tag-scope-line">Movies · Music</span>""", tagScopeLineHtml(listOf("music", "MOVIE")))
    }

    @Test fun `a tag outside its scope keeps a removable dashed chip with the title`() {
        val plain = tagChipWithScopeHtml("t", "#fff", outOfScope = false)
        val out = tagChipWithScopeHtml("t", "#fff", outOfScope = true)
        assertFalse("outside this tag's scope" in plain)
        assertTrue("""title="outside this tag's scope"""" in out)
        assertTrue("border-style:dashed" in out)
        assertTrue("""class="rm tag-rm" data-tag="t"""" in out, "still has its ✕")
    }

    @Test fun `the modal's line before a save names the count by kind`() {
        assertEquals("4 items outside the new scope keep this tag: 3 films · 1 series",
            tagOutsideNoticeText(4, mapOf("MOVIE" to 3, "TV_SHOW" to 1)))
        assertEquals("1 item outside the new scope keeps this tag: 1 film", tagOutsideNoticeText(1, mapOf("MOVIE" to 1)))
        assertEquals("", tagOutsideNoticeText(0, emptyMap()))
    }

    // ---- the routes (Acceptance 3) -------------------------------------------------------------------------------

    @Test fun `scopes are accepted and returned and validated`() = api {
        assertEquals(HttpStatusCode.Created, send("POST", "/api/tags", """{"name":"t","scopes":["music","audiobooks"]}""").first)
        val (_, list) = send("GET", "/api/tags")
        assertEquals(listOf("music", "audiobooks"), json.parseToJsonElement(list).jsonArray.single().jsonObject["scopes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(HttpStatusCode.BadRequest, send("POST", "/api/tags", """{"name":"bad","scopes":["podcasts"]}""").first)
        assertEquals(HttpStatusCode.BadRequest, send("PATCH", "/api/tags/t", """{"scopes":["nope"]}""").first)
        assertEquals(HttpStatusCode.OK, send("PATCH", "/api/tags/t", """{"scopes":[]}""").first)
        val (_, meta) = send("GET", "/api/metadata/tags")
        val tag = json.parseToJsonElement(meta).jsonObject["jsTags"]!!.jsonArray.single().jsonObject
        // The test's Json leaves defaults out, as an absent key; absent or empty both read as All.
        assertTrue(tag["scopes"]?.jsonArray.orEmpty().isEmpty(), "back to All")
        assertEquals(0, tag["outsideScope"]?.jsonPrimitive?.int ?: 0)
    }

    @Test fun `narrowing removes nothing and the explicit removal takes exactly the items outside`() = api {
        tags.create(JsTag("t"))
        runBlocking {
            store.addOrUpdate(film("f1", MediaKind.MOVIE, "t", "other"))
            store.addOrUpdate(film("f2", MediaKind.MOVIE, "t"))
            store.addOrUpdate(film("f3", MediaKind.MOVIE, "t"))
            store.addOrUpdate(film("s1", MediaKind.TV_SHOW, "t"))
            store.addOrUpdate(film("v1", MediaKind.MUSIC_VIDEO, "t"))
            store.addOrUpdate(film("untagged", MediaKind.MOVIE))
        }
        // Before the save: the modal's preview for a candidate scope, nothing stored yet.
        val (_, preview) = send("GET", "/api/tags/t/outside-scope?scopes=MUSIC_VIDEO")
        val p = json.parseToJsonElement(preview).jsonObject
        assertEquals(4, p["outside"]!!.jsonPrimitive.int)
        assertEquals(3, p["byKind"]!!.jsonObject["MOVIE"]!!.jsonPrimitive.int)
        assertEquals(1, p["byKind"]!!.jsonObject["TV_SHOW"]!!.jsonPrimitive.int)
        assertEquals(0, json.parseToJsonElement(send("GET", "/api/tags/t/outside-scope").second).jsonObject["outside"]?.jsonPrimitive?.int ?: 0, "stored scope is still All")

        // The save: narrow All to Music videos.
        assertEquals(HttpStatusCode.OK, send("PATCH", "/api/tags/t", """{"scopes":["MUSIC_VIDEO"]}""").first)
        runBlocking {
            assertEquals(listOf("f1", "f2", "f3", "s1", "v1"), store.allItems().filter { "t" in it.tags }.map { it.id }.sorted(), "nothing was removed")
        }
        val (_, meta) = send("GET", "/api/metadata/tags")
        val card = json.parseToJsonElement(meta).jsonObject["jsTags"]!!.jsonArray.single().jsonObject
        assertEquals(5, card["count"]!!.jsonPrimitive.int, "the count still counts every item that carries it")
        assertEquals(4, card["outsideScope"]!!.jsonPrimitive.int)

        // The explicit removal.
        val (st, removed) = send("POST", "/api/tags/t/remove-outside-scope")
        assertEquals(HttpStatusCode.OK, st)
        assertEquals(4, json.parseToJsonElement(removed).jsonObject["removed"]!!.jsonPrimitive.int)
        runBlocking {
            assertEquals(listOf("v1"), store.allItems().filter { "t" in it.tags }.map { it.id }, "only the item inside the scope keeps it")
            assertEquals(listOf("other"), store.resolve("f1")!!.tags, "other tags on a removed item stay")
        }
        // Widening changes no item.
        send("PATCH", "/api/tags/t", """{"scopes":[]}""")
        runBlocking { assertEquals(listOf("v1"), store.allItems().filter { "t" in it.tags }.map { it.id }) }
    }

    /** DEB-3704 (FR-8, FR-9) — the tags page opens Library → Music for a tag used for Music only, and words the gone rows. */
    @Test
    fun a_music_only_tag_opens_the_music_library_and_the_gone_line_is_worded() {
        assertEquals("#/library?kind=music&mview=albums&f.tag=Live%20night", tagLibraryHref("Live%20night", listOf("music")))
        assertEquals("#/library?tags=Live%20night", tagLibraryHref("Live%20night", emptyList()))
        assertEquals("#/library?tags=Live%20night", tagLibraryHref("Live%20night", listOf("music", "MOVIE")))
        assertEquals("#/library?kind=audiobooks&f.tag=Live%20night", tagLibraryHref("Live%20night", listOf("audiobooks")))
        assertEquals("#/library?tags=Live%20night", tagLibraryHref("Live%20night", listOf("music", "audiobooks")))
        assertEquals("1 tagged song, album or book is no longer in the library", tagGoneText(1))
        assertEquals("3 tagged songs, albums or books are no longer in the library", tagGoneText(3))
        assertEquals("", tagGoneBadgeHtml("x", 0))
        assertTrue(tagGoneBadgeHtml("x", 2).contains("data-gone-tag=\"x\"") && tagGoneBadgeHtml("x", 2).contains("data-stop-click"))
    }
}
