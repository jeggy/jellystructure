package dev.jellystructure.music

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicCredit
import dev.jellystructure.model.MusicMatch
import dev.jellystructure.model.MusicRecording
import dev.jellystructure.model.MusicTrack
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FR-276-1 (DEB-1487) — a track that arrives after its album was matched is mapped by the next `match_musicbrainz` run. */
class MusicNewTracksCatchUpTest {
    private val base = "/tmp/jellystructure-test-newtracks-${getpid()}"
    private lateinit var store: MusicStore
    private lateinit var config: ConfigStore

    private inner class CountingMb(var down: Boolean = false) : MusicBrainzClient(config) {
        val calls = ArrayList<String>()
        private val pressing = Pressings.release("kw-rel", Pressings.ELEVEN)
        override suspend fun releaseGroup(mbid: String): MbReleaseGroup? { calls += "rg:$mbid"; return if (down) null else Pressings.group() }
        override suspend fun releasesOf(releaseGroupMbid: String): List<MbRelease>? { calls += "releases:$releaseGroupMbid"; return if (down) null else listOf(pressing) }
        override suspend fun release(mbid: String): MbRelease? { calls += "release:$mbid"; return if (down) null else pressing.takeIf { it.id == mbid } }
        override suspend fun releaseWithRels(mbid: String): MbRelease? = null
        override suspend fun recording(mbid: String): MbRecording? = null
        override suspend fun recordingRels(mbid: String): MbRecording? = null
        override suspend fun recordingCredit(mbid: String): MbRecording? = null
        override suspend fun artist(mbid: String): MbArtist? = null
        override suspend fun searchReleaseGroups(artist: String, album: String, limit: Int): List<MbReleaseGroup>? = emptyList()
    }

    private fun file(pos: Int, tag: String? = null, state: String? = null, rec: String? = null) = MusicTrack(
        id = "kw-$pos", albumId = "kw", libraryId = "lib", title = "Song $pos", disc = 1, position = pos, durationMs = 200_000L,
        path = "/music/kw-$pos.flac", artists = listOf(MusicCredit("hl", "Harbour Lights")),
        jellyfinProviderIds = if (tag != null) mapOf("MusicBrainzRecording" to tag) else emptyMap(),
        recordingMbid = rec, recordingState = state,
    )

    /** The 11 songs' own files carry the release's recordings; a position past 11 is off the release. */
    private fun arriving(range: IntRange) = range.map { file(it, tag = if (it <= 11) "kw-r$it" else "x-$it") }

    @BeforeTest fun setUp() { runBlocking {
        runCatching { platform.posix.remove("$base.db") }
        store = MusicStore(createDatabase("$base.db"))
        config = ConfigStore("$base.toml").also { it.load() }
        val lib = EditionsFixture.library()
        val album = MusicAlbum(id = "kw", libraryId = "lib", title = "Kite Weather", albumArtists = listOf(MusicCredit("hl", "Harbour Lights")))
        store.replaceLibrary(MusicLibraryRows("lib", lib.artists.filter { it.id == "hl" }, listOf(album), arriving(1..5)))
    } }

    @AfterTest fun tearDown() { listOf("$base.db", "$base.db-wal", "$base.db-shm", "$base.toml").forEach { runCatching { platform.posix.remove(it) } } }

    private fun svc(mb: MusicBrainzClient) = MusicMatchService(store, mb, AcoustIdClient({ "" }), config, { null })

    /** The album matched with the five files it had, then [more] arrived. */
    private suspend fun matchedThen(more: List<MusicTrack>, lock: Boolean = false) {
        assertTrue(svc(CountingMb()).applyMatch("kw", "rg-kw", "kw-rel", "search", lock) != null)
        store.putTracks(more)
    }

    private fun tracks() = store.snapshot().tracksByAlbum["kw"].orEmpty()

