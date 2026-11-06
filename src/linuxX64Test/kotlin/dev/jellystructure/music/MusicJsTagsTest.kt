package dev.jellystructure.music

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.JsTag
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicCredit
import dev.jellystructure.model.MusicMatch
import dev.jellystructure.model.MusicTrack
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * DEB-3704 (DEB-3701 FR-5–FR-10) — albums and songs carry jellystructure tags, and invariant #6 holds for music by
 * construction. Acceptance 4 to 9 of `songs-and-albums-carry-jellystructure-tags-and-a-tag-has-a-scope.md`.
 */
class MusicJsTagsTest {
    private val base = "/tmp/jellystructure-test-jstags-${getpid()}"
    private val created = ArrayList<String>()
    private var nth = 0

    @AfterTest fun tearDown() { created.forEach { runCatching { platform.posix.remove(it) } } }

    private val hl = MusicCredit("hl", "Harbour Lights")

    private fun album(id: String, title: String = "Kite Weather", path: String? = "/m/hl/$id", rel: String? = null, rg: String? = null, missing: Long? = null, artist: MusicCredit = hl) =
        MusicAlbum(id = id, libraryId = "l", title = title, path = path, releaseMbid = rel, releaseGroupMbid = rg, albumArtists = listOf(artist),
            matchState = MusicMatch.MATCHED, missingSince = missing)

    private fun track(id: String, albumId: String?, title: String = "Song $id", path: String? = null, pos: Int? = null, disc: Int? = null, rec: String? = null, missing: Long? = null) =
        MusicTrack(id = id, albumId = albumId, libraryId = "l", title = title, path = path, position = pos, disc = disc, recordingMbid = rec, artists = listOf(hl), missingSince = missing)

    private fun file(name: String): String = "$base-${nth++}-$name".also { created += it; created += "$it-wal"; created += "$it-shm"; runCatching { platform.posix.remove(it) } }

    private var lastDb = ""

    private fun store(albums: List<MusicAlbum>, tracks: List<MusicTrack>): MusicStore = runBlocking {
        lastDb = file("db")
        MusicStore(createDatabase(lastDb)).also { it.replaceLibrary(MusicLibraryRows("l", emptyList(), albums, tracks)) }
    }

    private fun tags(vararg defs: JsTag): JsTagStore = JsTagStore(file("tags.json")).also { s -> defs.forEach { s.create(it) } }

    private fun row(id: String, tag: String, kind: String = MusicJsTagRow.TRACK, at: Long = 10) = MusicJsTagRow(id, kind, tag, at)

    // ── FR-5: the closed list of writers (Acceptance 5) ──

    @Test
    fun the_table_has_exactly_five_writers_and_each_may_only_make_its_own_kind_of_change() = runBlocking<Unit> {
        assertEquals(listOf("ADMIN", "TAG_DELETE", "OUTSIDE_SCOPE", "APPLY_TO_SONGS", "CARRY"), MusicJsTagWriter.entries.map { it.name })
        val s = store(listOf(album("a1")), listOf(track("t1", "a1")))
        // Apply and carry only ever add; the delete and the outside-scope removal only ever remove.
        assertFailsWith<IllegalArgumentException> { s.addJsTags(MusicJsTagWriter.TAG_DELETE, listOf(row("t1", "x"))) }
        assertFailsWith<IllegalArgumentException> { s.addJsTags(MusicJsTagWriter.OUTSIDE_SCOPE, listOf(row("t1", "x"))) }
        s.addJsTags(MusicJsTagWriter.ADMIN, listOf(row("t1", "x")))
        for (w in listOf(MusicJsTagWriter.APPLY_TO_SONGS, MusicJsTagWriter.CARRY, MusicJsTagWriter.TAG_DELETE))
            assertFailsWith<IllegalArgumentException>(w.name) { s.removeJsTags(w, listOf("t1" to "x")) }
        for (w in MusicJsTagWriter.entries.filter { it != MusicJsTagWriter.TAG_DELETE }) assertFailsWith<IllegalArgumentException>(w.name) { s.stripJsTag(w, "x") }
        assertEquals(listOf("x"), s.snapshot().jsTagsOf("t1"))
    }

