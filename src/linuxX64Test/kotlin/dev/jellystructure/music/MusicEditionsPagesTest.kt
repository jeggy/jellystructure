package dev.jellystructure.music

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.model.MusicMatch
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.ops.GateClassKind
import dev.jellystructure.ops.currentGateClass
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 305 (test 15) — the album page DTO, built by the pure [MusicAlbumPage.build]. */
class MusicAlbumPageTest {
    private val lib = EditionsFixture.library()

    @Test
    fun kite_weather_reads_official_then_extras_then_singles() {
        val p = MusicAlbumPage.build(lib.snapshot(), "kw")!!
        assertEquals((1..11).map { "$it" }, p.tracks.take(11).map { it.number })
        val extra = p.tracks.last()
        assertEquals("Lantern Swing", extra.title); assertNull(extra.number); assertTrue(extra.extra)
        assertEquals("the Japanese CD, 2006", extra.firstOn)
        val o = p.official!!
        assertEquals(11, o.songs); assertEquals(1, o.extras); assertEquals(7, o.k); assertEquals(9, o.m)
        assertEquals("Extras · Japan · 1", o.divider, "owner decision 3: no title or disambiguation ⇒ the country")
        assertEquals(listOf("s-nl", "s-fb", "s-salt", "s-lt", "s-tw"), p.singles.map { it.id })
        assertEquals(listOf("mb_single_from", "mb_single_from", "mb_single_from", "by_title", "via_remix"), p.singles.map { it.how })
        assertEquals(13, p.bsides.size)
        assertEquals("Northern Line", p.bsides.first().single)
    }

    @Test
    fun the_header_length_is_the_official_albums() {
        assertEquals((0 until 11).sumOf { 260_000L + it * 1000 }, MusicAlbumPage.build(lib.snapshot(), "kw")!!.official!!.lengthMs)
    }

    @Test
    fun a_gap_row_carries_disc_position_title_length() {
        val p = MusicAlbumPage.build(lib.copy(tracks = lib.tracks.filter { it.id != "kw-5" }).snapshot(), "kw")!!
        val gap = p.gaps.single()
        assertEquals(4, gap.slot); assertEquals("5", gap.number); assertEquals("Low Tide", gap.title)
        assertEquals(1, gap.disc); assertEquals(5, gap.position); assertEquals(264_000L, gap.lengthMs)
    }

    @Test
    fun a_singles_own_page_says_single_from() {
        val p = MusicAlbumPage.build(lib.snapshot(), "s-nl")!!
        assertTrue(p.isSingle)
        assertEquals("kw", p.singleFrom?.albumId); assertEquals("Kite Weather", p.singleFrom?.title); assertEquals("MusicBrainz", p.singleFrom?.howLabel)
        assertTrue(p.moveTargets.any { it.id == "sf" })
        assertEquals("none", MusicAlbumPage.build(lib.snapshot(), "s-hr")!!.singleFrom?.how, "a single on no album says so")
    }

    @Test
    fun an_unmatched_album_is_todays_page() {
        val plain = lib.copy(albums = lib.albums.map { if (it.id == "kw") it.copy(matchState = MusicMatch.UNMATCHED, releaseGroupMbid = null, primaryType = null) else it })
        val p = MusicAlbumPage.build(plain.snapshot(), "kw")!!
        assertNull(p.official); assertTrue(p.gaps.isEmpty()); assertTrue(p.singles.isEmpty()); assertTrue(p.bsides.isEmpty())
        assertEquals((1..12).map { "kw-$it" }, p.tracks.map { it.id })
        assertTrue(p.tracks.none { it.extra || it.number != null })
    }
}

/** Phase 305 (test 16, FR-305-4) — *Use as the official album* and *Back to automatic*. */
class MusicOfficialPickTest {
    private val base = "/tmp/jellystructure-test-editions-pick-${getpid()}"
    @AfterTest fun tearDown() { listOf("$base.db", "$base.db-wal", "$base.db-shm", "$base.toml").forEach { runCatching { platform.posix.remove(it) } } }