    @Test
    fun a_track_added_after_the_match_is_mapped_on_the_next_run() = runBlocking<Unit> {
        matchedThen(arriving(6..16))
        assertEquals(11, tracks().count { it.recordingState == null })
        val s = svc(CountingMb()).matchAlbums(null, scopeAll = false)
        assertEquals(1, s.mapped); assertTrue(s.sentence().contains("new tracks mapped on 1 album"), s.sentence())
        assertTrue(tracks().none { it.recordingState == null })
        val layout = MusicOfficial.layout(store.album("kw")!!, tracks().sortedBy { it.position }, MusicOfficial.Fill())
        assertEquals(11, layout.rows.size); assertEquals(5, layout.extras.size); assertEquals(0, layout.gaps.size)
        assertEquals(MusicRecording.AGREES, store.track("kw-9")!!.recordingState); assertEquals("kw-r9", store.track("kw-9")!!.recordingMbid)
    }

    @Test
    fun a_locked_album_still_maps_its_new_tracks() = runBlocking<Unit> {
        matchedThen(arriving(6..11), lock = true)
        assertTrue(store.album("kw")!!.matchLocked)
        assertEquals(1, svc(CountingMb()).matchAlbums(null, scopeAll = false).mapped)
        assertEquals(11, tracks().count { it.recordingState == MusicRecording.AGREES })
        assertTrue(store.album("kw")!!.matchLocked)
    }

    @Test
    fun a_track_off_the_release_takes_its_file_tag_as_disagrees() = runBlocking<Unit> {
        matchedThen(arriving(12..12))
        svc(CountingMb()).matchAlbums(null, scopeAll = false)
        val t = store.track("kw-12")!!
        assertEquals("x-12", t.recordingMbid); assertEquals(MusicRecording.DISAGREES, t.recordingState); assertNull(t.releaseTrackMbid)
        // It is an extra for 305 (its recording is not on the official list), and it never folds (rule 1 needs AGREES).
        assertFalse(MusicOfficial.isOnList(store.album("kw")!!.official, t))
    }

    @Test
    fun manual_and_already_mapped_tracks_are_untouched() = runBlocking<Unit> {
        matchedThen(listOf(file(6, tag = "kw-r6", state = MusicRecording.MANUAL, rec = "chosen-by-hand")) + arriving(7..8))
        val before = tracks().filter { it.position != 7 && it.position != 8 }.associateBy { it.id }
        val albumBefore = store.album("kw")!!
        svc(CountingMb()).matchAlbums(null, scopeAll = false)
        for ((id, t) in before) assertEquals(t, store.track(id), id)
        val after = store.album("kw")!!
        assertEquals(albumBefore.releaseMbid, after.releaseMbid); assertEquals(albumBefore.matchedAt, after.matchedAt)
        assertEquals(albumBefore.release, after.release); assertEquals(albumBefore.mbArtists, after.mbArtists); assertEquals(albumBefore.matchSource, after.matchSource)
        assertEquals(MusicMatch.MATCHED, after.matchState)
        assertEquals(MusicRecording.AGREES, store.track("kw-7")!!.recordingState)
    }

    @Test
    fun a_second_run_with_nothing_unmapped_asks_musicbrainz_nothing() = runBlocking<Unit> {
        // Track 30 is on no position of the release and carries no tag: nothing can map it, so it must not cost a request per run.
        matchedThen(arriving(6..8) + file(30))
        val mb = CountingMb()
        val svc = svc(mb)
        assertEquals(1, svc.matchAlbums(null, scopeAll = false).mapped)
        assertTrue(mb.calls.any { it.startsWith("release:") })
        assertNull(store.track("kw-30")!!.recordingState)
        mb.calls.clear()
        assertEquals(0, svc.matchAlbums(null, scopeAll = false).mapped)
        assertEquals(emptyList(), mb.calls)
    }