    private fun sources(): List<Pair<String, String>> {
        var dir = "."
        repeat(6) {
            val p = Path("$dir/src/linuxX64Main/kotlin/dev/jellystructure")
            if (SystemFileSystem.exists(p)) return all(p).map { it.name to SystemFileSystem.source(it).buffered().use { s -> s.readString() } }
            dir += "/.."
        }
        fail("the backend's sources were not found from the test's working directory")
    }

    private fun all(dir: Path): List<Path> = SystemFileSystem.list(dir).flatMap { p ->
        if (SystemFileSystem.metadataOrNull(p)?.isDirectory == true) all(p) else if (p.name.endsWith(".kt")) listOf(p) else emptyList()
    }

    /** Phase 199's audit, made structural: a grep over the backend's sources, so a sixth writer fails here. */
    @Test
    fun no_source_file_writes_music_js_tag_outside_the_five_writers() {
        val files = sources()
        assertTrue(files.size > 100, "found the sources (${files.size} files)")
        // Only MusicStore reaches the table's queries.
        assertEquals(listOf("MusicStore.kt"), files.filter { (_, t) -> t.contains("musicJsTagQueries") }.map { it.first }, "the table is queried from MusicStore only")
        // Every call of a mutator names its writer literally, and the (file, mutator, writer) set is the list in FR-5.
        val call = Regex("""\.(addJsTags|removeJsTags|stripJsTag)\(\s*(MusicJsTagWriter\.(\w+)|\w+)""")
        val found = files.filter { (n, _) -> n != "MusicStore.kt" }.flatMap { (n, t) ->
            call.findAll(t).map { m ->
                val w = m.groupValues[3].ifEmpty { fail("$n calls ${m.groupValues[1]} with a writer that is not a literal: ${m.value}") }
                Triple(n, m.groupValues[1], w)
            }.toList()
        }.toSet()
        assertEquals(
            setOf(
                Triple("MusicJsTagService.kt", "addJsTags", "ADMIN"),          // 1. the admin's add routes (and the bulk Tag…)
                Triple("MusicJsTagService.kt", "removeJsTags", "ADMIN"),       // 1. the admin's remove routes (and Untag…, FR-8's Remove)
                Triple("MusicJsTagService.kt", "stripJsTag", "TAG_DELETE"),    // 2. a tag's deletion
                Triple("MusicJsTagService.kt", "removeJsTags", "OUTSIDE_SCOPE"), // 3. FR-4's removal
                Triple("MusicJsTagService.kt", "addJsTags", "APPLY_TO_SONGS"), // 4. FR-6's apply button
                Triple("MusicScanner.kt", "addJsTags", "CARRY"),               // 5. FR-7's carry
            ),
            found,
        )
    }

    // ── Acceptance 4: no automatic path removes or rewrites a tag ──

