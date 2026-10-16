package dev.jellystructure.music

import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicExtraOrigin
import dev.jellystructure.model.MusicMatch
import dev.jellystructure.model.MusicOfficialList
import dev.jellystructure.model.MusicOfficialSong
import dev.jellystructure.model.MusicRecording
import dev.jellystructure.model.MusicRecordingFacts
import dev.jellystructure.model.MusicTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Builders shared by 305's pure tests: a pressing as MusicBrainz sends it, renamed to stand-ins. */
internal object Pressings {
    fun release(id: String, recs: List<String>, date: String? = "2006", status: String = "Official", country: String? = "GB",
                title: String = "Kite Weather", media: List<Pair<String?, List<String>>>? = null, videos: Set<String> = emptySet(),
                disambiguation: String? = null): MbRelease {
        val mediaList = (media ?: listOf("CD" to recs)).mapIndexed { mi, (format, rs) ->
            MbMedium(position = mi + 1, format = format, trackCount = rs.size, tracks = rs.mapIndexed { i, r ->
                MbTrack(id = "$id-t$mi-$i", position = i + 1, title = "Song $r", length = 200_000L,
                    recording = MbRecording(id = r, title = "Song $r", length = 200_000L, video = r in videos))
            })
        }
        return MbRelease(id = id, title = title, country = country, date = date, status = status, media = mediaList, disambiguation = disambiguation)
    }

    fun group(primary: String? = "Album", secondary: List<String> = emptyList(), title: String = "Kite Weather") =
        MbReleaseGroup(id = "rg-kw", title = title, primaryType = primary, secondaryTypes = secondary)

    val ELEVEN = (1..11).map { "kw-r$it" }
}

class MusicOfficialTest {
    private fun rel(id: String, recs: List<String>, date: String? = "2006", status: String = "Official") = Pressings.release(id, recs, date, status)

    @Test
    fun the_most_shared_recording_set_wins_and_says_k_of_m() {
        val pressings = (1..7).map { rel("gb-$it", Pressings.ELEVEN, "2006-0$it") } + rel("jp", Pressings.ELEVEN + "kw-r12", "2006-03-08") + rel("us", Pressings.ELEVEN.dropLast(1), "2007")
        val o = MusicOfficial.vote(Pressings.group(), pressings)!!
        assertEquals(11, o.songs.size)
        assertEquals(7, o.k); assertEquals(9, o.m)
        assertEquals("rg-kw", o.groupMbid)
    }

    @Test
    fun order_discs_and_titles_come_from_the_earliest_release_with_that_set() {
        val o = MusicOfficial.vote(Pressings.group(), listOf(rel("late", Pressings.ELEVEN.reversed(), "2010"), rel("early", Pressings.ELEVEN, "2006")))!!
        assertEquals("early", o.releaseMbid)
        assertEquals(Pressings.ELEVEN, o.songs.map { it.recordingMbid })
    }

    @Test
    fun a_tie_goes_to_the_smaller_set_then_the_earliest() {
        val big = (1..26).map { "sf-r$it" }; val small = (1..14).map { "sf-r$it" }
        val o = MusicOfficial.vote(Pressings.group(title = "Signal Found"), listOf(rel("anniv-1", big, "2003-01"), rel("anniv-2", big, "2004"), rel("a", small, "2005"), rel("b", small, "2006")))!!
        assertEquals(14, o.songs.size)
        assertEquals("a", o.releaseMbid)
        val other = (1..14).map { "x-r$it" }
        val o2 = MusicOfficial.vote(Pressings.group(), listOf(rel("later", other, "2009"), rel("earlier", small, "2001")))!!
        assertEquals("earlier", o2.releaseMbid)
    }

    @Test
    fun videos_and_dvd_media_are_dropped_before_the_vote() {
        val deluxe = { id: String -> Pressings.release(id, emptyList(), media = listOf("CD" to Pressings.ELEVEN + "kw-v1", "DVD-Video" to listOf("kw-dvd1", "kw-dvd2")), videos = setOf("kw-v1")) }
        val o = MusicOfficial.vote(Pressings.group(), listOf(deluxe("d1"), deluxe("d2"), rel("cd", Pressings.ELEVEN, "2007")))!!
        assertEquals(Pressings.ELEVEN, o.songs.map { it.recordingMbid })
        assertEquals(3, o.k)
        assertEquals(1, o.media)
        assertTrue(MusicOfficial.isVideoMedium("Blu-ray")); assertFalse(MusicOfficial.isVideoMedium("DVD-Audio"))
    }