    /** A present file with no track number whose tags name this release's track [n] (DEB-1639). */
    private fun numberless(n: Int, seconds: Long = 200, state: String? = null, rec: String? = null) = file(n, tag = "kw-r$n", state = state, rec = rec).copy(
        id = "nn-$n", position = null, durationMs = seconds * 1000L, path = "/music/nn-$n.flac",
        jellyfinProviderIds = mapOf("MusicBrainzRecording" to "kw-r$n", "MusicBrainzTrack" to "kw-rel-t0-${n - 1}"),
    )

    @Test
    fun a_track_with_no_number_is_placed_by_its_release_track_id_on_the_catch_up() = runBlocking<Unit> {
        matchedThen(listOf(numberless(6), numberless(7, seconds = 205)))
        assertEquals(1, svc(CountingMb()).matchAlbums(null, scopeAll = false).mapped)
        val a = store.track("nn-6")!!
        assertEquals(MusicRecording.AGREES, a.recordingState); assertEquals("kw-r6", a.recordingMbid); assertEquals("kw-rel-t0-5", a.releaseTrackMbid)
        assertEquals(MusicRecording.DISAGREES, store.track("nn-7")!!.recordingState)   // 5 s off
        assertEquals("kw-rel-t0-6", store.track("nn-7")!!.releaseTrackMbid)
    }

    @Test
    fun a_disagrees_track_with_no_number_is_re_mapped_once_and_not_asked_about_again() = runBlocking<Unit> {
        // The state DEB-1488 left: the file's tag as a disagrees recording, no release track.
        matchedThen(listOf(numberless(6, state = MusicRecording.DISAGREES, rec = "kw-r6"), numberless(7, state = MusicRecording.DISAGREES, rec = "kw-r7")
            .copy(jellyfinProviderIds = mapOf("MusicBrainzTrack" to "not-on-this-release"))))
        val mb = CountingMb()
        val svc = svc(mb)
        assertEquals(1, svc.matchAlbums(null, scopeAll = false).mapped)
        assertEquals(MusicRecording.AGREES, store.track("nn-6")!!.recordingState); assertEquals("kw-rel-t0-5", store.track("nn-6")!!.releaseTrackMbid)
        assertEquals(MusicRecording.DISAGREES, store.track("nn-7")!!.recordingState); assertNull(store.track("nn-7")!!.releaseTrackMbid)   // placed by nothing: as it was
        mb.calls.clear()
        assertEquals(0, svc.matchAlbums(null, scopeAll = false).mapped)
        assertEquals(emptyList(), mb.calls)
    }

    @Test
    fun a_manual_track_with_no_number_is_untouched() = runBlocking<Unit> {
        val manual = numberless(6, state = MusicRecording.MANUAL, rec = "chosen-by-hand")
        matchedThen(listOf(manual))
        svc(CountingMb()).matchAlbums(null, scopeAll = false)
        assertEquals(manual, store.track("nn-6"))
    }

    @Test
    fun applying_a_match_places_a_track_with_no_number_by_its_ids_and_leaves_the_pick_alone() = runBlocking<Unit> {
        store.putTracks(listOf(numberless(8)))
        assertTrue(svc(CountingMb()).applyMatch("kw", "rg-kw", "kw-rel", "search", false) != null)
        assertEquals("kw-rel-t0-7", store.track("nn-8")!!.releaseTrackMbid); assertEquals(MusicRecording.AGREES, store.track("nn-8")!!.recordingState)
        assertEquals(5, store.album("kw")!!.release!!.agreeing)   // the five positioned files; the numberless one is not counted
    }

    @Test
    fun an_unreachable_musicbrainz_leaves_the_tracks_unmapped() = runBlocking<Unit> {
        matchedThen(arriving(6..8))
        val down = CountingMb(down = true)
        val s = svc(down).matchAlbums(null, scopeAll = false)
        assertEquals(0, s.mapped)
        assertEquals(3, tracks().count { it.recordingState == null })
        assertEquals(1, svc(CountingMb()).matchAlbums(null, scopeAll = false).mapped)   // retried on the next run
        assertTrue(tracks().none { it.recordingState == null })
    }
}
