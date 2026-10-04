package dev.jellystructure.music

import dev.jellystructure.model.MusicRecording
import dev.jellystructure.model.MusicRecordingFacts
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.model.MusicVersionChoice
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicCopyRankTest {
    private fun copy(id: String, bitrate: Int?, kind: Int = MusicCopyRank.OFFICIAL, depth: Int? = null, codec: String = "mp3", added: Long = 1) =
        MusicCopyRank.Copy(MusicTrack(id = id, title = id, bitrate = bitrate, bitDepth = depth, codec = codec, addedAt = added), kind)
    private fun best(vararg c: MusicCopyRank.Copy) = c.sortedWith(MusicCopyRank.comparator).first().track.id

    @Test fun bitrate_decides_first() = assertEquals("mp3", best(copy("aac", 256_000, codec = "aac"), copy("mp3", 320_000)))

    /** Owner decision 2 — the declined lean Q-B: a copy a phone re-encodes is not ranked lower. */
    @Test fun a_copy_a_phone_re_encodes_is_not_ranked_lower() = assertEquals("wma", best(copy("mp3", 160_000), copy("wma", 192_000, codec = "wmav2")))

    @Test fun equal_bitrate_goes_to_the_higher_bit_depth() = assertEquals("24", best(copy("16", 900_000, depth = 16, codec = "flac"), copy("24", 900_000, depth = 24, codec = "flac")))

    @Test
    fun a_full_tie_goes_official_extra_single_compilation_live_then_earliest_added() {
        val all = listOf(
            copy("live", 320_000, MusicCopyRank.LIVE), copy("comp", 320_000, MusicCopyRank.COMPILATION), copy("single", 320_000, MusicCopyRank.SINGLE),
            copy("extra", 320_000, MusicCopyRank.EXTRA), copy("late", 320_000, added = 9), copy("early", 320_000, added = 2),
        )
        assertEquals(listOf("early", "late", "extra", "single", "comp", "live"), all.sortedWith(MusicCopyRank.comparator).map { it.track.id })
    }

    @Test fun an_unknown_bitrate_sorts_below_every_known_one() = assertEquals("known", best(copy("unknown", null), copy("known", 64_000, MusicCopyRank.LIVE)))

    @Test
    fun lossless_codecs_are_the_listed_six() {
        for (c in listOf("flac", "alac", "wav", "pcm_s16le", "ape", "wavpack", "tta")) assertTrue(MusicCopyRank.lossless(c, null), c)
        for (c in listOf("mp3", "aac", "wmav2", "opus", "vorbis")) assertFalse(MusicCopyRank.lossless(c, null), c)
    }
}

class MusicSongIndexTest {
    private val lib = EditionsFixture.library()

    @Test
    fun rule_one_is_keyof() {
        val snap = lib.snapshot()
        val kw2 = snap.tracks["kw-2"]!!
        assertEquals(setOf("kw-2", "s-nl-1", "tt-1"), snap.songs.copies(kw2).map { it.id }.toSet())
        val dis = lib.copy(tracks = lib.tracks.map { if (it.id == "tt-1") it.copy(recordingState = MusicRecording.DISAGREES) else it }).snapshot()
        assertEquals(1, dis.songs.copies(dis.tracks["tt-1"]!!).size, "a disagreeing copy stays trk:")
    }

    @Test
    fun a_sound_pair_joins_only_when_one_side_is_untrusted() {
        val joined = lib.snapshot(sound = listOf(MusicSoundPair("kw-2", "nn-1", 0.05, 0.99, 0)))
        assertEquals(4, joined.songs.copies(joined.tracks["nn-1"]!!).size)
        assertEquals("sound", joined.songs.reason(joined.tracks["nn-1"]!!))
        val weak = lib.snapshot(sound = listOf(MusicSoundPair("kw-2", "nn-1", 0.3, 0.99, 0)))
        assertEquals(1, weak.songs.copies(weak.tracks["nn-1"]!!).size)
    }

    @Test
    fun two_trusted_different_recordings_that_sound_alike_are_a_suggestion_not_a_join() {
        val s = lib.snapshot(sound = listOf(MusicSoundPair("kw-5", "s-lt-1", 0.04, 0.97, 1200)))
        assertNotEquals(s.songs.songOf(s.tracks["kw-5"]!!), s.songs.songOf(s.tracks["s-lt-1"]!!))
        assertEquals(listOf("kw-5" to "s-lt-1"), s.songs.suggestions.map { it.a to it.b })
    }

