package dev.jellystructure.music

import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicArtist
import dev.jellystructure.model.MusicCredit
import dev.jellystructure.model.MusicMatch
import dev.jellystructure.model.MusicRecording
import dev.jellystructure.model.MusicSongFilter
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.model.MusicVersionBulkRequest
import dev.jellystructure.model.MusicVersions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 292 (FR-292-16) — *Set version…* takes the Songs filter instead of a list of every shown id.
 *
 * The requirement is an **equivalence**, and it is the one a reader cannot check by eye: the filter form must write
 * exactly the rows the id form writes for the same set. So every case here builds two identical stores, writes the
 * same change to one through the ids a paged Songs view hands the browser (page by page, as the admin's scroller
 * gathers them) and to the other through the filter alone, and compares the owner's rows, the sentence and the
 * History lines. A filter resolved differently from the list on screen — wider, narrower or in a different fold —
 * shows up here as a diff rather than as 10 723 songs quietly carrying a type nobody asked for.
 */
class MusicVersionBulkFilterTest {

    private val base = "/tmp/jellystructure-test-vbulk-${getpid()}"
    private val files = ArrayList<String>()

    @AfterTest fun tearDown() { files.forEach { runCatching { platform.posix.remove(it) } } }

    private val hl = MusicCredit("hl", "Harbour Lights")
    private val fm = MusicCredit("fm", "The Ferrymen")

    /** One side of a comparison: its own database file, so the two writes cannot see each other. */
    private class Side(val store: MusicStore, val db: dev.jellystructure.db.JellystructureDb) {
        val history = MediaHistory(db)
        val service = MusicVersionService(store, history)
    }

    private var nth = 0

    /** One store per side, same rows in both, so a write to one can be compared with a write to the other. */
    private suspend fun side(): Side {
        val file = "$base-${nth++}.db"
        files += file; files += "$file-wal"; files += "$file-shm"
        runCatching { platform.posix.remove(file) }
        val db = createDatabase(file)
        val store = MusicStore(db)
        val albums = listOf("live" to hl, "best" to hl, "studio" to hl, "ferry" to fm).map { (id, who) ->
            MusicAlbum(id = id, libraryId = "l", title = "Album $id", albumArtists = listOf(who), matchState = MusicMatch.MATCHED)
        }
        // `addedAt` is pinned: 305's copy rank falls through to it, and a clock tick between the two sides must
        // not be able to pick a different shown copy on one of them. With it equal, the rank's last key is the id.
        fun t(id: String, album: String, title: String, rec: String?, who: MusicCredit) =
            MusicTrack(id = id, albumId = album, libraryId = "l", title = title, artists = listOf(who),
                recordingMbid = rec, recordingState = rec?.let { MusicRecording.AGREES }, addedAt = 1)
        store.replaceLibrary(MusicLibraryRows("l", listOf(
            MusicArtist(id = "hl", name = "Harbour Lights", libraryId = "l"),
            MusicArtist(id = "fm", name = "The Ferrymen", libraryId = "l"),
        ), albums, listOf(
            // The same live recording on two albums: one Songs row, two copies behind it (305's folding).
            t("c1", "live", "Salt on the Window (live at the harbour, 2011)", "r-salt-live", hl),
            t("c2", "best", "Salt on the Window (live)", "r-salt-live", hl),
            t("s1", "studio", "Salt on the Window", "r-salt", hl),
            t("s2", "studio", "Northern Line (Lighthouse Keepers remix)", "r-north-remix", hl),
            t("s3", "studio", "Kite Weather", "r-kite", hl),
            t("u1", "studio", "Northbound", null, hl),
            t("f1", "ferry", "Fog Bank (live)", "r-fog-live", fm),
            t("f2", "ferry", "Fog Bank", "r-fog", fm),
        )))
        return Side(store, db)
    }