    @Test
    fun a_scan_a_rematch_a_repull_and_a_file_write_leave_every_tag() = runBlocking<Unit> {
        val albums = listOf(album("a1"), album("a2", "Other"))
        val tracks = listOf(track("t1", "a1", "One", "/m/hl/a1/01.mp3", 1), track("t2", "a1", "Two", "/m/hl/a1/02.mp3", 2))
        val file = file("db")
        val s = MusicStore(createDatabase(file)).also { it.replaceLibrary(MusicLibraryRows("l", emptyList(), albums, tracks)) }
        s.addJsTags(MusicJsTagWriter.ADMIN, listOf(row("a1", "Live night", MusicJsTagRow.ALBUM), row("t1", "Favourite")))
        val before = s.snapshot().jsTags
        fun same(step: String) = assertEquals(before, s.snapshot().jsTags, step)

        // A full scan: the same rows again (and a changed field), by INSERT OR REPLACE.
        s.replaceLibrary(MusicLibraryRows("l", emptyList(), albums.map { it.copy(updatedAt = 99, trackCount = 2) }, tracks.map { it.copy(updatedAt = 99) })); same("scan")
        // A re-match and a MusicBrainz re-pull: the album row is rewritten (putAlbum), a match changes its ids.
        s.putAlbum(albums[0].copy(releaseMbid = "rel-1", releaseGroupMbid = "rg-1", matchedAt = 5, matchSource = "search", mbGenres = emptyList())); same("re-match")
        s.putAlbum(albums[0].copy(releaseMbid = "rel-2", firstReleaseDate = "2001-02-03")); same("re-pull")
        s.putTracks(tracks.map { it.copy(recordingMbid = "rec-${it.id}", mbTitle = "MB title") }); same("track re-pull")
        // write_tags stamps the album and rewrites a track's file facts through the same two calls.
        s.putAlbum(albums[0].copy(tagsWrittenAt = 7)); s.putTracks(tracks.map { it.copy(container = "mp3", updatedAt = 100) }); same("write_tags")
        // A Jellyfin refresh sets missing_since on a row that went away and clears it when it is back.
        s.replaceLibrary(MusicLibraryRows("l", emptyList(), albums, tracks.map { it.copy(missingSince = 50) })); same("went missing")
        s.replaceLibrary(MusicLibraryRows("l", emptyList(), albums, tracks)); same("came back")
        // Nothing of this is in the rows themselves (FR-5): the JSON a scan replaces carries no tag.
        assertTrue(Json.encodeToString(MusicAlbum.serializer(), s.snapshot().albums.getValue("a1")).let { "Live night" !in it })
        assertTrue(Json.encodeToString(MusicTrack.serializer(), s.snapshot().tracks.getValue("t1")).let { "Favourite" !in it })
        // And it survives a restart.
        assertEquals(before, MusicStore(createDatabase(file)).snapshot().jsTags)
    }

    // ── FR-7: a tag follows its song or album to a new id (Acceptance 6) ──

    private fun scanner(s: MusicStore, history: dev.jellystructure.media.MediaHistory? = null) =
        MusicScanner(ConfigStore(file("cfg.toml")).also { runBlocking { it.load() } }, JellyfinClient(), s, history = history)

    /** What a scan does: take the new rows, write them, then carry. [prev] is the snapshot before the write. */
    private suspend fun scan(s: MusicStore, sc: MusicScanner, albums: List<MusicAlbum>, tracks: List<MusicTrack>) {
        val prev = s.snapshot()
        val rows = MusicLibraryRows("l", emptyList(), albums, tracks)
        s.replaceLibrary(rows)
        sc.carryJsTags(prev, rows)
    }

    @Test
    fun a_lidarr_upgrade_from_mp3_to_flac_of_the_same_base_name_gives_the_new_id_the_tag_and_the_missing_row_keeps_it() = runBlocking<Unit> {
        val s = store(listOf(album("a1")), listOf(track("old", "a1", "Waltz", "/m/hl/a1/01 Waltz.mp3", 1)))
        s.addJsTags(MusicJsTagWriter.ADMIN, listOf(row("old", "Favourite", at = 3)))
        val sc = scanner(s)
        scan(s, sc, listOf(album("a1")), listOf(track("old", "a1", "Waltz", "/m/hl/a1/01 Waltz.mp3", 1, missing = 9), track("new", "a1", "Waltz", "/m/hl/a1/01 Waltz.flac", 1)))
        assertEquals(listOf("Favourite"), s.snapshot().jsTagsOf("new"))
        assertEquals(listOf("Favourite"), s.snapshot().jsTagsOf("old"), "a carry copies and never moves")
        assertEquals(3L, s.snapshot().jsTags["new"]!!.single().setAt)
    }