    private inner class Mb(cfg: ConfigStore) : MusicBrainzClient(cfg) {
        val jp = Pressings.release("kw-rel-jp", Pressings.ELEVEN + "kw-r12", "2006-03-08", country = "JP").copy(releaseGroup = MbReleaseGroup("rg-kw", "Kite Weather"))
        val anniversary = Pressings.release("kw-anniv", Pressings.ELEVEN + (1..13).map { "kw-new-$it" }, "2016", title = "Kite Weather 10th Anniversary")
        override suspend fun release(mbid: String): MbRelease? = when (mbid) { "kw-rel-jp" -> jp; "other" -> jp.copy(id = "other", releaseGroup = MbReleaseGroup("rg-sf")); else -> null }
        override suspend fun releaseGroup(mbid: String): MbReleaseGroup? = Pressings.group()
        override suspend fun releasesOf(releaseGroupMbid: String): List<MbRelease> =
            (1..7).map { Pressings.release("kw-gb-$it", Pressings.ELEVEN, "2006") } + jp + anniversary
        override suspend fun recording(mbid: String): MbRecording? = null
    }

    private fun setUp(): Pair<MusicStore, MusicMatchService> = runBlocking {
        runCatching { platform.posix.remove("$base.db") }
        val store = MusicStore(createDatabase("$base.db"))
        store.replaceLibrary(EditionsFixture.library().rows())
        val cfg = ConfigStore("$base.toml").also { it.load() }
        store to MusicMatchService(store, Mb(cfg), AcoustIdClient({ "" }), cfg, { null })
    }

    @Test
    fun picking_the_japanese_cd_and_back_to_automatic() = runBlocking<Unit> {
        val (store, svc) = setUp()
        assertTrue(svc.pickOfficial("kw", "kw-rel-jp") is MusicPickOutcome.Picked)
        val p = MusicAlbumPage.build(store.snapshot(), "kw")!!
        assertEquals("12", p.tracks.single { it.title == "Lantern Swing" }.number)
        assertNull(p.official!!.divider); assertTrue(p.official!!.chosenByYou)
        assertTrue(svc.pickOfficial("kw", "other") is MusicPickOutcome.NotOfThisAlbum)
        svc.unpickOfficial("kw")
        val back = MusicAlbumPage.build(store.snapshot(), "kw")!!
        assertNull(back.tracks.single { it.title == "Lantern Swing" }.number)
        assertEquals(11, back.official!!.songs); assertFalse(back.official!!.chosenByYou)
    }

    @Test
    fun a_pressing_whose_extras_are_not_held_is_listed_not_pickable() = runBlocking<Unit> {
        val (_, svc) = setUp()
        val list = svc.pressings("kw")!!
        val anniv = list.single { it.option.mbid == "kw-anniv" }
        assertFalse(anniv.pickable); assertEquals(13, anniv.notInLibrary); assertEquals("+13 · −0", anniv.against)
        assertTrue(list.single { it.option.mbid == "kw-rel-jp" }.pickable)
        assertEquals("same as the official", list.single { it.option.mbid == "kw-gb-1" }.against)
    }
}

/** Phase 305 (test 18, FR-305-13) — Library → Songs, folded and with every copy. */
class MusicSongFoldTest {
    private val full = EditionsFixture.library()
    private val small = full.copy(tracks = full.tracks.filter { it.id in setOf("kw-1", "kw-2", "kw-3", "s-nl-1", "nn-1", "s-hr-1", "s-bc-1") } +
        EditionsFixture.track("x1", "nn", 5, "Paper Boats (live)", null) + EditionsFixture.track("x2", "nn", 6, "Paper Boats (live)", null, bitrate = 128_000, codec = "wmav2").copy(container = "asf"))
    private fun snap() = small.snapshot(sound = listOf(MusicSoundPair("kw-2", "nn-1", 0.05, 0.99, 0)), sameSong = mapOf(("x1" to "x2") to MusicSameSong.SAME))