    @Test
    fun only_official_releases_vote() {
        val base = listOf(rel("a", Pressings.ELEVEN), rel("b", Pressings.ELEVEN))
        val noise = (1..5).map { rel("boot-$it", Pressings.ELEVEN + "kw-live$it", status = "Bootleg") } + rel("promo", listOf("kw-r1"), status = "Promotion")
        assertEquals(MusicOfficial.vote(Pressings.group(), base), MusicOfficial.vote(Pressings.group(), base + noise))
    }

    @Test
    fun only_an_album_without_compilation_gets_one() {
        val r = listOf(rel("a", Pressings.ELEVEN))
        assertNotNull(MusicOfficial.vote(Pressings.group("Album"), r))
        assertNotNull(MusicOfficial.vote(Pressings.group("Album", listOf("Live")), r))
        assertNotNull(MusicOfficial.vote(Pressings.group("Album", listOf("Soundtrack")), r))
        assertNull(MusicOfficial.vote(Pressings.group("Single"), r))
        assertNull(MusicOfficial.vote(Pressings.group("EP"), r))
        assertNull(MusicOfficial.vote(Pressings.group("Album", listOf("Compilation")), r))
        assertNull(MusicOfficial.vote(Pressings.group("Other", listOf("Compilation")), r), "a box set")
        assertNull(MusicOfficial.vote(Pressings.group("Single", listOf("Live")), r), "a live single is still a single")
    }

    @Test
    fun one_medium_numbers_1_to_n_more_media_number_disc_and_track() {
        val one = MusicOfficial.vote(Pressings.group(), listOf(rel("a", Pressings.ELEVEN)))!!
        assertEquals("1", MusicOfficial.number(one, 0)); assertEquals("11", MusicOfficial.number(one, 10))
        val two = MusicOfficial.vote(Pressings.group(), listOf(Pressings.release("d", emptyList(), media = listOf("CD" to listOf("a1", "a2"), "CD" to listOf("b1", "b2", "b3")))))!!
        assertEquals(2, two.media)
        assertEquals("1·2", MusicOfficial.number(two, 1)); assertEquals("2·3", MusicOfficial.number(two, 4))
    }
}

class MusicExtrasTest {
    private val official = MusicOfficialList((1..3).map { MusicOfficialSong("r$it", "Song $it", 200_000, 1, it) }, "rel", "A", 3, 3, "rg", "A")
    private val album = MusicAlbum(id = "a", title = "A", matchState = MusicMatch.MATCHED, releaseGroupMbid = "rg", primaryType = "Album", official = official)
    private val fill = MusicOfficial.Fill()
    private fun t(id: String, pos: Int, rec: String?, state: String? = MusicRecording.AGREES, title: String = id, disc: Int? = null) =
        MusicTrack(id = id, albumId = "a", title = title, disc = disc, position = pos, recordingMbid = rec, recordingState = rec?.let { state })

    @Test
    fun a_held_recording_off_the_official_list_is_an_extra_and_moves_down() {
        val l = MusicOfficial.layout(album, listOf(t("one", 1, "r1"), t("two", 2, "x-bonus"), t("three", 3, "r2"), t("four", 4, "r3")), fill)
        assertEquals(listOf("one", "three", "four"), l.rows.map { it.second.id })
        assertEquals(listOf("two"), l.extras.map { it.id })
    }

    @Test
    fun a_disagreeing_tracks_recording_still_decides_extra() {
        assertTrue(MusicOfficial.isOnList(official, t("x", 1, "r1", MusicRecording.DISAGREES)))
        assertFalse(MusicOfficial.isOnList(official, t("y", 2, "zz", MusicRecording.DISAGREES)))
        assertEquals("trk:x", dev.jellystructure.model.MusicVersions.keyOf(t("x", 1, "r1", MusicRecording.DISAGREES)), "rule 1 never trusts it")
    }

    @Test
    fun no_recording_id_on_a_matched_album_is_an_extra() {
        assertFalse(MusicOfficial.isOnList(official, t("x", 9, null)))
    }