    @Test
    fun a_renamed_file_with_the_same_recording_mbid_in_the_same_album_carries() = runBlocking<Unit> {
        val s = store(listOf(album("a1")), listOf(track("old", "a1", "Waltz", "/m/hl/a1/01 Waltz.mp3", 1, rec = "rec-w")))
        s.addJsTags(MusicJsTagWriter.ADMIN, listOf(row("old", "Favourite")))
        scan(s, scanner(s), listOf(album("a1")), listOf(track("old", "a1", "Waltz", "/m/hl/a1/01 Waltz.mp3", 1, rec = "rec-w", missing = 9), track("new", "a1", "Waltz (remaster)", "/m/hl/a1/Waltz.flac", 7, rec = "rec-w")))
        assertEquals(listOf("Favourite"), s.snapshot().jsTagsOf("new"))
    }

    @Test
    fun the_same_disc_position_and_case_folded_title_carries_when_nothing_else_matches() = runBlocking<Unit> {
        val s = store(listOf(album("a1")), listOf(track("old", "a1", "THE WALTZ", "/m/hl/a1/x.mp3", 4, disc = 2)))
        s.addJsTags(MusicJsTagWriter.ADMIN, listOf(row("old", "Favourite")))
        scan(s, scanner(s), listOf(album("a1")), listOf(track("old", "a1", "THE WALTZ", "/m/hl/a1/x.mp3", 4, disc = 2, missing = 9), track("new", "a1", "the waltz", "/m/hl/a1/y.flac", 4, disc = 2)))
        assertEquals(listOf("Favourite"), s.snapshot().jsTagsOf("new"))
        // A different position is another song.
        scan(s, scanner(s), listOf(album("a1")), listOf(track("old", "a1", "THE WALTZ", "/m/hl/a1/x.mp3", 4, disc = 2, missing = 9), track("elsewhere", "a1", "the waltz", "/m/hl/a1/z.flac", 5, disc = 2)))
        assertEquals(emptyList(), s.snapshot().jsTagsOf("elsewhere"))
    }

    @Test
    fun an_album_that_moved_to_a_new_id_takes_its_songs_tags_through_the_albums_successor() = runBlocking<Unit> {
        // A folder rename gives the album and every song a new id; the release MBID ties the albums, the album ties the songs.
        val s = store(listOf(album("a-old", rel = "rel-1", path = "/m/hl/Kite")), listOf(track("old", "a-old", "Waltz", "/m/hl/Kite/01.mp3", 1, rec = "rec-w")))
        s.addJsTags(MusicJsTagWriter.ADMIN, listOf(row("a-old", "Live night", MusicJsTagRow.ALBUM), row("old", "Favourite")))
        scan(s, scanner(s), listOf(album("a-old", rel = "rel-1", path = "/m/hl/Kite", missing = 9), album("a-new", rel = "rel-1", path = "/m/hl/Kite (2001)")),
            listOf(track("old", "a-old", "Waltz", "/m/hl/Kite/01.mp3", 1, rec = "rec-w", missing = 9), track("new", "a-new", "Waltz", "/m/hl/Kite (2001)/01.flac", 1, rec = "rec-w")))
        assertEquals(listOf("Live night"), s.snapshot().jsTagsOf("a-new"))
        assertEquals(listOf("Favourite"), s.snapshot().jsTagsOf("new"))
    }

    @Test
    fun album_rungs_go_folder_then_release_then_release_group_then_artist_and_title() = runBlocking<Unit> {
        fun carried(old: MusicAlbum, new: MusicAlbum): Boolean = runBlocking {
            val s = store(listOf(old.copy(missingSince = null)), emptyList())
            s.addJsTags(MusicJsTagWriter.ADMIN, listOf(row(old.id, "T", MusicJsTagRow.ALBUM)))
            scan(s, scanner(s), listOf(old.copy(missingSince = 9), new), emptyList())
            s.snapshot().jsTagsOf(new.id) == listOf("T")
        }
        val old = album("o", "Kite Weather", path = "/m/hl/Kite", rel = "r1", rg = "g1")
        assertTrue(carried(old, album("n", "Renamed", path = "/m/hl/Kite")), "same folder")
        assertTrue(carried(old, album("n", "Renamed", path = "/m/x", rel = "r1")), "same release")
        assertTrue(carried(old, album("n", "Renamed", path = "/m/x", rg = "g1")), "same release group")
        assertTrue(carried(old, album("n", "KITE weather", path = "/m/x")), "same album artist and case-folded title")
        assertTrue(!carried(old, album("n", "Kite Weather", path = "/m/x", artist = MusicCredit("other", "Someone Else"))), "another artist is another album")
    }