    @Test
    fun the_owners_same_joins_anything() {
        val s = lib.snapshot(sameSong = mapOf(("kw-5" to "s-lt-1") to MusicSameSong.SAME))
        assertEquals(s.songs.songOf(s.tracks["kw-5"]!!), s.songs.songOf(s.tracks["s-lt-1"]!!))
        assertEquals("you", s.songs.reason(s.tracks["s-lt-1"]!!))
    }

    @Test
    fun not_the_same_song_splits_the_compilations_copy_off() {
        val sound = listOf(MusicSoundPair("kw-2", "nn-1", 0.05, 0.99, 0))
        val before = lib.snapshot(sound = sound)
        val nl = before.tracks["kw-2"]!!
        assertEquals(listOf("kw-2", "s-nl-1", "tt-1", "nn-1"), before.songs.copies(nl).map { it.id })
        val after = lib.snapshot(sound = sound, sameSong = mapOf(("kw-2" to "tt-1") to MusicSameSong.NOT_SAME))
        assertEquals(listOf("kw-2", "s-nl-1", "nn-1"), after.songs.copies(nl).map { it.id })
        assertEquals(listOf("tt-1"), after.songs.copies(after.tracks["tt-1"]!!).map { it.id })
        val back = lib.snapshot(sound = sound, sameSong = mapOf(("kw-2" to "tt-1") to MusicSameSong.NOT_SAME, ("s-nl-1" to "tt-1") to MusicSameSong.SAME))
        assertEquals(4, back.songs.copies(nl).size, "only an explicit same brings it back")
    }

    @Test
    fun title_and_length_never_decide() {
        val twins = lib.copy(tracks = lib.tracks + EditionsFixture.track("x1", "nn", 2, "Grey Gulls", null) + EditionsFixture.track("x2", "tt", 2, "Grey Gulls", null)).snapshot()
        assertNotEquals(twins.songs.songOf(twins.tracks["x1"]!!), twins.songs.songOf(twins.tracks["x2"]!!))
    }

    @Test
    fun versions_and_lyrics_still_follow_the_recording() {
        val withChoice = lib.copy().snapshot(sound = listOf(MusicSoundPair("kw-2", "nn-1", 0.05, 0.99, 0))).copy(
            choices = mapOf("rec:kw-r2" to mapOf("live" to MusicVersionChoice("live", true, 1))),
        )
        assertEquals(listOf("live"), withChoice.versions.of(withChoice.tracks["kw-2"]!!).shown)
        assertEquals(emptyList(), withChoice.versions.of(withChoice.tracks["nn-1"]!!).shown, "a copy joined by sound keeps its own answer")
    }

    @Test
    fun groups_are_recomputed_after_any_store_write() {
        val s1 = lib.snapshot()
        val s2 = s1.copy(sameSong = mapOf(("kw-1" to "kw-3") to MusicSameSong.SAME))
        assertNotEquals(s1.songs.songOf(s1.tracks["kw-1"]!!), s1.songs.songOf(s1.tracks["kw-3"]!!))
        assertEquals(s2.songs.songOf(s2.tracks["kw-1"]!!), s2.songs.songOf(s2.tracks["kw-3"]!!))
    }

    @Test
    fun bonus_on_a_folded_row_only_when_the_song_is_official_nowhere() {
        val s = lib.snapshot()
        assertTrue(s.songs.bonus(s.tracks["kw-12"]!!), "Lantern Swing: an extra, official nowhere")
        // A live cut: an extra on the anniversary edition, official on the live album.
        val live = EditionsFixture.album("live", "Live at the Harbour", 2011, secondary = listOf("Live"), official = dev.jellystructure.model.MusicOfficialList(
            listOf(dev.jellystructure.model.MusicOfficialSong("sf-r20", "Signal 20", 1, 1, 1)), "lr", "Live at the Harbour", 1, 1, "rg-live", "Live at the Harbour"))
        val lv = lib.copy(albums = lib.albums + live, tracks = lib.tracks + EditionsFixture.track("live-1", "live", 1, "Signal 20", "sf-r20", bitrate = 128_000)).snapshot()
        assertFalse(lv.songs.bonus(lv.tracks["sf-20"]!!), "official on the live album ⇒ not bonus, even when the extra copy is shown")
        assertTrue(lv.songs.isShown(lv.tracks["sf-20"]!!))
        assertTrue(lv.songs.bonus(lv.tracks["sf-21"]!!), "an extra on every copy is bonus")
    }
}