    /** What the browser holds: the Songs rows of [f], gathered a page at a time exactly as `muFetch` gathers them. */
    private fun idsByPage(snap: MusicStore.Snapshot, f: MusicSongFilter, pageSize: Int = 2): List<String> {
        val out = ArrayList<String>()
        var page = 1
        while (true) {
            val rows = MusicBrowse.browse(
                snap, MusicBrowse.SONGS,
                selected = f.f.mapValues { (_, v) -> v.toSet() }, query = f.q, libraryNames = emptyMap(),
                triage = f.filter, excluded = f.x.mapValues { (_, v) -> v.toSet() }, artist = f.artist,
                everyCopy = f.copies == "all", page = page, pageSize = pageSize,
            ).songs.map { it.id }
            if (rows.isEmpty()) return out
            out += rows
            page++
        }
    }

    /** The owner's rows as data: the timestamp is when each side ran, so it is normalised away. */
    private fun rows(store: MusicStore): List<Triple<String, String, Boolean>> =
        store.snapshot().choices.entries.sortedBy { it.key }
            .flatMap { (key, m) -> m.values.sortedBy { it.type }.map { Triple(key, it.type, it.on) } }

    /** Every song's answer, for the second half of the claim: equal rows must mean equal answers. */
    private fun answers(store: MusicStore): Map<String, List<String>> =
        store.snapshot().let { s -> s.tracks.values.associate { it.id to s.versions.of(it).shown } }

    /** Both shapes of the same write, through the same service, on two stores that started identical. */
    private suspend fun bothForms(f: MusicSongFilter, add: Set<String>, remove: Set<String> = emptySet()): Side {
        val byId = side()
        val byFilter = side()
        val paged = idsByPage(byId.store.snapshot(), f)
        assertEquals(paged, MusicBrowse.songIdsOf(byId.store.snapshot(), f),
            "the filter resolves to the rows the pages concatenate to, in the same order")
        val resolved = MusicBrowse.songIdsOf(byFilter.store.snapshot(), f)
        val one = byId.service.bulk(paged, add, remove)
        val two = byFilter.service.bulk(resolved, add, remove)
        assertEquals(one, two, "the sentence the admin is shown")
        assertEquals(rows(byId.store), rows(byFilter.store), "the owner's rows written")
        assertEquals(answers(byId.store), answers(byFilter.store), "every song's answer afterwards")
        return byFilter
    }

    @Test
    fun the_filter_form_and_the_id_form_of_the_same_set_write_the_same_rows() = runBlocking {
        // The whole library — what *select all shown* means on an unfiltered Songs tab.
        bothForms(MusicSongFilter(), setOf(MusicVersions.ACOUSTIC))
        // One artist, and Hide Live on top of it (FR-292-11's `x.version=`).
        bothForms(MusicSongFilter(artist = "hl", x = mapOf("version" to listOf(MusicVersions.LIVE))), setOf(MusicVersions.DEMO))
        // Only Live, a search, and a facet that is not the Version one.
        bothForms(MusicSongFilter(f = mapOf("version" to listOf(MusicVersions.LIVE))), setOf(MusicVersions.SESSION))
        bothForms(MusicSongFilter(q = "fog"), setOf(MusicVersions.COVER))
        bothForms(MusicSongFilter(f = mapOf("lyrics" to listOf("missing"))), setOf(MusicVersions.EDIT))
        // *Show every copy*: the set is files rather than songs, and both forms must agree on that too.
        bothForms(MusicSongFilter(copies = "all", artist = "hl"), setOf(MusicVersions.ALTERNATE))
        // A removal is the same claim pointing the other way.
        bothForms(MusicSongFilter(f = mapOf("version" to listOf(MusicVersions.LIVE))), emptySet(), setOf(MusicVersions.LIVE))
        Unit
    }