    @Test
    fun songs_fold_and_show_every_copy_lists_every_file() {
        val folded = MusicBrowse.browse(snap(), MusicBrowse.SONGS, emptyMap(), null, emptyMap())
        assertEquals(6, folded.rowsTotal); assertEquals(3, folded.folded)
        assertEquals(2, folded.songs.single { it.id == "kw-2" }.alsoOn)
        val every = MusicBrowse.browse(snap(), MusicBrowse.SONGS, emptyMap(), null, emptyMap(), everyCopy = true)
        assertEquals(9, every.rowsTotal)
        val byId = every.songs.associateBy { it.id }
        assertEquals("same recording · MusicBrainz", byId["s-nl-1"]?.reason); assertEquals("kw-2", byId["s-nl-1"]?.copyOf)
        assertEquals("sounds the same", byId["nn-1"]?.reason)
        assertEquals("you said so", byId["x2"]?.reason)
        val ids = every.songs.map { it.id }
        assertEquals(ids.indexOf("kw-2") + 1, ids.indexOf("s-nl-1"), "the other copies sit under their song")
    }

    @Test
    fun facets_test_the_copy_shown_when_folded_and_each_file_with_every_copy() {
        val wma = mapOf("format" to setOf("WMA"))
        assertEquals(0, MusicBrowse.browse(snap(), MusicBrowse.SONGS, wma, null, emptyMap()).rowsTotal)
        assertEquals(1, MusicBrowse.browse(snap(), MusicBrowse.SONGS, wma, null, emptyMap(), everyCopy = true).rowsTotal)
    }

    @Test
    fun artist_songs_fold_album_rows_keep_their_own_tracks_and_health_counts_files() {
        val s = full.snapshot()
        val hl = MusicBrowse.browse(s, MusicBrowse.ARTISTS, emptyMap(), null, emptyMap()).artists.single { it.id == "hl" }
        val hlFiles = full.tracks.count { it.artists.any { c -> c.artistId == "hl" } }
        assertTrue(hl.songs < hlFiles, "${hl.songs} songs of $hlFiles files")
        assertEquals(hlFiles - 5, hl.songs, "Northern Line twice more, Fog Bank, Salt on the Window and Lantern Swing once more each fold")
        val kw = MusicBrowse.browse(s, MusicBrowse.ALBUMS, emptyMap(), null, emptyMap()).albums.single { it.id == "kw" }
        assertEquals(11, kw.songs); assertEquals(1, kw.extras)
    }

    @Test
    fun bonus_after_the_version_chips_per_copy_with_every_copy() {
        val s = full.snapshot()
        assertTrue(MusicBrowse.browse(s, MusicBrowse.SONGS, emptyMap(), "Lantern", emptyMap()).songs.single().bonus)
        val every = MusicBrowse.browse(s, MusicBrowse.SONGS, emptyMap(), "Lantern", emptyMap(), everyCopy = true).songs.associateBy { it.id }
        assertTrue(every["kw-12"]!!.bonus); assertFalse(every["s-fb-5"]!!.bonus, "the single's copy is no extra")
    }
}

/** Phase 305 (test 20, FR-305-14) — *Songs that may be the same*. */
class MusicSameSongDashboardTest {
    private val lib = EditionsFixture.library()
    private val pairs = listOf(MusicSoundPair("kw-5", "s-lt-1", 0.05, 0.98, 1500), MusicSoundPair("ep-0-1", "kw-5", 0.08, 0.97, 0), MusicSoundPair("kw-6", "s-tw-1", 0.1, 0.96, -800))

