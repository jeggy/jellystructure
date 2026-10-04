package dev.jellystructure.music

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 305 (test 22) and R373 (test 3) — what the phone is sent: one copy of every song, chosen per viewer. */
abstract class MusicTvEditionsBase(name: String) {
    protected val base = "/tmp/jellystructure-test-$name-${getpid()}"
    protected lateinit var store: MusicStore
    protected lateinit var config: ConfigStore
    protected lateinit var svc: MusicTvService
    protected fun device(allowed: Set<String>?, id: String = "d") = DeviceData(id, "tok-$id", "u1", "viewer", "", isAdmin = false, allowedLibraries = allowed, kind = "phone")
    protected val open = device(null)
    protected val onlyLib = device(setOf(EditionsFixture.LIB), "r")

    /** The stand-ins, plus a FLAC of *Kite Weather*'s title song in a library only some viewers may open. */
    protected fun library() = EditionsFixture.library().let { l ->
        l.copy(tracks = l.tracks + EditionsFixture.track("flac-1", "flac-album", 1, "Kite Weather", "kw-r1", bitrate = 900_000, codec = "flac").copy(libraryId = "lib-flac"),
            albums = l.albums + EditionsFixture.album("flac-album", "Kite Weather (Hi-Res)", 2006, rg = "rg-kw-hr").copy(libraryId = "lib-flac"))
    }

    @BeforeTest fun setUp() { runBlocking {
        listOf("$base.db", "$base.db-wal", "$base.db-shm").forEach { runCatching { platform.posix.remove(it) } }
        store = MusicStore(createDatabase("$base.db"))
        val l = library()
        store.replaceLibrary(MusicLibraryRows(EditionsFixture.LIB, l.artists, l.albums.filter { it.libraryId == EditionsFixture.LIB }, l.tracks.filter { it.libraryId == EditionsFixture.LIB }))
        store.replaceLibrary(MusicLibraryRows("lib-flac", emptyList(), l.albums.filter { it.libraryId == "lib-flac" }, l.tracks.filter { it.libraryId == "lib-flac" }))
        store.putFacts(l.facts)
        config = ConfigStore("$base.toml").also { it.load() }
        svc = MusicTvService(store, { null }, JellyfinClient(), config)
    } }

    @AfterTest fun tearDown() { listOf("$base.db", "$base.db-wal", "$base.db-shm", "$base.toml").forEach { runCatching { platform.posix.remove(it) } } }
}

class MusicTvEditionsTest : MusicTvEditionsBase("tv-editions") {
    @Test
    fun album_detail_carries_official_extras_singles_and_b_sides_in_order() = runBlocking<Unit> {
        val d = svc.album(open, "kw")!!
        assertEquals((1..11).map { "kw-$it" }, d.officialIds)
        assertEquals(listOf("kw-12"), d.extraIds)
        assertEquals("JP", d.editionCountry); assertNull(d.editionTitle)
        assertEquals(listOf("s-nl", "s-fb", "s-salt", "s-lt", "s-tw"), d.singles.map { it.id })
        assertEquals(13, d.bsideTracks.size)
        assertEquals("Northern Line", d.bsideTracks.first().album); assertEquals("s-nl", d.bsideTracks.first().albumId)
    }

    @Test
    fun tracks_keep_file_order_so_an_installed_app_shows_todays_page() = runBlocking<Unit> {
        // Lantern Swing sits sixth on the held disc: an installed app still lists the files in their order.
        store.putTracks(listOf(store.track("kw-12")!!.copy(position = 6), store.track("kw-6")!!.copy(position = 12)))
        val d = svc.album(open, "kw")!!
        assertEquals("kw-12", d.tracks[5].id)
        assertFalse("kw-12" in d.officialIds!!)
    }

    @Test
    fun an_unmatched_album_has_no_official_ids() = runBlocking<Unit> {
        assertNull(svc.album(open, "nn")!!.officialIds)
        assertTrue(svc.album(open, "nn")!!.extraIds.isEmpty())
    }

    @Test
    fun a_singles_page_carries_single_from() = runBlocking<Unit> {
        assertEquals("kw", svc.album(open, "s-nl")!!.singleFrom?.id)
        assertNull(svc.album(open, "s-hr")!!.singleFrom)
    }

