package dev.jellystructure.media

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.io.FileIo
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 316, DEB-1252 — the three things the 2026-10-15 press got wrong, each against the real piece rather than a
 * port fake: the Jellyfin lookup (a loopback Jellyfin that **ignores `Path=`**, as 12.1 does), the rows step 6 moves
 * (FR-316-9, a real database) and the store's twin deletion (FR-316-8, real files).
 */
class LooseFilmsBindingTest {
    private val run = getpid().toString()
    private val dir = "/tmp/jellystructure-test-316-binding-$run"

    @BeforeTest fun setUp() { platform.posix.system("rm -rf '$dir'; mkdir -p '$dir'") }
    @AfterTest fun tearDown() { platform.posix.system("rm -rf '$dir'") }

    // ── test 7 — the lookup, over HTTP ──

    private val library = """{"Items":[
        {"Id":"album0001","Name":"An Album","Type":"MusicAlbum","Path":"/music/An Artist/An Album"},
        {"Id":"film0001","Name":"Some Film","Type":"Movie","Path":"/movies/Some Film (2023)/Some.Film.2023.mkv"},
        {"Id":"film0002","Name":"Other Film","Type":"Movie","Path":"/movies/Other Film (2020)/Other.Film.2020.mkv"}
    ],"TotalRecordCount":3}"""

