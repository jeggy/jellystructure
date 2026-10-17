package dev.jellystructure.music

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.MusicSteps
import dev.jellystructure.config.PipelineStep
import dev.jellystructure.db.createDatabase
import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicArtist
import dev.jellystructure.model.MusicCandidate
import dev.jellystructure.model.MusicCredit
import dev.jellystructure.model.MusicGenrePick
import dev.jellystructure.model.MusicGenreVote
import dev.jellystructure.model.MusicMatch
import dev.jellystructure.model.MusicRecording
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.nowEpochSec
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicMatchTest {
    // ── scoring (pure) ──

    private fun track(id: String, pos: Int, seconds: Int, disc: Int? = 1, title: String = "Song $pos") =
        MusicTrack(id = id, albumId = "alb", libraryId = "lib", title = title, disc = disc, position = pos, durationMs = seconds * 1000L,
            artists = listOf(MusicCredit("ja1", "Harbour Lights")), path = "/music/$id.mp3")

    private fun release(id: String, vararg lengthsSec: Int, status: String = "Official") = MbRelease(
        id = id, title = "Salt", country = "NO", date = "2004", status = status,
        labelInfo = listOf(MbLabelInfo(MbLabel("Fjord Records"))),
        media = listOf(MbMedium(position = 1, format = "CD", trackCount = lengthsSec.size, tracks = lengthsSec.mapIndexed { i, s ->
            MbTrack(id = "$id-t${i + 1}", position = i + 1, title = "Song ${i + 1}", length = s * 1000L,
                recording = MbRecording(id = "$id-rec${i + 1}", title = "Song ${i + 1}", length = s * 1000L),
                artistCredit = listOf(MbArtistCredit("Harbour Lights", "", MbArtistRef("mb-a1", "Harbour Lights"))))
        })),
        artistCredit = listOf(MbArtistCredit("Harbour Lights", "", MbArtistRef("mb-a1", "Harbour Lights"))),
    )

    @Test
    fun a_partial_album_agrees_on_what_is_on_disk() {
        // Tracks 2 and 18 of an 18-track release — the household's own shape.
        val disk = listOf(track("a", 2, 200), track("b", 18, 241))
        val rel = release("r1", *IntArray(18) { if (it == 17) 240 else 200 })
        val a = MusicScoring.agreement(rel, disk)
        assertEquals(2, a.agreeing); assertEquals(2, a.total)
        val off = MusicScoring.agreement(release("r2", *IntArray(18) { 150 }), disk)
        assertEquals(0, off.agreeing); assertEquals(91, off.offMaxSec)
    }

    // ── FR-276-5a (DEB-1639): a track with no position is placed by its own ids ──

    private fun bare(id: String, seconds: Int?, vararg ids: Pair<String, String>, title: String = "Song", state: String? = null) =
        MusicTrack(id = id, albumId = "alb", libraryId = "lib", title = title, durationMs = seconds?.let { it * 1000L },
            jellyfinProviderIds = mapOf(*ids), recordingState = state, path = "/music/$id.mp3")

    private fun place(rel: MbRelease, vararg disk: MusicTrack) = MusicScoring.placeById(rel, disk.toList(), MusicScoring.agreement(rel, disk.toList()))

    @Test
    fun a_position_less_track_is_found_by_its_release_track_id() {
        val rel = release("r1", 200, 240)
        val hit = place(rel, bare("a", 200, "MusicBrainzTrack" to "r1-t2", title = "Song 2"))["a"]!!
        assertEquals("r1-t2", hit.mb?.id); assertTrue(!hit.agrees)   // 240 s on the release, 200 s on disk
        assertTrue(place(rel, bare("b", 240, "MusicBrainzTrack" to "r1-t2"))["b"]!!.agrees)
    }

    @Test
    fun a_length_five_seconds_off_disagrees_and_a_missing_length_is_judged_by_title() {
        val rel = release("r1", 200, 240)
        assertTrue(!place(rel, bare("a", 245, "MusicBrainzTrack" to "r1-t2"))["a"]!!.agrees)
        assertTrue(place(rel, bare("b", 242, "MusicBrainzTrack" to "r1-t2"))["b"]!!.agrees)
        assertTrue(place(rel, bare("c", null, "MusicBrainzTrack" to "r1-t2", title = "Song 2"))["c"]!!.agrees)
        assertTrue(!place(rel, bare("d", null, "MusicBrainzTrack" to "r1-t2", title = "Other"))["d"]!!.agrees)
    }

    @Test
    fun the_recording_id_places_a_track_only_when_exactly_one_release_track_has_it() {
        val rel = release("r1", 200, 240)
        assertEquals("r1-t1", place(rel, bare("a", 200, "MusicBrainzRecording" to "r1-rec1"))["a"]?.mb?.id)
        val twice = rel.copy(media = rel.media.map { m -> m.copy(tracks = m.tracks + m.tracks[0].copy(id = "r1-t9", position = 3)) })
        assertNull(place(twice, bare("a", 200, "MusicBrainzRecording" to "r1-rec1"))["a"])
        // The release-track id wins over the recording id.
        assertEquals("r1-t2", place(twice, bare("b", 240, "MusicBrainzTrack" to "r1-t2", "MusicBrainzRecording" to "r1-rec1"))["b"]?.mb?.id)
    }

    @Test
    fun an_id_that_is_not_on_the_release_places_nothing() {
        val rel = release("r1", 200, 240)
        assertTrue(place(rel, bare("a", 200, "MusicBrainzTrack" to "elsewhere", "MusicBrainzRecording" to "elsewhere-rec")).isEmpty())
    }

    @Test
    fun a_manual_track_and_a_position_hit_are_not_placed_by_id() {
        val rel = release("r1", 200, 240)
        assertTrue(place(rel, bare("m", 200, "MusicBrainzTrack" to "r1-t1", state = MusicRecording.MANUAL)).isEmpty())
        val positioned = track("p", 1, 200).copy(jellyfinProviderIds = mapOf("MusicBrainzTrack" to "r1-t2"))
        assertTrue(place(rel, positioned).isEmpty())          // position 1 hit track 1; the id is not consulted
        // A position past the release's end finds nothing, so the id places it.
        val past = track("q", 9, 240).copy(jellyfinProviderIds = mapOf("MusicBrainzTrack" to "r1-t2"))
        assertEquals("r1-t2", place(rel, past)["q"]?.mb?.id)
    }

    @Test
    fun placing_by_id_never_changes_the_agreement_the_pick_rule_counts() {
        val rel = release("r1", 200, 240)
        val disk = listOf(bare("a", 240, "MusicBrainzTrack" to "r1-t2"))
        val a = MusicScoring.agreement(rel, disk)
        assertEquals(0, a.agreeing); assertEquals(0, a.total)
    }

    @Test
    fun no_disc_number_reads_as_disc_one_and_a_title_decides_without_lengths() {
        val disk = listOf(track("a", 1, 200, disc = null), MusicTrack(id = "b", albumId = "alb", title = "Song 2", disc = null, position = 2))
        assertEquals(2, MusicScoring.agreement(release("r1", 200, 999), disk).agreeing)
    }

    private fun cand(rg: String, agreeing: Int, total: Int = 2, score: Int = 100) =
        MusicCandidate(releaseGroupMbid = rg, title = rg, score = score, agreeing = agreeing, total = total)

    @Test
    fun the_pick_rule_from_the_dry_run() {
        assertIs<MusicScoring.Decision.Pick>(MusicScoring.decide(listOf(cand("a", 2), cand("b", 0))))
        assertIs<MusicScoring.Decision.Pick>(MusicScoring.decide(listOf(cand("a", 1, total = 2), cand("b", 0))))   // half is enough
        assertIs<MusicScoring.Decision.NeedsYou>(MusicScoring.decide(listOf(cand("a", 2), cand("b", 2))))           // a tie
        assertIs<MusicScoring.Decision.NeedsYou>(MusicScoring.decide(listOf(cand("a", 1, total = 5))))            // too few agree
        assertIs<MusicScoring.Decision.NeedsYou>(MusicScoring.decide(listOf(cand("a", 2, score = 60))))           // unsure name
        assertIs<MusicScoring.Decision.Unmatched>(MusicScoring.decide(listOf(cand("a", 0), cand("b", 0))))        // never usable
        assertIs<MusicScoring.Decision.Unmatched>(MusicScoring.decide(emptyList()))
    }

    @Test
    fun the_fit_is_a_sentence() {
        assertEquals("No track agrees with that candidate", MusicScoring.fitSentence(cand("a", 0)))
        val partial = cand("a", 2).copy(bestRelease = dev.jellystructure.model.MusicReleaseOption("r", trackCount = 18))
        assertEquals("2 of 2 tracks agree on position and length (partial album)", MusicScoring.fitSentence(partial))
    }

    @Test
    fun genres_life_span_urls_and_credits() {
        val votes = listOf(MusicGenreVote("grunge", 67), MusicGenreVote("rock", 26), MusicGenreVote("noise", 5), MusicGenreVote("fluke", 2))
        assertEquals(listOf("grunge", "rock"), MusicGenrePick.pick(votes))   // 5 < 67/10; 2 < 3 votes
        assertEquals("1987–1994", MusicScoring.lifeSpan(MbLifeSpan("1987", "1994-04-05", true)))
        assertEquals("1999–", MusicScoring.lifeSpan(MbLifeSpan("1999-02")))
        assertNull(MusicScoring.lifeSpan(null))
        val mb = listOf(dev.jellystructure.model.MusicMbCredit("m1", "Harbour Lights"))
        assertEquals("m1", MusicScoring.pairCredit("harbour lights", mb)?.mbid)
        assertEquals("m1", MusicScoring.pairCredit("Harbour Lights feat. Someone", mb)?.mbid)
        assertNull(MusicScoring.pairCredit("Somebody Else", mb))
    }

    @Test
    fun a_pasted_url_names_its_entity() {
        assertEquals("release-group" to "f1afec0b-26dd-3db5-9aa1-c91229a74a24",
            MusicBrainzClient.parseUrl("https://musicbrainz.org/release-group/f1afec0b-26dd-3db5-9aa1-c91229a74a24"))
        assertEquals("release", MusicBrainzClient.parseUrl("https://musicbrainz.org/release/adab3feb-1822-4d27-a997-db7d6c9688c0/cover-art")?.first)
        assertNull(MusicBrainzClient.parseUrl("not a url"))
        assertEquals("AC\\/DC", MusicBrainzClient.lucene("AC/DC"))
    }

    @Test
    fun the_match_step_joins_before_the_trailing_notify() {
        val p = listOf(PipelineStep("scan_files"), PipelineStep("pull_tmdb"), PipelineStep("notify"))
        assertEquals(listOf("scan_files", "scan_music", "scan_audiobooks", "pull_tmdb", "match_musicbrainz", "compare_songs", "fetch_music_artwork", "fetch_lyrics", "write_music_nfo", "write_tags", "notify"),
            MusicSteps.seed(p, emptyList()).map { it.step })
        // 275 already seeded scan_music (and the operator later removed it): only the new steps join, in order —
        // 280's scan_audiobooks after scan_files, since there is no music scan left to follow.
        assertEquals(listOf("scan_files", "scan_audiobooks", "pull_tmdb", "match_musicbrainz", "compare_songs", "fetch_music_artwork", "fetch_lyrics", "write_music_nfo", "write_tags", "notify"),
            MusicSteps.seed(p, listOf(MusicSteps.SCAN)).map { it.step })
        // Phase 305 (dev review 7) — a pipeline that already has every earlier music step gains only compare_songs,
        // straight after the match.
        val seeded = listOf("scan_files", "scan_music", "scan_audiobooks", "pull_tmdb", "match_musicbrainz", "fetch_music_artwork", "fetch_lyrics", "write_music_nfo", "write_tags", "notify").map { PipelineStep(it) }
        val before = MusicSteps.ALL - MusicSteps.COMPARE
        assertEquals(listOf("scan_files", "scan_music", "scan_audiobooks", "pull_tmdb", "match_musicbrainz", "compare_songs", "fetch_music_artwork", "fetch_lyrics", "write_music_nfo", "write_tags", "notify"),
            MusicSteps.seed(seeded, before).map { it.step })
        assertTrue(MusicSteps.isMusic("compare_songs"))
    }

    // ── the ladder against a fake MusicBrainz ──

    private val base = "/tmp/jellystructure-test-musicmatch-${getpid()}"
    private lateinit var store: MusicStore
    private lateinit var config: ConfigStore

    @BeforeTest fun setUp() {
        runBlocking {
            runCatching { platform.posix.remove("$base.db") }
            store = MusicStore(createDatabase("$base.db"))
            config = ConfigStore("$base.toml").also { it.load() }
            val artist = MusicArtist(id = "ja1", libraryId = "lib", name = "Harbour Lights", path = "/music/HL")
            val album = MusicAlbum(id = "alb", libraryId = "lib", title = "Salt", albumArtists = listOf(MusicCredit("ja1", "Harbour Lights")), trackCount = 2)
            store.replaceLibrary(MusicLibraryRows("lib", listOf(artist), listOf(album), listOf(track("t1", 1, 200), track("t2", 2, 210))))
        }
    }

    @AfterTest fun tearDown() {
        runCatching { platform.posix.remove("$base.db") }; runCatching { platform.posix.remove("$base.toml") }
    }

    /** Answers from fixed data; `down = true` answers nothing, like an unreachable MusicBrainz. */
    private inner class FakeMb(val groups: List<MbReleaseGroup>, val releases: Map<String, List<MbRelease>>, var down: Boolean = false) : MusicBrainzClient(config) {
        override suspend fun searchReleaseGroups(artist: String, album: String, limit: Int) = if (down) null else groups
        override suspend fun releaseGroup(mbid: String) = if (down) null else groups.firstOrNull { it.id == mbid }?.copy(genres = listOf(MbGenre("folk", 12)))
        override suspend fun releasesOf(releaseGroupMbid: String) = if (down) null else releases[releaseGroupMbid].orEmpty()
        override suspend fun release(mbid: String) = if (down) null else releases.values.flatten().firstOrNull { it.id == mbid }
        // Phase 292 — never MusicBrainz itself in a test.
        override suspend fun releaseWithRels(mbid: String) = release(mbid)
        override suspend fun recordingRels(mbid: String): MbRecording? = null
        override suspend fun recordingCredit(mbid: String): MbRecording? = null
        // Phase 305 — an extra's first pressing, never asked of MusicBrainz in a test.
        override suspend fun recording(mbid: String): MbRecording? = null
        override suspend fun artist(mbid: String) = if (down) null else MbArtist(id = mbid, name = "Harbour Lights", type = "Group", country = "NO", lifeSpan = MbLifeSpan("1999"))
    }

    private fun service(mb: MusicBrainzClient) = MusicMatchService(store, mb, AcoustIdClient({ "" }), config, fingerprint = { null })

    @Test
    fun a_clear_winner_is_matched_down_to_the_recordings_and_the_artist() = runBlocking {
        val mb = FakeMb(listOf(MbReleaseGroup("rg1", "Salt", 100, "Album", artistCredit = release("x").artistCredit), MbReleaseGroup("rg2", "Salt (Live)", 90, "Album")),
            mapOf("rg1" to listOf(release("r1", 200, 211)), "rg2" to listOf(release("r2", 300, 300))))
        val s = service(mb).matchAlbums(null, scopeAll = false)
        assertEquals(1, s.matched)
        val album = store.album("alb")!!
        assertEquals(MusicMatch.MATCHED, album.matchState); assertEquals("rg1", album.releaseGroupMbid); assertEquals("r1", album.releaseMbid)
        assertEquals(listOf("folk"), album.mbGenres.map { it.name }); assertEquals("Fjord Records", album.release?.label)
        assertEquals(MusicRecording.AGREES, store.track("t1")!!.recordingState); assertEquals("r1-rec2", store.track("t2")!!.recordingMbid)
        val artist = store.artist("ja1")!!
        assertEquals("mb-a1", artist.mbid); assertEquals("1999–", artist.lifeSpan); assertEquals("Group", artist.type)
    }

    @Test
    fun a_tie_needs_you_and_keeps_its_candidates() = runBlocking {
        val mb = FakeMb(listOf(MbReleaseGroup("rg1", "Salt", 100), MbReleaseGroup("rg2", "Salt", 100)),
            mapOf("rg1" to listOf(release("r1", 200, 210)), "rg2" to listOf(release("r2", 200, 210))))
        service(mb).matchAlbums(null, scopeAll = false)
        val album = store.album("alb")!!
        assertEquals(MusicMatch.NEEDS_YOU, album.matchState); assertEquals(2, album.candidates.size)
        assertTrue(album.matchNote!!.contains("agree equally"))
    }

    @Test
    fun no_answer_leaves_the_album_exactly_as_it_was() = runBlocking {
        val before = store.album("alb")!!
        val s = service(FakeMb(emptyList(), emptyMap(), down = true)).matchAlbums(null, scopeAll = false)
        assertEquals(1, s.failed)
        assertEquals(before, store.album("alb"))
    }

    @Test
    fun locked_albums_are_never_touched_and_clear_locks() = runBlocking {
        val mb = FakeMb(listOf(MbReleaseGroup("rg1", "Salt", 100)), mapOf("rg1" to listOf(release("r1", 200, 210))))
        val svc = service(mb)
        svc.setLocked("alb", true)
        assertEquals(0, svc.matchAlbums(null, scopeAll = true).tried)
        svc.setLocked("alb", false)
        svc.matchAlbums(null, scopeAll = false)
        val cleared = svc.clear("alb")!!
        assertEquals(MusicMatch.UNMATCHED, cleared.matchState); assertTrue(cleared.matchLocked); assertNull(cleared.releaseGroupMbid)
        assertNull(store.track("t1")!!.recordingMbid)
    }

    @Test
    fun a_second_folder_is_not_given_an_album_another_folder_has() = runBlocking {
        // Phase 283 (FR-283-2) — two folders whose tags name the same album: the first takes it, the second waits.
        store.putAlbum(store.album("alb")!!.copy(path = "/music/HL/Salt"))
        store.putAlbum(MusicAlbum(id = "alb2", libraryId = "lib", title = "Salt", path = "/music/HL/Salt (Single)", albumArtists = listOf(MusicCredit("ja1", "Harbour Lights")), trackCount = 2))
        store.putTracks(listOf(track("t3", 1, 200).copy(albumId = "alb2"), track("t4", 2, 210).copy(albumId = "alb2")))
        val mb = FakeMb(listOf(MbReleaseGroup("rg1", "Salt", 100)), mapOf("rg1" to listOf(release("r1", 200, 210))))
        val s = service(mb).matchAlbums(null, scopeAll = false)
        assertEquals(1, s.matched); assertEquals(1, s.needsYou)
        val states = listOf(store.album("alb")!!, store.album("alb2")!!)
        val waiting = states.single { it.matchState == MusicMatch.NEEDS_YOU }
        assertEquals(MusicMatch.MATCHED, states.single { it.id != waiting.id }.matchState)
        assertTrue(waiting.matchNote!!.startsWith("Another folder is already matched to this album: Salt"), waiting.matchNote)
        assertEquals(listOf("rg1"), waiting.candidates.map { it.releaseGroupMbid })
    }

    @Test
    fun an_unmatched_album_is_retried_once_a_day() = runBlocking {
        store.putAlbum(store.album("alb")!!.copy(matchAttemptedAt = nowEpochSec() - 60))
        val mb = FakeMb(listOf(MbReleaseGroup("rg1", "Salt", 100)), mapOf("rg1" to listOf(release("r1", 200, 210))))
        assertEquals(0, service(mb).matchAlbums(null, scopeAll = false).tried)
        assertEquals(1, service(mb).matchAlbums(listOf("alb"), scopeAll = false).tried)   // Match now ignores the day
    }
}
