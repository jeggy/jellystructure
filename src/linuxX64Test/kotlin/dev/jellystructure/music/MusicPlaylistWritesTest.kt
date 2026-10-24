package dev.jellystructure.music

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.DeviceKey
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.Screengrabber
import dev.jellystructure.server.routes.musicTvRoutes
import dev.jellystructure.shared.tv.MusicList
import dev.jellystructure.shared.tv.MusicPlaylist
import dev.jellystructure.shared.tv.MusicPlaylistAdded
import dev.jellystructure.tmdb.TmdbClient
import dev.jellystructure.tv.PlaybackQoeStore
import dev.jellystructure.tv.PlaybackService
import dev.jellystructure.tv.PlaybackStartSampleStore
import dev.jellystructure.tv.RaviloDeviceService
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * DEB-2254 (FR-1, FR-2, FR-3, FR-5, FR-9..FR-12, FR-15) — the playlist writes, against a fake Jellyfin that applies the
 * rules the real one does (owner-only `GET /Playlists/{id}/Users`, owner-only writes, no de-duplication on add) and
 * records every request with the token it carried. The library is the 305/R373 stand-ins: *Northern Line* is held three
 * times (`kw-2`, `s-nl-1`, `tt-1`), and `flac-1` sits in a library only some viewers may open.
 */
internal class PlaylistFakeJellyfin(val serverKey: String) {
    class Pl(val id: String, var name: String, val owner: String, val entries: MutableList<Pair<String, String>> = mutableListOf())

    val playlists = LinkedHashMap<String, Pl>()
    /** token → Jellyfin user id; a token not in here is dead (401 on `/Users/{id}`). */
    val tokens = HashMap<String, String>()
    /** Every request that is not a GET: `"POST /Playlists/p1/Items?ids=kw-3"` and the token it carried. */
    val mutations = ArrayList<Pair<String, String?>>()
    /** The token of every `GET /Playlists/{id}/Users`. */
    val ownerChecks = ArrayList<Pair<String, String?>>()
    private var next = 0

    fun playlist(id: String, name: String, owner: String, vararg items: String) =
        Pl(id, name, owner, items.map { "e${++next}" to it }.toMutableList()).also { playlists[id] = it }

    fun mutationsOf(prefix: String) = mutations.filter { it.first.startsWith(prefix) }

    private fun tokenOf(authorization: String?): String? = Regex("Token=\"([^\"]+)\"").find(authorization.orEmpty())?.groupValues?.get(1)