    @Test
    fun a_matched_album_whose_files_carry_no_recording_ids_is_todays_plain_list() {
        // Found on the dev stack 2026-10-05: 54 files, an official list, no recording ids ⇒ *0 songs + 54 extras*.
        val files = listOf(t("01-01 - One", 1, null), t("01-02 - Two", 2, null), t("02-01 - One", 1, null))
        val l = MusicOfficial.layout(album, files, fill)
        assertNull(l.official); assertTrue(l.rows.isEmpty()); assertTrue(l.gaps.isEmpty())
        assertEquals(files.map { it.id }, l.extras.map { it.id }, "the files' own order, as an unmatched album")
        assertNull(MusicOfficial.usable(album, files, fill), "nothing to compare, so no file is an extra or Bonus")
        assertEquals(official, MusicOfficial.usable(album, files + t("three", 3, "r3"), fill), "one id is enough to lay out")
    }

    @Test
    fun no_held_file_on_an_official_row_is_the_plain_list() {
        // FR-305-3b — files with ids that are all off the list (a bonus disc held alone) are no longer extras.
        val files = listOf(t("b1", 1, "x-1"), t("b2", 2, "x-2"))
        val l = MusicOfficial.layout(album, files, fill)
        assertNull(l.official); assertTrue(l.rows.isEmpty()); assertTrue(l.gaps.isEmpty())
        assertEquals(files.map { it.id }, l.extras.map { it.id })
        assertEquals(official, MusicOfficial.usable(album, files + t("one", 3, "r1"), fill), "one file on a row is enough")
    }

    @Test
    fun an_official_song_the_files_lack_is_a_gap_row_above_the_divider() {
        val l = MusicOfficial.layout(album, listOf(t("one", 1, "r1"), t("three", 3, "r3")), fill)
        assertEquals(listOf(1), l.gaps.map { it.first })
        assertEquals("Song 2", l.gaps.single().second.title)
    }

    @Test
    fun an_unmatched_album_has_no_official_list_no_extras_no_gaps() {
        val plain = album.copy(matchState = MusicMatch.UNMATCHED, releaseGroupMbid = null)
        val files = listOf(t("b", 2, null), t("a", 1, null))
        val l = MusicOfficial.layout(plain, files, fill)
        assertNull(l.official); assertTrue(l.rows.isEmpty()); assertTrue(l.gaps.isEmpty())
        assertEquals(listOf("b", "a"), l.extras.map { it.id }, "the files' own order, exactly today")
    }
}

/** DEB-1487 (FR-305-3a/3b) — a held version fills its official song's row. Stand-ins only. */
class MusicFillTest {
    private val titles = listOf("Low Tide", "Fog Bank", "Tidewater")
    private val official = MusicOfficialList(
        titles.mapIndexed { i, t -> MusicOfficialSong("r${i + 1}", t, 200_000, 1, i + 1, works = listOf("w${i + 1}")) }, "rel", "A", 3, 3, "rg", "A",
    )
    private val album = MusicAlbum(id = "a", title = "A", matchState = MusicMatch.MATCHED, releaseGroupMbid = "rg", primaryType = "Album", official = official)
    private fun t(id: String, pos: Int, rec: String?, title: String, disc: Int? = null) =
        MusicTrack(id = id, albumId = "a", title = title, disc = disc, position = pos, recordingMbid = rec, recordingState = rec?.let { MusicRecording.AGREES })
    private fun fill(types: Map<String, List<String>> = emptyMap(), works: Map<String, List<String>> = emptyMap()) =
        MusicOfficial.Fill({ types[it.id].orEmpty() }, { it.recordingMbid?.let { r -> works[r] } })

    @Test
    fun a_remastered_edition_fills_the_official_rows_by_work() {
        // Held as other recordings of the same three works, under titles that say nothing of the official ones.
        val files = listOf(t("h1", 1, "m1", "Opening"), t("h2", 2, "m2", "Middle"), t("h3", 3, "m3", "Closing"))
        val l = MusicOfficial.layout(album, files, fill(works = mapOf("m1" to listOf("w1"), "m2" to listOf("w2"), "m3" to listOf("w3"))))
        assertEquals(official, l.official)
        assertEquals(listOf(0 to "h1", 1 to "h2", 2 to "h3"), l.rows.map { it.first to it.second.id })
        assertTrue(l.gaps.isEmpty()); assertTrue(l.extras.isEmpty())
        assertEquals("1", MusicOfficial.number(official, l.rows[0].first), "numbered in the official order")
    }