    @Test
    fun two_candidates_on_one_rung_carry_nothing_and_fr8_lists_the_song() = runBlocking<Unit> {
        val s = store(listOf(album("a1")), listOf(track("old", "a1", "Waltz", "/m/hl/a1/01 Waltz.mp3", 1, rec = "rec-w")))
        s.addJsTags(MusicJsTagWriter.ADMIN, listOf(row("old", "Favourite")))
        // Two new files with the same recording in the same album: rung 2 has two candidates, and the search stops there.
        scan(s, scanner(s), listOf(album("a1")), listOf(track("old", "a1", "Waltz", "/m/hl/a1/01 Waltz.mp3", 1, rec = "rec-w", missing = 9),
            track("n1", "a1", "Waltz", "/m/hl/a1/Waltz.flac", 1, rec = "rec-w"), track("n2", "a1", "Waltz", "/m/hl/a1/Waltz 2.flac", 1, rec = "rec-w")))
        assertEquals(emptyList(), s.snapshot().jsTagsOf("n1")); assertEquals(emptyList(), s.snapshot().jsTagsOf("n2"))
        assertEquals(listOf("old"), MusicTagCarry.gone(s.snapshot()).map { it.subjectId })
        val svc = MusicJsTagService(s, tags(JsTag("Favourite")))
        assertEquals(listOf("old"), svc.gone("Favourite").map { it.second.id })
    }

    @Test
    fun a_carried_tag_is_not_listed_as_gone_and_the_carry_writes_one_history_line_on_the_album() = runBlocking<Unit> {
        val db = createDatabase(file("db"))
        val s = MusicStore(db).also { it.replaceLibrary(MusicLibraryRows("l", emptyList(), listOf(album("a1")), listOf(track("old", "a1", "Waltz", "/m/hl/a1/01.mp3", 1)))) }
        s.addJsTags(MusicJsTagWriter.ADMIN, listOf(row("old", "Favourite"), row("old", "Live night")))
        val history = dev.jellystructure.media.MediaHistory(db)
        scan(s, scanner(s, history), listOf(album("a1")), listOf(track("old", "a1", "Waltz", "/m/hl/a1/01.mp3", 1, missing = 9), track("new", "a1", "Waltz", "/m/hl/a1/01.flac", 1)))
        assertEquals(emptyList(), MusicTagCarry.gone(s.snapshot()).map { it.subjectId }.filter { it == "old" })
        val lines = history.forItem("a1")
        assertEquals(1, lines.size)
        assertEquals("tags carried from the replaced file: Favourite, Live night", lines.single().detail)
    }

    @Test
    fun a_tag_the_owner_took_off_the_new_file_is_not_put_back_by_the_next_scan() = runBlocking<Unit> {
        val s = store(listOf(album("a1")), listOf(track("old", "a1", "Waltz", "/m/hl/a1/01.mp3", 1)))
        s.addJsTags(MusicJsTagWriter.ADMIN, listOf(row("old", "Favourite")))
        val sc = scanner(s)
        val albums = listOf(album("a1"))
        val tracks = listOf(track("old", "a1", "Waltz", "/m/hl/a1/01.mp3", 1, missing = 9), track("new", "a1", "Waltz", "/m/hl/a1/01.flac", 1))
        scan(s, sc, albums, tracks)
        s.removeJsTags(MusicJsTagWriter.ADMIN, listOf("new" to "Favourite"))
        scan(s, sc, albums, tracks)   // nothing is new or newly missing
        assertEquals(emptyList(), s.snapshot().jsTagsOf("new"))
        assertEquals(listOf("Favourite"), s.snapshot().jsTagsOf("old"))
    }