    fun start() = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
        routing {
            get("/Users/{id}") {
                if (tokenOf(call.request.headers["Authorization"]) in tokens) call.respondText("{}", ContentType.Application.Json) else call.respond(HttpStatusCode.Unauthorized)
            }
            get("/Items") {
                val q = call.request.queryParameters
                val body = if (q["IncludeItemTypes"] == "Playlist")
                    playlists.values.joinToString(",", """{"Items":[""", "]}") { """{"Id":"${it.id}","Name":"${it.name}","ChildCount":${it.entries.size},"MediaType":"Audio"}""" }
                else """{"Items":[]}"""
                call.respondText(body, ContentType.Application.Json)
            }
            get("/Playlists/{id}/Users") {
                val token = tokenOf(call.request.headers["Authorization"])
                ownerChecks += call.parameters["id"]!! to token
                val pl = playlists[call.parameters["id"]!!] ?: return@get call.respond(HttpStatusCode.NotFound)
                if (tokens[token] == pl.owner) call.respondText("[]", ContentType.Application.Json) else call.respond(HttpStatusCode.Forbidden)
            }
            get("/Playlists/{id}/Items") {
                val pl = playlists[call.parameters["id"]!!] ?: return@get call.respond(HttpStatusCode.NotFound)
                val limit = call.request.queryParameters["Limit"]?.toIntOrNull() ?: Int.MAX_VALUE
                call.respondText(pl.entries.take(limit).joinToString(",", """{"Items":[""", "]}") { (e, i) -> """{"Id":"$i","Type":"Audio","PlaylistItemId":"$e"}""" }, ContentType.Application.Json)
            }
            post("/Playlists") {
                val token = tokenOf(call.request.headers["Authorization"])
                val text = call.receiveText()
                mutations += "POST /Playlists $text" to token
                val body = Json.parseToJsonElement(text).jsonObject
                val id = "pl-new-${playlists.size}"
                val pl = Pl(id, body["Name"]!!.jsonPrimitive.content, tokens[token] ?: return@post call.respond(HttpStatusCode.Unauthorized))
                body["Ids"]!!.jsonArray.forEach { pl.entries += "e${++next}" to it.jsonPrimitive.content }
                playlists[id] = pl
                call.respondText("""{"Id":"$id"}""", ContentType.Application.Json)
            }
            post("/Playlists/{id}") {
                val token = tokenOf(call.request.headers["Authorization"])
                val text = call.receiveText()
                mutations += "POST /Playlists/${call.parameters["id"]} $text" to token
                val pl = playlists[call.parameters["id"]!!] ?: return@post call.respond(HttpStatusCode.NotFound)
                if (tokens[token] != pl.owner) return@post call.respond(HttpStatusCode.Forbidden)
                pl.name = Json.parseToJsonElement(text).jsonObject["Name"]!!.jsonPrimitive.content
                call.respond(HttpStatusCode.NoContent)
            }
            post("/Playlists/{id}/Items") {
                val token = tokenOf(call.request.headers["Authorization"])
                val ids = call.request.queryParameters["ids"].orEmpty()
                mutations += "POST /Playlists/${call.parameters["id"]}/Items?ids=$ids" to token
                val pl = playlists[call.parameters["id"]!!] ?: return@post call.respond(HttpStatusCode.NotFound)
                if (tokens[token] != pl.owner) return@post call.respond(HttpStatusCode.Forbidden)
                ids.split(',').filter { it.isNotEmpty() }.forEach { pl.entries += "e${++next}" to it }
                call.respond(HttpStatusCode.NoContent)
            }
            delete("/Playlists/{id}/Items") {
                val token = tokenOf(call.request.headers["Authorization"])
                val entry = call.request.queryParameters["entryIds"].orEmpty()
                mutations += "DELETE /Playlists/${call.parameters["id"]}/Items?entryIds=$entry" to token
                val pl = playlists[call.parameters["id"]!!] ?: return@delete call.respond(HttpStatusCode.NotFound)
                if (tokens[token] != pl.owner) return@delete call.respond(HttpStatusCode.Forbidden)
                pl.entries.removeAll { it.first == entry }
                call.respond(HttpStatusCode.NoContent)
            }
            post("/Playlists/{id}/Items/{entry}/Move/{index}") {
                val token = tokenOf(call.request.headers["Authorization"])
                mutations += "POST /Playlists/${call.parameters["id"]}/Items/${call.parameters["entry"]}/Move/${call.parameters["index"]}" to token
                val pl = playlists[call.parameters["id"]!!] ?: return@post call.respond(HttpStatusCode.NotFound)
                if (tokens[token] != pl.owner) return@post call.respond(HttpStatusCode.Forbidden)
                val moved = pl.entries.first { it.first == call.parameters["entry"] }
                pl.entries.remove(moved)
                pl.entries.add(call.parameters["index"]!!.toInt().coerceIn(0, pl.entries.size), moved)
                call.respond(HttpStatusCode.NoContent)
            }
            delete("/Items/{id}") {
                val token = tokenOf(call.request.headers["Authorization"])
                mutations += "DELETE /Items/${call.parameters["id"]}" to token
                val pl = playlists[call.parameters["id"]!!] ?: return@delete call.respond(HttpStatusCode.NotFound)
                if (tokens[token] != pl.owner) return@delete call.respond(HttpStatusCode.Forbidden)
                playlists.remove(pl.id)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }.start(wait = false)
}

class MusicPlaylistWritesTest : MusicTvEditionsBase("playlist-writes") {
    private val run = getpid()
    private val serverKey = "server-key-$run"
    private val ownerTok = "tok-owner-$run"
    private val otherTok = "tok-other-$run"
    private val deadTok = "tok-dead-$run"