    @Test
    fun a_version_with_no_work_fills_by_base_title() {
        val files = listOf(t("own", 1, "r1", "Low Tide"), t("v", 2, "m2", "Fog Bank (2026 mix)"), t("x", 3, "m9", "Unrelated"))
        val l = MusicOfficial.layout(album, files, fill())
        assertEquals(listOf(0 to "own", 1 to "v"), l.rows.map { it.first to it.second.id })
        assertEquals(listOf(2), l.gaps.map { it.first }, "Tidewater is still not held")
        assertEquals(listOf("x"), l.extras.map { it.id })
    }

    @Test
    fun a_live_demo_or_remix_never_fills_a_slot_an_edit_does() {
        val files = listOf(t("own", 1, "r1", "Low Tide"), t("live", 2, "m2", "Fog Bank (live)"), t("demo", 3, "m3", "Tidewater (demo)"))
        val types = mapOf("live" to listOf("live"), "demo" to listOf("demo"))
        val l = MusicOfficial.layout(album, files, fill(types, works = mapOf("m2" to listOf("w2"), "m3" to listOf("w3"))))
        assertEquals(listOf("own"), l.rows.map { it.second.id }); assertEquals(listOf("live", "demo"), l.extras.map { it.id })
        for (type in listOf("remix", "instrumental", "cover", "acoustic", "alternate", "session")) {
            val r = MusicOfficial.layout(album, listOf(t("own", 1, "r1", "Low Tide"), t("v", 2, "m2", "Fog Bank")), fill(mapOf("v" to listOf(type))))
            assertEquals(listOf("v"), r.extras.map { it.id }, type)
        }
        val edit = MusicOfficial.layout(album, listOf(t("own", 1, "r1", "Low Tide"), t("v", 2, "m2", "Fog Bank - Extended")), fill(mapOf("v" to listOf("edit"))))
        assertEquals(listOf("own", "v"), edit.rows.map { it.second.id }, "an Edit fills")
        assertTrue(edit.extras.isEmpty())
    }

    @Test
    fun a_slot_held_by_its_own_recording_is_not_refilled() {
        val files = listOf(t("own", 1, "r1", "Low Tide"), t("v", 2, "m1", "Low Tide (2026 mix)"))
        val l = MusicOfficial.layout(album, files, fill(works = mapOf("m1" to listOf("w1"))))
        assertEquals(listOf("own"), l.rows.map { it.second.id })
        assertEquals(listOf("v"), l.extras.map { it.id })
    }

    @Test
    fun a_title_matching_two_official_songs_fills_neither() {
        val twice = official.copy(songs = official.songs + MusicOfficialSong("r4", "Low Tide (reprise)", 100_000, 1, 4))
        val a = album.copy(official = twice)
        val l = MusicOfficial.layout(a, listOf(t("own", 1, "r2", "Fog Bank"), t("v", 2, "m1", "Low Tide (2026 mix)")), fill())
        assertEquals(listOf("own"), l.rows.map { it.second.id })
        assertEquals(listOf("v"), l.extras.map { it.id })
        assertEquals(listOf(0, 2, 3), l.gaps.map { it.first })
    }

    @Test
    fun the_earliest_held_candidate_fills_the_slot_the_rest_stay_extras() {
        val files = listOf(t("own", 1, "r2", "Fog Bank"), t("late", 5, "m1b", "Low Tide (2026 mix)"), t("early", 2, "m1a", "Low Tide (2011 mix)"), t("disc2", 1, "m1c", "Low Tide (live)", disc = 2))
        val l = MusicOfficial.layout(album, files, fill())
        assertEquals(mapOf(0 to "early", 1 to "own"), l.rows.associate { it.first to it.second.id })
        assertEquals(listOf("late", "disc2"), l.extras.map { it.id }, "the files' own order")
    }

    @Test
    fun a_track_fills_at_most_one_slot() {
        // One recording performing two works (a medley without the attribute) must not fill both rows.
        val l = MusicOfficial.layout(album, listOf(t("own", 1, "r3", "Tidewater"), t("v", 2, "m", "Opening")), fill(works = mapOf("m" to listOf("w1", "w2"))))
        assertEquals(listOf(0 to "v", 2 to "own"), l.rows.map { it.first to it.second.id })
        assertEquals(listOf(1), l.gaps.map { it.first })
    }