    @Test
    fun more_from_artist_leaves_out_the_singles_homed_here() = runBlocking<Unit> {
        val more = svc.album(open, "kw")!!.moreFromArtist.map { it.id }
        assertTrue(more.none { it in setOf("s-nl", "s-fb", "s-salt", "s-lt", "s-tw") }, more.toString())
        assertTrue("sf" in more)
    }

    @Test
    fun artist_detail_moves_homed_singles_to_singles_under_whichever_group_held_them() = runBlocking<Unit> {
        store.replaceLibrary(MusicLibraryRows(EditionsFixture.LIB, emptyList(),
            listOf(EditionsFixture.album("s-live", "Small Boats (Live)", 2007, primary = "Single", secondary = listOf("Live"))),
            listOf(EditionsFixture.track("s-live-1", "s-live", 1, "Small Boats (live)", "sb-live"))))
        assertTrue(svc.artist(open, "hl", null)!!.groups.single { it.type == "live" }.albums.any { it.id == "s-live" })
        store.putHome("s-live", "kw", 1)
        val a = svc.artist(open, "hl", null)!!
        assertTrue(a.groups.none { g -> g.albums.any { it.id == "s-live" || it.id == "s-nl" } })
        assertEquals(listOf("kw" to 6), a.singlesUnder.map { it.album.id to it.count })
    }

    @Test
    fun lists_fold_per_viewer() = runBlocking<Unit> {
        val songs = svc.browse(onlyLib, "tracks", null, 0, null)
        val files = store.snapshot().tracks.values.count { it.libraryId == EditionsFixture.LIB }
        assertEquals(files - 5, songs.total, "Northern Line ×3, Fog Bank ×2, Salt ×2, Lantern Swing ×2 fold")
        assertEquals(setOf("kw-2", "nn-1"), svc.search(onlyLib, "northern line").songs.map { it.id }.toSet(), "the compilation's copy has no recording and no sound pair yet")
        val top = svc.artist(onlyLib, "hl", null)!!.topTracks
        assertEquals(top.size, top.map { it.title }.size)
        assertEquals(files - 5, top.size, "every song once")
        assertEquals(12, svc.album(onlyLib, "kw")!!.tracks.size, "an album's own tracks never fold")
        assertEquals(4, svc.album(onlyLib, "s-nl")!!.tracks.size)
    }

    @Test
    fun the_shown_copy_is_the_highest_bitrate_this_viewer_may_see() = runBlocking<Unit> {
        // A 192 kbps WMA beats a 160 kbps MP3: re-encoding does not demote a copy (305 owner decision 2).
        store.putTracks(listOf(store.track("kw-3")!!.copy(bitrate = 160_000), store.track("s-fb-1")!!.copy(bitrate = 192_000, container = "asf", codec = "wmav2")))
        assertEquals(listOf("s-fb-1"), svc.search(onlyLib, "fog bank").songs.map { it.id })
        // The FLAC sits in a library this viewer cannot open: never shown, never counted.
        assertEquals(listOf("kw-1"), svc.search(onlyLib, "kite weather").songs.filter { it.title == "Kite Weather" }.map { it.id })
        assertEquals(listOf("flac-1"), svc.search(open, "kite weather").songs.filter { it.title == "Kite Weather" }.map { it.id })
        assertEquals(0, svc.album(onlyLib, "kw")!!.tracks.first().alsoOn)
        assertEquals(1, svc.album(open, "kw")!!.tracks.first().alsoOn)
    }

    @Test
    fun extra_on_a_folded_row_only_when_the_song_is_official_nowhere() = runBlocking<Unit> {
        assertTrue(svc.search(open, "lantern").songs.single().extra)
        assertTrue(svc.album(open, "kw")!!.tracks.single { it.id == "kw-12" }.extra)
        assertFalse(svc.album(open, "s-fb")!!.tracks.single { it.id == "s-fb-5" }.extra, "on an album's own tracks it is per copy")
        assertFalse(svc.search(open, "northern").songs.single { it.id == "kw-2" }.extra)
    }