    private fun viewer(id: String, user: String, token: String, allowed: Set<String>? = null) =
        DeviceData("dev-$id", "dt-$id", user, id, token, isAdmin = false, allowedLibraries = allowed, kind = "phone")
    private val owner = viewer("owner", "u-owner", ownerTok)
    private val ownerRestricted = viewer("owner-r", "u-owner", ownerTok, setOf(EditionsFixture.LIB))
    private val other = viewer("other", "u-other", otherTok)
    private val dead = viewer("dead", "u-owner", deadTok)

    private fun <T> withJellyfin(block: suspend (PlaylistFakeJellyfin) -> T): T = runBlocking {
        val fake = PlaylistFakeJellyfin(serverKey)
        fake.tokens[ownerTok] = "u-owner"; fake.tokens[otherTok] = "u-other"
        val server = fake.start()
        val port = server.engine.resolvedConnectors().first().port
        config.update(config.current.copy(apiKeys = config.current.apiKeys.copy(jellyfinUrl = "http://127.0.0.1:$port", jellyfinToken = serverKey)))
        try { block(fake) } finally { server.stop(100, 500) }
    }

    private fun <T> done(r: PlaylistWrite<T>): T = (r as? PlaylistWrite.Done)?.value ?: fail("refused: ${(r as PlaylistWrite.Refused).why}")
    private fun refusal(r: PlaylistWrite<*>): PlaylistRefusal = (r as? PlaylistWrite.Refused)?.why ?: fail("expected a refusal, got ${(r as PlaylistWrite.Done<*>).value}")

    @Test
    fun every_write_runs_as_the_viewer_and_never_with_the_server_key() = withJellyfin { fake ->
        fake.playlist("own", "Mine", "u-owner", "kw-1", "kw-2")
        val created = done(svc.createPlaylist(owner, "Stand-in Mix", listOf("kw-3")))
        done(svc.addToPlaylist(owner, "own", listOf("kw-4")))
        val first = fake.playlists["own"]!!.entries.first().first
        done(svc.movePlaylistEntry(owner, "own", first, 1))
        done(svc.removeFromPlaylist(owner, "own", first))
        done(svc.renamePlaylist(owner, "own", "Renamed"))
        done(svc.deletePlaylist(owner, created.id))
        assertEquals(6, fake.mutations.size, fake.mutations.toString())
        assertTrue(fake.mutations.all { it.second == ownerTok }, "every write carried the viewer's own token: ${fake.mutations}")
        assertTrue(fake.ownerChecks.all { it.second == ownerTok })
    }

    @Test
    fun mine_is_set_from_the_owner_check() = withJellyfin { fake ->
        fake.playlist("own", "Mine", "u-owner", "kw-1")
        fake.playlist("theirs", "Shared with me", "u-other", "kw-2")
        assertEquals(mapOf("own" to true, "theirs" to false), svc.playlists(owner).associate { it.id to it.mine })
        assertEquals(mapOf("own" to false, "theirs" to true), svc.playlists(other).associate { it.id to it.mine })
        assertTrue(svc.playlist(owner, "own")!!.mine)
        assertFalse(svc.playlist(owner, "theirs")!!.mine)
    }

    @Test
    fun the_owner_check_runs_once_per_playlist_per_read_and_again_after_a_write() = withJellyfin { fake ->
        fake.playlist("a", "A", "u-owner"); fake.playlist("b", "B", "u-other")
        svc.playlists(owner)
        assertEquals(listOf("a", "b"), fake.ownerChecks.map { it.first })
        svc.playlists(owner)
        assertEquals(2, fake.ownerChecks.size, "the second read is served from the cache")
        svc.forget(owner)
        svc.playlists(owner)
        assertEquals(4, fake.ownerChecks.size, "forget(device) clears it")
    }

    @Test
    fun a_rejected_token_reads_mine_false_without_asking_and_still_lists() = withJellyfin { fake ->
        fake.playlist("own", "Mine", "u-owner", "kw-1")
        val list = svc.playlists(dead)
        assertEquals(listOf("own"), list.map { it.id })
        assertFalse(list.single().mine)
        assertFalse(svc.playlist(dead, "own")!!.mine)
        assertTrue(fake.ownerChecks.isEmpty(), "no owner check without the viewer's token")
    }