    @Test
    fun three_pairs_then_none() {
        var decided = emptyMap<Pair<String, String>, String>()
        val row = MusicAlbumPage.sameSongsRow(lib.snapshot(sound = pairs))!!
        assertEquals(3, row.count); assertEquals("info", row.severity); assertEquals("pair", row.unit); assertEquals("here", row.fix)
        assertEquals("same_songs", row.opens); assertNull(row.actionId, "it opens, it does not post")
        for ((i, p) in pairs.withIndex()) {
            decided = decided + ((p.a to p.b) to if (i == 0) MusicSameSong.SAME else MusicSameSong.NOT_SAME)
            val r = MusicAlbumPage.sameSongsRow(lib.snapshot(sound = pairs, sameSong = decided))
            if (i < 2) assertEquals(2 - i, r?.count) else assertNull(r)
        }
    }

    @Test
    fun no_never_returns_the_pair() {
        val s = lib.snapshot(sound = pairs, sameSong = mapOf(("kw-5" to "s-lt-1") to MusicSameSong.NOT_SAME))
        assertTrue(s.songs.suggestions.none { it.a == "kw-5" && it.b == "s-lt-1" })
        assertTrue(MusicCompareSongs.candidates(s).none { it.a.id == "kw-5" && it.b.id == "s-lt-1" })
    }

    @Test
    fun a_pair_carries_the_offset_so_both_players_start_together() {
        val p = MusicAlbumPage.suggestions(lib.snapshot(sound = pairs)).single { it.a.trackId == "kw-5" && it.b.trackId == "s-lt-1" }
        assertEquals(1500, p.offsetMs)
        assertEquals("Kite Weather", p.a.album)
    }

    @Test
    fun a_file_a_browser_cannot_play_has_its_player_disabled_with_the_reason() {
        val wma = lib.copy(tracks = lib.tracks.map { if (it.id == "s-lt-1") it.copy(container = "asf", codec = "wmav2") else it })
        val side = MusicAlbumPage.suggestions(wma.snapshot(sound = pairs)).single { it.b.trackId == "s-lt-1" }.b
        assertFalse(side.playable); assertNotNull(side.why)
    }
}

/** Phase 305 (test 21, FR-305-15) — the artist page's *Singles & EPs*. */
class MusicSingleHomeStoreTest {
    private val base = "/tmp/jellystructure-test-editions-home-${getpid()}"
    @AfterTest fun tearDown() { listOf("$base.db", "$base.db-wal", "$base.db-shm").forEach { runCatching { platform.posix.remove(it) } } }

    private fun standAlone(s: MusicStore.Snapshot, type: String): List<String> {
        val homed = s.editions.homes.filterValues { it.albumId != null }.keys
        return MusicBrowse.browse(s, MusicBrowse.ALBUMS, emptyMap(), null, emptyMap()).albums
            .filter { it.artistId == "hl" && it.type == type && it.id !in homed }.map { it.id }
    }

    @Test
    fun the_artist_page_keeps_six_singles_and_eps() {
        assertEquals(setOf("s-hr", "s-bc", "ep-0", "ep-1", "ep-2", "s-st"), standAlone(EditionsFixture.library().snapshot(), "single").toSet())
    }

    @Test
    fun singles_under_names_the_album_and_count() {
        val rows = MusicAlbumPage.singlesUnder(EditionsFixture.library().snapshot(), listOf("sf", "kw"))
        assertEquals(listOf(dev.jellystructure.model.MusicSinglesUnderRow("kw", "Kite Weather", 5)), rows)
    }

    @Test
    fun a_homed_live_single_leaves_the_live_group() = runBlocking<Unit> {
        runCatching { platform.posix.remove("$base.db") }
        val store = MusicStore(createDatabase("$base.db"))
        val lib = EditionsFixture.library()
        val live = EditionsFixture.album("s-live", "Small Boats (Live)", 2007, primary = "Single", secondary = listOf("Live"))
        store.replaceLibrary(lib.copy(albums = lib.albums + live, tracks = lib.tracks + EditionsFixture.track("s-live-1", "s-live", 1, "Small Boats (live)", "sb-live")).rows())
        assertTrue("s-live" in standAlone(store.snapshot(), "live"))
        store.putHome("s-live", "kw", 5)
        assertFalse("s-live" in standAlone(store.snapshot(), "live"))
        assertEquals(6, MusicAlbumPage.singlesUnder(store.snapshot(), listOf("kw")).single().count)
        store.putHome("s-live", null, 6, clear = true)
        assertTrue("s-live" in standAlone(MusicStore(createDatabase("$base.db")).snapshot(), "live"), "back to automatic, and kept on disk")
    }
}