    // ── FR-6: independent, and the apply button (Acceptance 7) ──

    private fun fixture(): Triple<MusicStore, MusicJsTagService, JsTagStore> {
        val s = store(listOf(album("a1"), album("a2", "Other")),
            listOf(track("t1", "a1", "One", pos = 1), track("t2", "a1", "Two", pos = 2), track("t3", "a1", "Gone", pos = 3, missing = 5), track("t4", "a2", "Else", pos = 1)))
        val defs = tags(JsTag("Live night"), JsTag("Films only", scopes = listOf("MOVIE")))
        return Triple(s, MusicJsTagService(s, defs), defs)
    }

    @Test
    fun an_album_tag_says_nothing_about_its_songs_and_the_songs_facet_does_not_find_them() = runBlocking<Unit> {
        val (s, svc, defs) = fixture()
        svc.add(MusicJsTagRow.ALBUM, listOf("a1"), "Live night")
        val snap = s.snapshot()
        assertEquals(listOf("Live night"), snap.jsTagsOf("a1"))
        assertEquals(emptyList(), snap.jsTagsOf("t1"))
        val defined = defs.all().associate { it.name to it.scopes }
        fun ids(view: String) = MusicBrowse.browse(snap, view, mapOf("tag" to setOf("Live night")), null, emptyMap(), definedTags = defined).let { r -> r.albums.map { it.id } + r.songs.map { it.id } }
        assertEquals(listOf("a1"), ids(MusicBrowse.ALBUMS))
        assertEquals(emptyList(), ids(MusicBrowse.SONGS), "a song does not inherit its album's tag")
        // The facet lists the tags whose scope covers Music (and any a music row carries), never one scoped elsewhere; not on Artists.
        val facet = MusicBrowse.browse(snap, MusicBrowse.ALBUMS, emptyMap(), null, emptyMap(), definedTags = defined).facets.single { it.key == "tag" }
        assertEquals(listOf("Live night"), facet.values.map { it.value })
        assertEquals(1, facet.values.single().count)
        assertTrue(MusicBrowse.browse(snap, MusicBrowse.ARTISTS, emptyMap(), null, emptyMap(), definedTags = defined).facets.none { it.key == "tag" })
        // The rows show their own tags.
        assertEquals(listOf("Live night"), MusicBrowse.browse(snap, MusicBrowse.ALBUMS, emptyMap(), null, emptyMap()).albums.single { it.id == "a1" }.jsTags)
    }

    @Test
    fun apply_to_all_songs_gives_each_present_song_the_tag_skips_one_that_has_it_and_leaves_a_missing_song_alone() = runBlocking<Unit> {
        val (s, svc, _) = fixture()
        svc.add(MusicJsTagRow.ALBUM, listOf("a1"), "Live night")
        svc.add(MusicJsTagRow.TRACK, listOf("t1"), "Live night")
        val p = svc.applyPreview("a1", "Live night")!!
        assertEquals(2, p.songs); assertEquals(1, p.already)
        assertEquals(1, svc.applyToSongs("a1", "Live night"))
        val snap = s.snapshot()
        assertEquals(listOf("Live night"), snap.jsTagsOf("t1")); assertEquals(listOf("Live night"), snap.jsTagsOf("t2"))
        assertEquals(emptyList(), snap.jsTagsOf("t3"), "a missing song is left alone")
        assertEquals(emptyList(), snap.jsTagsOf("t4"), "another album's song is not touched")
        assertEquals(0, svc.applyToSongs("a1", "Live night"), "pressed again, nothing is new")
        // A song added to the album afterwards does not get it until the button is pressed again.
        s.replaceLibrary(MusicLibraryRows("l", emptyList(), emptyList(), listOf(track("t5", "a1", "Five", pos = 5))))
        assertEquals(emptyList(), s.snapshot().jsTagsOf("t5"))
        assertEquals(1, svc.applyToSongs("a1", "Live night"))
    }