    @Test
    fun a_filled_row_is_official_and_the_header_counts_it() {
        val files = listOf(t("own", 1, "r1", "Low Tide"), t("v", 2, "m2", "Fog Bank (2026 mix)"))
        val l = MusicOfficial.layout(album, files, fill())
        assertEquals(official, l.official)
        assertEquals(2, l.rows.size); assertTrue(l.extras.isEmpty())
    }

    @Test
    fun no_held_file_on_an_official_row_is_the_plain_list() {
        val files = listOf(t("b", 1, "m1", "Bonus Song"), t("v", 2, "m2", "Fog Bank (live)"))
        val l = MusicOfficial.layout(album, files, fill(mapOf("v" to listOf("live"))))
        assertNull(l.official); assertTrue(l.rows.isEmpty()); assertTrue(l.gaps.isEmpty())
        assertEquals(files.map { it.id }, l.extras.map { it.id })
        assertNull(MusicOfficial.usable(album, files, fill(mapOf("v" to listOf("live")))))
    }

    @Test
    fun the_work_rule_needs_a_read_only_for_a_gap_and_an_extra() {
        val facts = { rec: String -> MusicRecordingFacts(rec) }
        val gapAndExtra = listOf(t("own", 1, "r1", "Low Tide"), t("v", 2, "m2", "Fog Bank (2026 mix)"))
        val need = MusicOfficial.workNeed(album, gapAndExtra, facts)
        assertEquals(setOf("m2"), need?.heldRecordings)
        assertEquals(false, need?.officialList)
        val noList = album.copy(official = official.copy(songs = official.songs.map { it.copy(works = null) }))
        assertEquals(true, MusicOfficial.workNeed(noList, gapAndExtra, facts)?.officialList)
        assertNull(MusicOfficial.workNeed(noList, listOf(t("a", 1, "r1", "Low Tide"), t("b", 2, "r2", "Fog Bank"), t("c", 3, "r3", "Tidewater")), facts), "no gap, no extra")
        assertNull(MusicOfficial.workNeed(noList, listOf(t("a", 1, "r1", "Low Tide"), t("c", 3, "r3", "Tidewater")), facts), "a gap but no extra")
        assertNull(MusicOfficial.workNeed(noList, listOf(t("a", 1, "r1", "Low Tide"), t("b", 2, "r2", "Fog Bank"), t("c", 3, "r3", "Tidewater"), t("x", 4, "m", "Bonus")), facts), "an extra but no gap")
        assertNull(MusicOfficial.workNeed(album, gapAndExtra) { rec -> MusicRecordingFacts(rec, works = emptyList()) }, "already read")
    }
}

class MusicEditionNameTest {
    @Test
    fun the_releases_own_title_when_it_differs_from_the_groups() {
        assertEquals("Signal Found 20th Anniversary", MusicOfficial.edition("Signal Found", MusicExtraOrigin(title = "Signal Found 20th Anniversary", country = "GB")).title)
    }

    @Test
    fun else_its_disambiguation_as_it_is() {
        assertEquals("super deluxe", MusicOfficial.edition("Kite Weather", MusicExtraOrigin(title = "Kite Weather", disambiguation = "super deluxe", country = "GB")).title)
    }

    @Test
    fun else_the_country_code() {
        val e = MusicOfficial.edition("Kite Weather", MusicExtraOrigin(title = "Kite Weather", country = "JP"))
        assertNull(e.title); assertEquals("JP", e.country)
        assertEquals("Japan", MusicCountries.name("JP"))
    }

    @Test
    fun else_nothing_and_the_divider_reads_extras() {
        assertEquals(MusicOfficial.Edition(), MusicOfficial.edition("Kite Weather", MusicExtraOrigin(title = "Kite Weather")))
        assertEquals(MusicOfficial.Edition(), MusicOfficial.edition("Kite Weather", null))
    }

    @Test
    fun the_first_release_is_the_earliest_dated_pressing_that_carries_the_recording() {
        val rs = listOf(
            Pressings.release("jp-reissue", Pressings.ELEVEN + "kw-r12", "2012", country = "JP"),
            Pressings.release("jp", Pressings.ELEVEN + "kw-r12", "2006-03-08", country = "JP"),
            Pressings.release("undated", Pressings.ELEVEN + "kw-r12", null),
        )
        val o = MusicOfficial.firstReleases(rs, setOf("kw-r12"))["kw-r12"]!!
        assertEquals("jp", o.releaseMbid)
        assertEquals("the Japanese CD, 2006", MusicOfficial.firstOnText(o))
    }
}