    @Test
    fun a_playlist_read_carries_each_rows_entry_id_and_a_list_row_elsewhere_does_not() = withJellyfin { fake ->
        fake.playlist("own", "Mine", "u-owner", "kw-1", "kw-3")
        val rows = svc.playlist(owner, "own")!!.tracks
        assertEquals(fake.playlists["own"]!!.entries.map { it.first }, rows.map { it.entryId })
        assertEquals(null, svc.album(owner, "kw")!!.tracks.first().entryId)
    }

    @Test
    fun add_drops_what_the_playlist_holds_its_copies_and_repeats() = withJellyfin { fake ->
        fake.playlist("own", "Mine", "u-owner", "kw-2")
        // s-nl-1 and tt-1 are other copies of the held song, the second kw-3 is a repeat.
        val r = done(svc.addToPlaylist(owner, "own", listOf("s-nl-1", "kw-3", "kw-3", "kw-4", "tt-1")))
        assertEquals(MusicPlaylistAdded(added = 2, already = 3), r)
        assertEquals(listOf("kw-2", "kw-3", "kw-4"), fake.playlists["own"]!!.entries.map { it.second })
        assertEquals(1, fake.mutationsOf("POST /Playlists/own/Items").size)
        // Again: nothing is left, so nothing is sent.
        val again = done(svc.addToPlaylist(owner, "own", listOf("kw-3", "tt-1")))
        assertEquals(MusicPlaylistAdded(added = 0, already = 2), again)
        assertEquals(1, fake.mutationsOf("POST /Playlists/own/Items").size, "no second Jellyfin call")
    }

    @Test
    fun add_keeps_the_first_of_several_copies_inside_item_ids() = withJellyfin { fake ->
        fake.playlist("own", "Mine", "u-owner")
        val r = done(svc.addToPlaylist(owner, "own", listOf("s-nl-1", "tt-1", "kw-2")))
        assertEquals(MusicPlaylistAdded(added = 1, already = 2), r)
        assertEquals(listOf("s-nl-1"), fake.playlists["own"]!!.entries.map { it.second })
    }

    @Test
    fun a_copy_the_viewer_cannot_open_is_not_the_copy_that_counts() = withJellyfin { fake ->
        // flac-1 is in a library this viewer may not open, so for them kw-1 is the song's one copy.
        fake.playlist("own", "Mine", "u-owner", "kw-1")
        val r = done(svc.addToPlaylist(ownerRestricted, "own", listOf("kw-1")))
        assertEquals(MusicPlaylistAdded(0, 1), r)
        assertEquals(refusal(svc.addToPlaylist(ownerRestricted, "own", listOf("flac-1"))), PlaylistRefusal.BAD_REQUEST, "a song they cannot open is not theirs to add")
    }

    @Test
    fun add_answers_400_for_an_id_that_is_not_a_song_the_viewer_may_open() = withJellyfin { fake ->
        fake.playlist("own", "Mine", "u-owner")
        assertEquals(PlaylistRefusal.BAD_REQUEST, refusal(svc.addToPlaylist(owner, "own", listOf("kw-1", "nope"))))
        assertEquals(PlaylistRefusal.BAD_REQUEST, refusal(svc.createPlaylist(owner, "X", listOf("nope"))))
        assertTrue(fake.mutations.isEmpty())
    }