    @Test
    fun removing_the_album_tag_leaves_every_songs_copy_and_removing_a_songs_copy_leaves_the_albums() = runBlocking<Unit> {
        val (s, svc, _) = fixture()
        svc.add(MusicJsTagRow.ALBUM, listOf("a1"), "Live night"); svc.applyToSongs("a1", "Live night")
        svc.remove(MusicJsTagRow.ALBUM, listOf("a1"), listOf("Live night"))
        assertEquals(emptyList(), s.snapshot().jsTagsOf("a1"))
        assertEquals(listOf("Live night"), s.snapshot().jsTagsOf("t1")); assertEquals(listOf("Live night"), s.snapshot().jsTagsOf("t2"))
        svc.add(MusicJsTagRow.ALBUM, listOf("a1"), "Live night")
        svc.remove(MusicJsTagRow.TRACK, listOf("t1"), listOf("Live night"))
        assertEquals(listOf("Live night"), s.snapshot().jsTagsOf("a1"))
        assertEquals(listOf("Live night"), s.snapshot().jsTagsOf("t2"))
    }

    @Test
    fun apply_needs_the_tag_on_the_album_and_a_tag_that_is_defined_and_used_for_music() = runBlocking<Unit> {
        val (s, svc, _) = fixture()
        assertEquals(409, assertFailsWith<MusicJsTagRefused> { svc.applyToSongs("a1", "Live night") }.status)   // not on the album
        assertEquals(400, assertFailsWith<MusicJsTagRefused> { svc.add(MusicJsTagRow.ALBUM, listOf("a1"), "Nope") }.status)
        val out = assertFailsWith<MusicJsTagRefused> { svc.add(MusicJsTagRow.TRACK, listOf("t1"), "Films only") }
        assertEquals(409, out.status); assertEquals("Films only is not used for Music", out.message)
        assertTrue(s.snapshot().jsTags.isEmpty())
    }

    // ── tag lifecycle: delete, outside scope (Acceptance 8) ──

    @Test
    fun deleting_a_tag_strips_it_from_albums_and_songs_and_leaves_the_others() = runBlocking<Unit> {
        val (s, svc, _) = fixture()
        svc.add(MusicJsTagRow.ALBUM, listOf("a1", "a2"), "Live night"); svc.add(MusicJsTagRow.TRACK, listOf("t1", "t4"), "Live night")
        s.addJsTags(MusicJsTagWriter.ADMIN, listOf(row("t1", "Other tag")))
        assertEquals(4, svc.count("Live night"))
        assertEquals(4, svc.strip("Live night"))
        assertEquals(0, svc.count("Live night"))
        assertEquals(listOf("Other tag"), s.snapshot().jsTagsOf("t1"))
        assertEquals(0, MusicStore(createDatabase(lastDb)).snapshot().jsTags.values.sumOf { l -> l.count { it.tag == "Live night" } })
    }

    @Test
    fun narrowing_a_scope_removes_nothing_and_the_explicit_removal_takes_exactly_the_music_rows_outside_it() = runBlocking<Unit> {
        val (s, svc, _) = fixture()
        svc.add(MusicJsTagRow.ALBUM, listOf("a1"), "Live night"); svc.add(MusicJsTagRow.TRACK, listOf("t1", "t2"), "Live night")
        s.addJsTags(MusicJsTagWriter.ADMIN, listOf(row("t3", "Live night")))   // a missing song: not counted as present
        assertEquals(emptyList(), svc.outside("Live night", emptyList()), "All")
        assertEquals(emptyList(), svc.outside("Live night", listOf("MOVIE", "music")), "scope still covers Music")
        assertEquals(3, svc.outside("Live night", listOf("MOVIE")).size, "narrowed away from Music: the rows keep the tag")
        assertEquals(4, s.snapshot().jsTags.values.sumOf { it.size })
        assertEquals(3, svc.removeOutside("Live night", listOf("MOVIE")))
        assertEquals(listOf("t3"), s.snapshot().jsTags.keys.toList(), "exactly the present rows went")
    }