    private fun <T> withJellyfin(body: String, block: suspend (JellyfinClient, String) -> T): T = runBlocking {
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            // 12.1: `Path=` is ignored, the whole library comes back in its own order.
            routing { get("/Items") { call.respondText(body, ContentType.Application.Json) } }
        }.start(wait = false)
        try {
            block(JellyfinClient(), "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}")
        } finally {
            server.stop(100, 500)
        }
    }

    @Test fun `a lookup against a Jellyfin that ignores Path never returns the first item`() = withJellyfin<Unit>(library) { jf, base ->
        // The path is not in the library: the answer is nothing, not the album that came first.
        assertNull(jf.findRecentItemByPath(base, "t", "/movies/Not There (2024)/Not.There.2024.mkv"))
    }

    @Test fun `a lookup returns the item whose Path equals the one asked for`() = withJellyfin<Unit>(library) { jf, base ->
        val item = jf.findRecentItemByPath(base, "t", "/movies/Other Film (2020)/Other.Film.2020.mkv")
        assertEquals("film0002", item?.id)
        assertEquals("Movie", item?.type)
    }

    @Test fun `a path that only prefixes another item's path is not a match`() = withJellyfin<Unit>(library) { jf, base ->
        assertNull(jf.findRecentItemByPath(base, "t", "/movies/Some Film (2023)"))
        assertNull(jf.findRecentItemByPath(base, "t", "/movies/Some Film (2023)/Some.Film.2023.mk"))
    }

    // ── test 10 — FR-316-9, the rows step 6 moves, on a real database ──

    @Test fun `remapRows names every row it carried with its key and old value and leaves other ids alone`() {
        val db = createDatabase("$dir/remap.db")
        db.playbackStartSampleQueries.insert("dev1", "wrong", "/lib/a.mkv", 3, 1L)
        db.playbackStartSampleQueries.insert("dev1", "other", "/lib/b.mkv", 4, 2L)
        db.recommendationQueries.putStarter("scope", 1, "wrong", "watched", 10)
        db.recommendationQueries.putStarter("scope", 2, "other", "watched", 10)
        db.recommendationQueries.putRecommendation("u1", "scope", 1, "x", 1.0, "rated", "wrong", "standard", 10)
        db.recommendationQueries.putRecommendation("u1", "scope", 2, "wrong", 1.0, "new", null, "standard", 10)

        val moved = remapRows(db, "wrong", "right")

        assertEquals(4, moved.count)
        assertEquals(setOf("playback_start_sample", "starter_list", "recommendation"), moved.rows.map { it.table }.toSet())
        // reason_item_id and item_id are two moves of two rows of one table, each with the column it held.
        val rec = moved.rows.filter { it.table == "recommendation" }.associate { it.key to it.old }
        assertEquals(mapOf("reason_item_id" to "wrong"), rec["u1|scope|1"])
        assertEquals(mapOf("item_id" to "wrong"), rec["u1|scope|2"])
        assertEquals(mapOf("item_id" to "wrong"), moved.rows.single { it.table == "starter_list" }.old)
        // The rows really are on the new id, and the unrelated one is not.
        assertEquals(listOf("right", "other"), db.recommendationQueries.starterFor("scope").executeAsList().map { it.item_id })
        assertEquals(0, db.jellyfinIdRemapQueries.sampleKeysOn("wrong").executeAsList().size)
        assertEquals(1, db.jellyfinIdRemapQueries.sampleKeysOn("other").executeAsList().size)

        // A second run has nothing to carry: the snapshot is the record of the first.
        assertEquals(0, remapRows(db, "wrong", "right").count)
        val snap = movedRowsSnapshot("wrong", "right", moved.rows)
        assertTrue("\"oldId\":\"wrong\"" in snap && "\"newId\":\"right\"" in snap && "playback_start_sample" in snap, snap)
    }

    // ── test 9 — FR-316-8, a store write never deletes another film ──

    private fun film(slug: String, path: String, jellyfinId: String?) = MediaItem(
        id = slug, title = slug, year = 2023, kind = MediaKind.MOVIE, path = path, tmdbId = null, originalLanguage = null,
        posterPath = null, overview = null, tracks = emptyList(), issueCount = 0, scannedAt = 0L, jellyfinId = jellyfinId,
    )

    private fun touch(path: String) {
        platform.posix.system("mkdir -p '${path.substringBeforeLast('/')}'")
        runBlocking { FileIo.writeText(Path(path), "x") }
    }

    private suspend fun store(): MediaStore {
        val config = ConfigStore("$dir/config.toml").also { it.load() }
        return MediaStore(createDatabase("$dir/store.db"), JsTagStore("$dir/tags.json"), config)
    }

    @Test fun `three films sharing one id lose none of each other when one is written`() = runBlocking<Unit> {
        val a = "$dir/a/A.mkv"; val b = "$dir/b/B.mkv"; val c = "$dir/c/C.mkv"
        listOf(a, b, c).forEach(::touch)
        val s = store()
        // The state the 2026-10-15 press left: three rows, three real files, one Jellyfin id.
        s.addOrUpdate(film("a", a, "album"))
        s.addOrUpdate(film("b", b, "album"))
        s.addOrUpdate(film("c", c, "album"))
        // …and a sweep that writes one of them again.
        s.addOrUpdate(film("a", a, "album").copy(title = "A again"))
        assertEquals(setOf("a", "b", "c"), s.allItems().map { it.id }.toSet(), "no film is a stale duplicate of another")
    }

    @Test fun `a sweep holding a pre-move read of a film does not delete the row whose file is on disk`() = runBlocking<Unit> {
        val oldPath = "$dir/Film.mkv"          // the loose file the move emptied
        val newPath = "$dir/Film (2023)/Film.mkv"
        touch(newPath)
        val s = store()
        val other = "$dir/Other (2020)/Other.mkv"; touch(other)
        s.addOrUpdate(film("other", other, "shared"))
        // The row step 6 has just given its new path and (here) the id another item carries.
        s.addOrUpdate(film("film", newPath, "shared"))
        // The sweep read this film at its old path before step 6 ran, and writes it under the same id.
        s.addOrUpdate(film("film", oldPath, "shared"))
        assertNotNull(s.get("other"), "the other film's row survives")
        assertNotNull(s.get("film"))
    }

    @Test fun `a real duplicate is still removed — the same file under another slug`() = runBlocking<Unit> {
        val p = "$dir/d/D.mkv"; touch(p)
        val s = store()
        s.addOrUpdate(film("d-old-slug", p, "jf-1"))
        s.addOrUpdate(film("d-2023", p, "jf-1"))
        assertEquals(setOf("d-2023"), s.allItems().map { it.id }.toSet())
    }

    @Test fun `a twin whose file is gone is still removed`() = runBlocking<Unit> {
        val gone = "$dir/gone/G.mkv"
        val live = "$dir/live/L.mkv"; touch(live)
        val s = store()
        s.addOrUpdate(film("g", gone, "jf-2"))
        s.addOrUpdate(film("l", live, "jf-2"))
        assertEquals(setOf("l"), s.allItems().map { it.id }.toSet())
    }
}