    @Test
    fun the_filter_writes_what_it_matches_and_nothing_beside_it() = runBlocking {
        // Songs by Harbour Lights, without Live: the live recording and the other artist's songs are outside it.
        val f = MusicSongFilter(artist = "hl", x = mapOf("version" to listOf(MusicVersions.LIVE)))
        val side = bothForms(f, setOf(MusicVersions.DEMO))
        val snap = side.store.snapshot()
        for (id in listOf("s1", "s2", "s3", "u1")) assertTrue(MusicVersions.DEMO in snap.versions.of(snap.tracks[id]!!).shown, id)
        for (id in listOf("c1", "c2", "f1", "f2")) assertTrue(MusicVersions.DEMO !in snap.versions.of(snap.tracks[id]!!).shown, id)
        assertNull(snap.choices["rec:r-fog"], "the other artist's songs were never in the set")
        // One History line per album the set touched, and none on the albums it did not.
        assertEquals(1, side.history.forItem("studio").count { it.action == "music_versions" })
        assertEquals(0, side.history.forItem("ferry").count { it.action == "music_versions" })
        assertEquals(0, side.history.forItem("live").count { it.action == "music_versions" })
    }

    @Test
    fun a_copy_on_another_album_follows_the_recording_and_says_so_in_its_history() = runBlocking {
        // `c1` is the Songs row; `c2` is the same recording on the best-of and is not in the resolved ids at all.
        val f = MusicSongFilter(q = "salt on the window", f = mapOf("version" to listOf(MusicVersions.LIVE)))
        val side = bothForms(f, setOf(MusicVersions.ACOUSTIC))
        val snap = side.store.snapshot()
        assertEquals(listOf("c1"), MusicBrowse.songIdsOf(snap, f), "one row stands for both copies")
        assertTrue(MusicVersions.ACOUSTIC in snap.versions.of(snap.tracks["c2"]!!).shown, "FR-292-2 — one answer per recording")
        assertTrue(MusicVersions.ACOUSTIC !in snap.versions.of(snap.tracks["s1"]!!).shown, "the studio recording is another song")
        assertEquals(1, side.history.forItem("best").count { it.action == "music_versions" }, "the album holding the copy says what happened")
    }

    @Test
    fun the_dialogs_count_is_the_whole_set_and_not_the_page() = runBlocking {
        val side = side()
        val f = MusicSongFilter(artist = "hl")
        val whole = MusicBrowse.browse(side.store.snapshot(), MusicBrowse.SONGS, emptyMap(), null, emptyMap(), artist = "hl", page = 1, pageSize = 2)
        assertEquals(2, whole.songs.size, "the page the browser holds")
        val pv = side.service.preview(MusicBrowse.songIdsOf(side.store.snapshot(), f))
        assertEquals(whole.rowsTotal, pv.songs, "*Set version on N songs* is the whole filtered set")
        assertEquals(1, pv.counts[MusicVersions.LIVE], "the live recording, once, of ${whole.rowsTotal} in the set")
        assertEquals(1, pv.otherCopies, "the best-of's copy changes with it")
        assertEquals(1, pv.otherAlbums)
        Unit
    }

    /**
     * The installed-client rule: `track_ids` is not retyped and not removed, and neither shape needs the other's
     * field. An admin build that never heard of `filter` sends exactly what it sent before.
     */
    @Test
    fun both_shapes_decode_off_the_wire_and_neither_field_is_required() {
        val json = Json { ignoreUnknownKeys = true }
        val ids = json.decodeFromString(MusicVersionBulkRequest.serializer(), """{"track_ids":["c1","s1"],"add":["live"]}""")
        assertEquals(listOf("c1", "s1"), ids.trackIds)
        assertNull(ids.filter, "the shape every installed admin sends")
        val filtered = json.decodeFromString(MusicVersionBulkRequest.serializer(),
            """{"add":["demo"],"filter":{"q":"fog","f":{"version":["live"]},"x":{"lyrics":["has"]},"artist":"fm","copies":"all","filter":"m-instlyr"}}""")
        assertEquals(emptyList<String>(), filtered.trackIds)
        assertEquals("fog", filtered.filter?.q)
        assertEquals(mapOf("version" to listOf(MusicVersions.LIVE)), filtered.filter?.f)
        assertEquals(mapOf("lyrics" to listOf("has")), filtered.filter?.x)
        assertEquals("fm", filtered.filter?.artist)
        assertEquals("all", filtered.filter?.copies)
        assertEquals("m-instlyr", filtered.filter?.filter)
    }
}
