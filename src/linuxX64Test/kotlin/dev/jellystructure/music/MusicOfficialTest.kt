package dev.jellystructure.music

import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicExtraOrigin
import dev.jellystructure.model.MusicMatch
import dev.jellystructure.model.MusicOfficialList
import dev.jellystructure.model.MusicOfficialSong
import dev.jellystructure.model.MusicRecording
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
    private fun t(id: String, pos: Int, rec: String?, state: String? = MusicRecording.AGREES) =
        MusicTrack(id = id, albumId = "a", title = id, position = pos, recordingMbid = rec, recordingState = rec?.let { state })

    @Test
    fun a_held_recording_off_the_official_list_is_an_extra_and_moves_down() {
        val l = MusicOfficial.layout(album, listOf(t("one", 1, "r1"), t("two", 2, "x-bonus"), t("three", 3, "r2"), t("four", 4, "r3")))
        assertEquals(listOf("one", "three", "four"), l.rows.map { it.second.id })
        assertEquals(listOf("two"), l.extras.map { it.id })
    }

    @Test
    fun a_disagreeing_tracks_recording_still_decides_extra() {
        assertFalse(MusicOfficial.isExtra(official, t("x", 1, "r1", MusicRecording.DISAGREES)))
        assertTrue(MusicOfficial.isExtra(official, t("y", 2, "zz", MusicRecording.DISAGREES)))
        assertEquals("trk:x", dev.jellystructure.model.MusicVersions.keyOf(t("x", 1, "r1", MusicRecording.DISAGREES)), "rule 1 never trusts it")
    }

    @Test
    fun no_recording_id_on_a_matched_album_is_an_extra() {
        assertTrue(MusicOfficial.isExtra(official, t("x", 9, null)))
    }

    @Test
    fun an_official_song_the_files_lack_is_a_gap_row_above_the_divider() {
        val l = MusicOfficial.layout(album, listOf(t("one", 1, "r1"), t("three", 3, "r3")))
        assertEquals(listOf(1), l.gaps.map { it.first })
        assertEquals("Song 2", l.gaps.single().second.title)
    }

    @Test
    fun an_unmatched_album_has_no_official_list_no_extras_no_gaps() {
        val plain = album.copy(matchState = MusicMatch.UNMATCHED, releaseGroupMbid = null)
        val files = listOf(t("b", 2, null), t("a", 1, null))
        val l = MusicOfficial.layout(plain, files)
        assertNull(l.official); assertTrue(l.rows.isEmpty()); assertTrue(l.gaps.isEmpty())
        assertEquals(listOf("b", "a"), l.extras.map { it.id }, "the files' own order, exactly today")
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