class MusicSingleHomeTest {
    private val lib = EditionsFixture.library()
    private fun homes(l: EditionsFixture.Lib = lib, overrides: Map<String, MusicSingleHome.Override> = emptyMap()) = l.snapshot(homes = overrides).editions.homes

    @Test fun musicbrainz_single_from_wins_first() {
        assertEquals(MusicSingleHome.Home("kw", MusicSingleHome.MB), homes()["s-nl"])
        // Even when rule 3 would pick another album: the title also matches Signal Found's, MusicBrainz still wins.
        val l = lib.copy(albums = lib.albums.map { if (it.id == "s-nl") it.copy(singleFrom = "rg-sf") else it })
        assertEquals("sf", homes(l)["s-nl"]?.albumId)
    }

    @Test fun a_remix_of_an_album_recording_is_via_remix() = assertEquals(MusicSingleHome.Home("kw", MusicSingleHome.REMIX), homes()["s-tw"])

    @Test
    fun a_remix_of_the_singles_own_recording_follows_the_hop() {
        // A remix single whose remix points at Northern Line's single, not at the album.
        val l = lib.copy(
            albums = lib.albums + EditionsFixture.album("s-rx", "Northern Line Remixes", 2008, primary = "Single"),
            tracks = lib.tracks.map { if (it.id == "s-nl-1") it.copy(recordingMbid = "nl-single-rec") else it } +
                EditionsFixture.track("s-rx-1", "s-rx", 1, "Northern Line (Dub)", "nl-dub"),
            facts = lib.facts + MusicRecordingFacts("nl-dub", remix = true, remixOf = dev.jellystructure.model.MusicVersionTarget("nl-single-rec")),
        )
        assertEquals(MusicSingleHome.Home("kw", MusicSingleHome.REMIX), homes(l)["s-rx"])
    }

    @Test
    fun same_base_title_same_artist_within_two_years_is_by_title() {
        assertEquals(MusicSingleHome.Home("kw", MusicSingleHome.TITLE), homes()["s-lt"])
        val late = lib.copy(albums = lib.albums.map { if (it.id == "s-lt") it.copy(year = 2009, firstReleaseDate = "2009") else it })
        assertNull(homes(late)["s-lt"], "three years on is not homed")
    }

    @Test fun an_ep_is_never_homed_by_title() = assertNull(homes()["ep-0"], "Shoreline EP opens with Low Tide")

    @Test fun another_artists_single_is_never_homed() = assertNull(homes()["s-st"])

    @Test
    fun the_owners_move_and_no_album_win_and_back_to_automatic_clears() {
        assertEquals(MusicSingleHome.Home("sf", MusicSingleHome.USER), homes(overrides = mapOf("s-nl" to MusicSingleHome.Override("sf")))["s-nl"])
        assertEquals(MusicSingleHome.Home(null, MusicSingleHome.USER), homes(overrides = mapOf("s-nl" to MusicSingleHome.Override(null)))["s-nl"])
        assertEquals("kw", homes()["s-nl"]?.albumId)
    }

    @Test
    fun b_sides_are_the_singles_songs_that_are_not_already_the_albums() {
        val b = lib.snapshot().editions.bsides("kw")
        assertEquals(13, b.size)
        assertEquals(listOf("s-nl", "s-fb", "s-salt", "s-lt", "s-tw"), b.map { it.first.id }.distinct(), "single (year) order")
        assertTrue(b.none { it.second.id.endsWith("-1") }, "the A-sides are never repeated")
        assertTrue(b.none { it.second.id == "s-fb-5" }, "nor a B-side that folds into an extra")
    }
}

class MusicSoundMatchTest {
    private fun fp(n: Int, seed: Int = 7) = Random(seed).let { r -> List(n) { r.nextInt() } }
    private fun flip(f: List<Int>, share: Double, seed: Int = 3): List<Int> {
        val r = Random(seed)
        return f.map { v -> var x = v; for (bit in 0 until 32) if (r.nextDouble() < share) x = x xor (1 shl bit); x }
    }