    @Test
    fun also_on_counts_the_other_visible_copies() = runBlocking<Unit> {
        assertEquals(2, svc.search(onlyLib, "northern").songs.single { it.id == "kw-2" }.alsoOn)
    }

    @Test
    fun copies_lists_the_other_visible_copies_and_a_hidden_track_is_not_found() = runBlocking<Unit> {
        val c = svc.copies(onlyLib, "kw-2")!!.copies
        assertEquals(listOf("s-nl-1", "tt-1"), c.map { it.track.id })
        assertEquals(listOf("single", "compilation"), c.map { it.album?.type })
        assertNull(svc.copies(onlyLib, "flac-1"))
        assertEquals(listOf("flac-1"), svc.copies(open, "kw-1")!!.copies.map { it.track.id })
    }
}

class MusicTvSongCopyTest : MusicTvEditionsBase("tv-songcopy") {
    @Test
    fun the_copy_shown_is_the_best_this_viewer_may_see() = runBlocking<Unit> {
        val restricted = svc.browse(onlyLib, "tracks", null, 0, null)
        assertTrue(restricted.tracks.none { it.id == "flac-1" })
        assertTrue(restricted.tracks.any { it.id == "kw-1" })
        val all = svc.browse(open, "tracks", null, 0, null)
        assertTrue(all.tracks.any { it.id == "flac-1" }); assertTrue(all.tracks.none { it.id == "kw-1" })
        assertEquals(restricted.total, all.total, "the hidden copy is not counted")
    }

    private class FakeJellyfin {
        val deleted = ArrayList<String>()
        val posted = ArrayList<String>()
    }

    private fun <T> withJellyfin(block: suspend (FakeJellyfin) -> T): T = runBlocking {
        val fake = FakeJellyfin()
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/Users/{id}") { call.respondText("""{"Id":"u1","Name":"viewer"}""", ContentType.Application.Json) }
                get("/Items") {
                    val body = when (call.request.queryParameters["Filters"]) {
                        "IsPlayed" -> """{"Items":[{"Id":"s-nl-1","UserData":{"PlayCount":2,"Played":true}},{"Id":"kw-2","UserData":{"PlayCount":3,"Played":true}},{"Id":"tt-1","UserData":{"PlayCount":1,"Played":true}}]}"""
                        "IsFavorite" -> """{"Items":[{"Id":"s-nl-1"},{"Id":"tt-1"}]}"""
                        else -> """{"Items":[]}"""
                    }
                    call.respondText(body, ContentType.Application.Json)
                }
                delete("/UserFavoriteItems/{id}") { fake.deleted += call.parameters["id"]!!; call.respond(HttpStatusCode.OK) }
                post("/UserFavoriteItems/{id}") { fake.posted += call.parameters["id"]!!; call.respond(HttpStatusCode.OK) }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        config.update(config.current.copy(apiKeys = config.current.apiKeys.copy(jellyfinUrl = "http://127.0.0.1:$port", jellyfinToken = "server")))
        try { block(fake) } finally { server.stop(100, 500) }
    }

    @Test
    fun play_count_sums_last_played_is_latest_favourite_is_any() = withJellyfin<Unit> {
        val row = svc.search(onlyLib, "northern").songs.single { it.id == "kw-2" }
        assertTrue(row.favorite, "a favourite when any copy is")
        // Recently played: Jellyfin's newest first, deduplicated by song, order kept.
        assertEquals(listOf("kw-2"), svc.search(onlyLib, null).songs.map { it.id })
        val sorted = svc.browse(onlyLib, "tracks", "played", 0, null).tracks
        assertEquals("kw-2", sorted.first().id, "2 + 3 + 1 plays summed put the song first")
    }

    @Test
    fun unfavouriting_a_folded_row_clears_every_favourited_copy() = withJellyfin<Unit> { fake ->
        svc.setFavorite(onlyLib, "kw-2", favorite = false)
        assertEquals(setOf("kw-2", "s-nl-1", "tt-1"), fake.deleted.toSet())
        assertEquals(3, fake.deleted.size)
        svc.setFavorite(onlyLib, "kw-5", favorite = true)
        assertEquals(listOf("kw-5"), fake.posted)
    }
}