/** Phase 305 (test 13) — the on-disk fingerprint cache. */
class MusicFingerprintCacheTest {
    private val dir = "/tmp/jellystructure-test-editions-fp-${getpid()}"
    @AfterTest fun tearDown() { platform.posix.system("rm -rf '$dir'") }

    @Test
    fun an_unchanged_file_is_never_fingerprinted_twice() = runBlocking<Unit> {
        var computed = 0
        val cache = MusicFingerprintCache(dir, { computed++; listOf(1, 2, 3) }, { 100L to 7L })
        val t = MusicTrack(id = "kw-1", title = "Kite Weather", path = "/music/kw-1.mp3")
        assertEquals(listOf(1, 2, 3) to true, cache.get(t))
        assertEquals(listOf(1, 2, 3) to false, cache.get(t))
        assertEquals(1, computed)
        assertTrue(cache.has(t))
    }

    @Test
    fun a_changed_size_or_mtime_misses_by_name() = runBlocking<Unit> {
        var stamp = 100L to 7L
        var computed = 0
        val cache = MusicFingerprintCache(dir, { computed++; listOf(computed) }, { stamp })
        val t = MusicTrack(id = "kw-2", title = "Northern Line", path = "/music/kw-2.mp3")
        cache.get(t)
        stamp = 100L to 8L
        assertFalse(cache.has(t))
        assertEquals(listOf(2) to true, cache.get(t))
        stamp = 101L to 8L
        cache.get(t)
        assertEquals(3, computed)
    }
}

/** Phase 305 (test 14) — `compare_songs` on a store, with a fake fingerprinter. */
class MusicCompareSongsTest {
    private val base = "/tmp/jellystructure-test-editions-compare-${getpid()}"
    @AfterTest fun tearDown() {
        listOf("$base.db", "$base.db-wal", "$base.db-shm").forEach { runCatching { platform.posix.remove(it) } }
        platform.posix.system("rm -rf '$base-fp'")
    }

    private val song = Random(11).let { r -> List(1200) { r.nextInt() } }
    private val other = Random(12).let { r -> List(1200) { r.nextInt() } }

    @Test
    fun compare_songs_writes_ber_coverage_and_offset_per_candidate_pair() = runBlocking<Unit> {
        runCatching { platform.posix.remove("$base.db") }
        val store = MusicStore(createDatabase("$base.db"))
        store.replaceLibrary(EditionsFixture.library().rows())
        val computed = ArrayList<String>()
        var gate: GateClassKind? = null
        val cache = MusicFingerprintCache("$base-fp", { path ->
            gate = currentGateClass(); computed += path
            // Low Tide's album copy and its radio edit and the EP's Low Tide sound the same; everything else differs.
            if ("kw-5" in path || "s-lt-1" in path || "ep-0-1" in path) song else other.shuffled(Random(path.hashCode()))
        }, { 1L to 1L })
        val step = MusicCompareSongsStep(store, cache)
        val first = step.run()
        val pairs = store.snapshot().soundPairs.associateBy { it.a to it.b }
        val lt = pairs["kw-5" to "s-lt-1"]!!
        assertEquals(0.0, lt.ber); assertEquals(1.0, lt.coverage); assertEquals(0L, lt.offsetMs)
        assertTrue(first.suggestions >= 2, first.sentence())
        assertEquals(GateClassKind.BACKGROUND, gate, "it asks for the background process class")
        // A second run: nothing new or changed, so nothing is fingerprinted or measured again.
        val n = computed.size
        val second = step.run()
        assertEquals(n, computed.size)
        assertEquals(0, second.measured)
    }
}