    @Test fun identical_is_bit_error_0_coverage_1_offset_0() {
        val a = fp(1500)
        assertEquals(MusicSoundMatch.Score(0.0, 1.0, 0), MusicSoundMatch.score(a, a))
    }

    @Test fun a_3_second_lead_in_is_found_as_the_offset_in_ms() {
        val a = fp(1500)
        val lead = (3.0 / dev.jellystructure.media.SegmentDetection.FRAME_SEC).toInt()
        val b = fp(lead, seed = 99) + a
        val s = MusicSoundMatch.score(a, b)!!
        assertEquals((lead * dev.jellystructure.media.SegmentDetection.FRAME_SEC * 1000).toLong(), s.offsetMs)
        assertEquals(0.0, s.ber)
    }

    @Test fun ten_percent_bit_flips_join_twenty_do_not() {
        val a = fp(1500)
        assertTrue(MusicSoundMatch.joins(MusicSoundMatch.score(a, flip(a, 0.10))))
        assertFalse(MusicSoundMatch.joins(MusicSoundMatch.score(a, flip(a, 0.20))))
    }

    @Test fun a_remix_that_keeps_only_the_first_40_percent_does_not_join() {
        val a = fp(1500)
        val remix = a.take(600) + fp(900, seed = 42)
        assertFalse(MusicSoundMatch.joins(MusicSoundMatch.score(a, remix)))
    }

    @Test fun an_offset_beyond_10_seconds_is_not_searched() {
        val a = fp(1500)
        val lead = (12.0 / dev.jellystructure.media.SegmentDetection.FRAME_SEC).toInt()
        val b = fp(lead, seed = 5) + a
        assertFalse(MusicSoundMatch.joins(MusicSoundMatch.score(a, b)))
    }
}

class MusicPairCandidatesTest {
    private val lib = EditionsFixture.library()

    @Test fun same_artist_same_base_title_not_already_one_song() {
        val c = MusicCompareSongs.candidates(lib.snapshot()).map { it.a.id to it.b.id }
        assertTrue(("kw-5" to "s-lt-1") in c, "Low Tide (radio edit) pairs with Low Tide")
        assertTrue(("ep-0-1" to "kw-5") in c)
        assertFalse(("kw-2" to "s-nl-1") in c, "already one song by recording")
        assertTrue(("kw-2" to "nn-1") in c, "the compilation's copy has no recording: the artist is the track's")
        val other = lib.copy(tracks = lib.tracks + EditionsFixture.track("lk-1", "lk-st", 2, "Northern Line", "lk-nl", credit = EditionsFixture.LK)).snapshot()
        assertTrue(MusicCompareSongs.candidates(other).none { "lk-1" in listOf(it.a.id, it.b.id) }, "another artist's song of the same name")
    }

    @Test fun untrusted_side_is_rule_2_both_trusted_and_different_is_a_suggestion() {
        val l = lib.copy(tracks = lib.tracks + EditionsFixture.track("x-nl", "nn", 3, "Northern Line (live)", null))
        val c = MusicCompareSongs.candidates(l.snapshot())
        assertFalse(c.first { it.a.id == "kw-2" && it.b.id == "x-nl" }.suggestion)
        assertTrue(c.first { it.a.id == "kw-5" && it.b.id == "s-lt-1" }.suggestion)
    }

    @Test fun a_decided_pair_is_never_offered_again() {
        val s = lib.snapshot(sameSong = mapOf(("kw-5" to "s-lt-1") to MusicSameSong.NOT_SAME))
        assertTrue(MusicCompareSongs.candidates(s).none { it.a.id == "kw-5" && it.b.id == "s-lt-1" })
    }

    @Test fun pairs_are_ordered_a_less_than_b() = assertTrue(MusicCompareSongs.candidates(lib.snapshot()).all { it.a.id < it.b.id })

    @Test fun title_only_picks_never_joins() {
        val s = lib.snapshot(sound = listOf(MusicSoundPair("ep-0-1", "kw-5", 0.4, 0.5, 0)))
        assertNotEquals(s.songs.songOf(s.tracks["ep-0-1"]!!), s.songs.songOf(s.tracks["kw-5"]!!))
    }
}