    @Test
    fun create_trims_deduplicates_and_answers_the_playlist_as_mine() = withJellyfin { fake ->
        val p = done(svc.createPlaylist(owner, "  Stand-in Mix  ", listOf("kw-3", "kw-3", "s-nl-1", "tt-1")))
        assertEquals("Stand-in Mix", p.name)
        assertEquals(2, p.trackCount)
        assertTrue(p.mine)
        val sent = Json.parseToJsonElement(fake.mutations.single().first.removePrefix("POST /Playlists ")).jsonObject
        assertEquals("Stand-in Mix", sent["Name"]!!.jsonPrimitive.content)
        assertEquals(listOf("kw-3", "s-nl-1"), sent["Ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("u-owner", sent["UserId"]!!.jsonPrimitive.content)
        assertEquals("Audio", sent["MediaType"]!!.jsonPrimitive.content)
        assertEquals(listOf(p.id), svc.playlists(owner).filter { it.mine }.map { it.id })
    }

    @Test
    fun a_name_is_one_to_a_hundred_characters_after_trimming() = withJellyfin { fake ->
        fake.playlist("own", "Mine", "u-owner")
        for (bad in listOf("", "   ", "x".repeat(101))) {
            assertEquals(PlaylistRefusal.BAD_REQUEST, refusal(svc.createPlaylist(owner, bad, emptyList())), "create '$bad'")
            assertEquals(PlaylistRefusal.BAD_REQUEST, refusal(svc.renamePlaylist(owner, "own", bad)), "rename '$bad'")
        }
        assertEquals(100, done(svc.createPlaylist(owner, "x".repeat(100), emptyList())).name.length)
        assertEquals(1, fake.mutations.size, "only the valid create went out")
    }

    @Test
    fun remove_answers_the_playlist_as_it_now_stands() = withJellyfin { fake ->
        fake.playlist("own", "Mine", "u-owner", "kw-1", "kw-3", "kw-4")
        val gone = fake.playlists["own"]!!.entries[1].first
        val list = done(svc.removeFromPlaylist(owner, "own", gone))
        assertEquals(listOf("kw-1", "kw-4"), list.tracks.map { it.id })
        assertTrue(list.mine)
        assertEquals(fake.playlists["own"]!!.entries.map { it.first }, list.tracks.map { it.entryId })
        assertEquals(PlaylistRefusal.NOT_FOUND, refusal(svc.removeFromPlaylist(owner, "own", gone)), "an entry the playlist does not hold")
        assertEquals(1, fake.mutations.size)
    }

    @Test
    fun move_puts_the_row_where_it_was_dropped_in_the_list_as_shown() = withJellyfin { fake ->
        // `hidden` is a song the viewer cannot see, so the list they are shown is kw-1, kw-3, kw-4.
        fake.playlist("own", "Mine", "u-owner", "kw-1", "hidden", "kw-3", "kw-4")
        fun entry(item: String) = fake.playlists["own"]!!.entries.first { it.second == item }.first
        fun order() = fake.playlists["own"]!!.entries.map { it.second }

        assertEquals(listOf("kw-4", "kw-1", "kw-3"), done(svc.movePlaylistEntry(owner, "own", entry("kw-4"), 0)).tracks.map { it.id })
        assertEquals(listOf("kw-4", "kw-1", "hidden", "kw-3"), order(), "in front of the row now first")

        assertEquals(listOf("kw-4", "kw-3", "kw-1"), done(svc.movePlaylistEntry(owner, "own", entry("kw-1"), 2)).tracks.map { it.id }, "to the end")
        assertEquals(listOf("kw-4", "hidden", "kw-3", "kw-1"), order(), "after the last row shown")

        assertEquals(listOf("kw-3", "kw-4", "kw-1"), done(svc.movePlaylistEntry(owner, "own", entry("kw-3"), 0)).tracks.map { it.id })
        assertEquals(listOf("kw-3", "kw-4", "hidden", "kw-1"), order())

        assertEquals(PlaylistRefusal.NOT_FOUND, refusal(svc.movePlaylistEntry(owner, "own", "e-none", 0)))
        assertEquals(PlaylistRefusal.BAD_REQUEST, refusal(svc.movePlaylistEntry(owner, "own", entry("kw-3"), -1)))
    }

    @Test
    fun rename_and_delete() = withJellyfin { fake ->
        fake.playlist("own", "Mine", "u-owner", "kw-1", "kw-3")
        val renamed = done(svc.renamePlaylist(owner, "own", "  Better  "))
        assertEquals("Better", renamed.name); assertEquals(2, renamed.trackCount); assertTrue(renamed.mine)
        assertEquals("Better", fake.playlists["own"]!!.name)
        assertEquals("""POST /Playlists/own {"Name":"Better"}""", fake.mutations.single().first, "Name only")

        done(svc.deletePlaylist(owner, "own"))
        assertTrue(fake.playlists.isEmpty())
        assertEquals(emptyList(), svc.playlists(owner))
    }

    @Test
    fun delete_never_reaches_an_item_that_is_not_a_playlist() = withJellyfin { fake ->
        assertEquals(PlaylistRefusal.NOT_FOUND, refusal(svc.deletePlaylist(owner, "kw-1")))
        assertTrue(fake.mutations.isEmpty())
    }

    @Test
    fun a_playlist_the_viewer_does_not_own_refuses_every_write_with_403_and_changes_nothing() = withJellyfin { fake ->
        fake.playlist("own", "Mine", "u-owner", "kw-1", "kw-3")
        val before = fake.playlists["own"]!!.entries.toList()
        val entry = before.first().first
        val results = listOf(
            svc.addToPlaylist(other, "own", listOf("kw-4")),
            svc.removeFromPlaylist(other, "own", entry),
            svc.movePlaylistEntry(other, "own", entry, 1),
            svc.renamePlaylist(other, "own", "Mine now"),
            svc.deletePlaylist(other, "own"),
        )
        results.forEach { assertEquals(PlaylistRefusal.NOT_OWNER, refusal(it)); assertEquals(HttpStatusCode.Forbidden, refusal(it).status) }
        assertTrue(fake.mutations.isEmpty(), "Jellyfin was never written to: ${fake.mutations}")
        assertEquals(before, fake.playlists["own"]!!.entries)
        // Creating one's own is not a write to someone else's.
        assertTrue(done(svc.createPlaylist(other, "Theirs", emptyList())).mine)
    }

    @Test
    fun a_missing_playlist_is_404() = withJellyfin { fake ->
        assertEquals(PlaylistRefusal.NOT_FOUND, refusal(svc.addToPlaylist(owner, "nope", listOf("kw-1"))))
        assertEquals(PlaylistRefusal.NOT_FOUND, refusal(svc.renamePlaylist(owner, "nope", "X")))
        assertTrue(fake.mutations.isEmpty())
    }

    @Test
    fun a_rejected_token_refuses_every_write_with_401_and_changes_nothing() = withJellyfin { fake ->
        fake.playlist("own", "Mine", "u-owner", "kw-1")
        val entry = fake.playlists["own"]!!.entries.first().first
        val results = listOf(
            svc.createPlaylist(dead, "X", listOf("kw-1")),
            svc.addToPlaylist(dead, "own", listOf("kw-4")),
            svc.removeFromPlaylist(dead, "own", entry),
            svc.movePlaylistEntry(dead, "own", entry, 0),
            svc.renamePlaylist(dead, "own", "X"),
            svc.deletePlaylist(dead, "own"),
        )
        results.forEach { assertEquals(HttpStatusCode.Unauthorized, refusal(it).status) }
        assertTrue(fake.mutations.isEmpty(), "the server key is never used for a write: ${fake.mutations}")
        assertTrue(fake.ownerChecks.isEmpty())
        assertEquals(setOf("own"), fake.playlists.keys)
    }

    // ── the routes ──

    @Serializable private data class OldPlaylist(val id: String, val name: String, @SerialName("track_count") val trackCount: Int = 0, val covers: List<String> = emptyList())
    @Serializable private data class OldRow(val id: String, val title: String, val favorite: Boolean = false)
    @Serializable private data class OldList(val tracks: List<OldRow> = emptyList(), val total: Int = 0, val page: Int = 0, @SerialName("page_size") val pageSize: Int = 0)

    private fun routes(fake: PlaylistFakeJellyfin, block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        val db = createDatabase("$base-routes.db")
        val jf = JellyfinClient()
        val media = MediaStore(db, JsTagStore("$base-tags.json"), config)
        val playback = PlaybackService(media, jf, config, PlaybackQoeStore(db), PlaybackStartSampleStore(db), RaviloDeviceService(db))
        val musicMedia = MusicMediaService(store, ArtworkDownloader(TmdbClient(config), Screengrabber()), config, jf, FanartTvClient { "" })
        val devices = mapOf("owner" to owner, "other" to other, "dead" to dead)
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing {
                route("/api") {
                    intercept(ApplicationCallPipeline.Plugins) { call.attributes.put(DeviceKey, devices.getValue(call.request.headers["X-Viewer"]!!)) }
                    musicTvRoutes(svc, playback, jf, config, store, musicMedia, null)
                }
            }
        }
        try { block() } finally { listOf("$base-routes.db", "$base-routes.db-wal", "$base-routes.db-shm", "$base-tags.json").forEach { runCatching { platform.posix.remove(it) } } }
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun the_routes_answer_the_statuses_of_FR_1_and_FR_13() = withJellyfin { fake ->
        fake.playlist("own", "Mine", "u-owner", "kw-1", "kw-3")
        val entry = fake.playlists["own"]!!.entries.first().first
        routes(fake) {
            suspend fun status(method: String, path: String, viewer: String, body: String? = null): HttpStatusCode {
                val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = { header("X-Viewer", viewer); body?.let { contentType(ContentType.Application.Json); setBody(it) } }
                return when (method) {
                    "POST" -> client.post("/api$path", build)
                    "PUT" -> client.put("/api$path", build)
                    else -> client.delete("/api$path", build)
                }.status
            }
            val calls = listOf(
                Triple("POST", "/tv/music/playlists", """{"name":"X"}"""),
                Triple("POST", "/tv/music/playlist/own/items", """{"item_ids":["kw-4"]}"""),
                Triple("DELETE", "/tv/music/playlist/own/items/$entry", null),
                Triple("POST", "/tv/music/playlist/own/move", """{"entry_id":"$entry","to":1}"""),
                Triple("PUT", "/tv/music/playlist/own", """{"name":"X"}"""),
                Triple("DELETE", "/tv/music/playlist/own", null),
            )
            for ((m, p, b) in calls) assertEquals(HttpStatusCode.Unauthorized, status(m, p, "dead", b), "$m $p with a rejected token")
            for ((m, p, b) in calls.drop(1)) assertEquals(HttpStatusCode.Forbidden, status(m, p, "other", b), "$m $p on a playlist that is not theirs")
            assertTrue(fake.mutations.isEmpty(), fake.mutations.toString())

            assertEquals(HttpStatusCode.BadRequest, status("PUT", "/tv/music/playlist/own", "owner", """{"name":" "}"""))
            assertEquals(HttpStatusCode.NotFound, status("PUT", "/tv/music/playlist/nope", "owner", """{"name":"X"}"""))

            val created = client.post("/api/tv/music/playlists") { header("X-Viewer", "owner"); contentType(ContentType.Application.Json); setBody("""{"name":"Fresh","item_ids":["kw-4"]}""") }
            assertEquals(HttpStatusCode.OK, created.status)
            val card = json.decodeFromString(MusicPlaylist.serializer(), created.bodyAsText())
            assertTrue(card.mine); assertEquals("Fresh", card.name); assertEquals(1, card.trackCount)

            val added = client.post("/api/tv/music/playlist/own/items") { header("X-Viewer", "owner"); contentType(ContentType.Application.Json); setBody("""{"item_ids":["kw-1","kw-4"]}""") }
            assertEquals(MusicPlaylistAdded(1, 1), json.decodeFromString(MusicPlaylistAdded.serializer(), added.bodyAsText()))

            val removed = client.delete("/api/tv/music/playlist/own/items/$entry") { header("X-Viewer", "owner") }
            assertEquals(listOf("kw-3", "kw-4"), json.decodeFromString(MusicList.serializer(), removed.bodyAsText()).tracks.map { it.id })

            assertEquals(HttpStatusCode.NoContent, status("DELETE", "/tv/music/playlist/${card.id}", "owner"))
            assertTrue(fake.mutations.all { it.second == ownerTok })
        }
    }

    @Test
    fun an_existing_playlist_read_still_decodes_with_an_old_shape_client() = withJellyfin { fake ->
        fake.playlist("own", "Mine", "u-owner", "kw-1", "kw-3")
        routes(fake) {
            val lists = client.get("/api/tv/music/playlists") { header("X-Viewer", "owner") }
            val old = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(OldPlaylist.serializer()), lists.bodyAsText())
            assertEquals(listOf("own" to "Mine"), old.map { it.id to it.name })
            assertTrue(""""mine":true""" in lists.bodyAsText())

            val page = client.get("/api/tv/music/playlist/own") { header("X-Viewer", "owner") }
            val oldPage = json.decodeFromString(OldList.serializer(), page.bodyAsText())
            assertEquals(listOf("kw-1", "kw-3"), oldPage.tracks.map { it.id })
            assertTrue(""""entry_id"""" in page.bodyAsText())
        }
    }
}