    // ── FR-8 ──

    @Test
    fun gone_lists_a_missing_tagged_song_with_title_artist_and_album_and_remove_is_manual() = runBlocking<Unit> {
        val (s, svc, _) = fixture()
        s.addJsTags(MusicJsTagWriter.ADMIN, listOf(row("t3", "Live night")))
        val gone = svc.gone("Live night").single().second
        assertEquals("Gone", gone.title); assertEquals("Harbour Lights", gone.artist); assertEquals("Kite Weather", gone.album)
        svc.remove(MusicJsTagRow.TRACK, listOf("t3"), listOf("Live night"))
        assertTrue(svc.gone("Live night").isEmpty())
    }

    // ── bulk Tag… / Untag… ──

    @Test
    fun the_bulk_preview_counts_what_a_selection_already_carries() = runBlocking<Unit> {
        val (_, svc, _) = fixture()
        svc.add(MusicJsTagRow.TRACK, listOf("t1"), "Live night")
        val p = svc.preview(MusicJsTagRow.TRACK, listOf("t1", "t2", "t3", "t4"))
        assertEquals(3, p.subjects, "a missing song is not part of a selection"); assertEquals(mapOf("Live night" to 1), p.counts)
        assertEquals(2, svc.add(MusicJsTagRow.TRACK, listOf("t1", "t2", "t3", "t4"), "Live night"), "t1 has it already: two are new, the missing song is skipped")
    }

    // ── FR-10 and Acceptance 9: nothing reaches the files, Jellyfin or a viewer ──

    @Test
    fun album_nfo_the_file_rows_and_every_api_tv_answer_are_identical_before_and_after_tagging() = runBlocking<Unit> {
        val (s, svc, _) = fixture()
        fun nfo(): String { val sn = s.snapshot(); val a = sn.albums.getValue("a1"); return MusicNfo.albumXml(a, sn.tracksByAlbum[a.id].orEmpty(), sn.artists) }
        val tv = MusicTvService(s, { null }, JellyfinClient(), ConfigStore(file("tv.toml")).also { it.load() }, kotlin.random.Random(1))
        fun dev(allowed: Set<String>? = null, allowedTags: Set<String> = emptySet(), blocked: Set<String> = emptySet()) =
            DeviceData("d", "tok", "u1", "viewer", "", isAdmin = false, allowedLibraries = allowed, allowedTags = allowedTags, blockedTags = blocked, kind = "phone")
        suspend fun answers() = listOf(
            tv.browse(dev(), "albums", null, 0, null), tv.browse(dev(), "tracks", null, 0, null), tv.browse(dev(), "artists", null, 0, null),
            tv.album(dev(), "a1"), tv.search(dev(), "one"), tv.genres(dev()),
        )
        val rowsBefore = s.snapshot().let { it.albums to it.tracks }
        val nfoBefore = nfo(); val tvBefore = answers()
        svc.add(MusicJsTagRow.ALBUM, listOf("a1"), "Live night"); svc.add(MusicJsTagRow.TRACK, listOf("t1", "t2"), "Live night")
        assertEquals(nfoBefore, nfo(), "album.nfo gets no <tag>")
        assertTrue("Live night" !in nfo())
        assertEquals(rowsBefore, s.snapshot().let { it.albums to it.tracks }, "the rows write_tags reads are unchanged")
        assertEquals(tvBefore, answers(), "/api/tv answers the same")
        // An allowed-tag account still sees no music; a blocked-tag-only account still sees all of it.
        assertEquals(0, tv.browse(dev(allowedTags = setOf("Live night")), "albums", null, 0, null).total)
        assertEquals(tv.browse(dev(), "albums", null, 0, null).total, tv.browse(dev(blocked = setOf("Live night")), "albums", null, 0, null).total)
        assertNull(tv.album(dev(allowedTags = setOf("Live night")), "a1"))
    }
}
