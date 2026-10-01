package dev.jellystructure.music

import dev.jellystructure.model.MusicRecording
import dev.jellystructure.model.MusicRecordingFacts
import dev.jellystructure.model.MusicTitleVersions
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.model.MusicVersionChoice
import dev.jellystructure.model.MusicVersions
import dev.jellystructure.model.MusicVersions.ACOUSTIC
import dev.jellystructure.model.MusicVersions.ALTERNATE
import dev.jellystructure.model.MusicVersions.COVER
import dev.jellystructure.model.MusicVersions.DEMO
import dev.jellystructure.model.MusicVersions.EDIT
import dev.jellystructure.model.MusicVersions.INSTRUMENTAL
import dev.jellystructure.model.MusicVersions.LIVE
import dev.jellystructure.model.MusicVersions.REMIX
import dev.jellystructure.model.MusicVersions.SESSION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 292 — the one function every reader calls, and the title finder (dev review 3, 7, 15). */
class MusicVersionsTest {
    private fun track(id: String, title: String, mbid: String? = null, state: String? = MusicRecording.AGREES) =
        MusicTrack(id = id, title = title, recordingMbid = mbid, recordingState = if (mbid == null) null else state)

    private fun of(t: MusicTrack, facts: MusicRecordingFacts? = null, choices: Map<String, Boolean> = emptyMap(), copies: List<MusicTrack> = listOf(t)) =
        MusicVersions.of(t, copies, facts, choices.mapValues { (k, on) -> MusicVersionChoice(k, on, 1) })

    // ── the title finder ──

    @Test
    fun the_title_finder_reads_brackets_and_what_follows_a_dash() {
        assertEquals(setOf(LIVE), MusicTitleVersions.find("Salt on the Window (live at the harbour, 2011)"))
        assertEquals(setOf(REMIX, EDIT), MusicTitleVersions.find("Northern Line (Lighthouse Keepers remix) (extended)"))
        assertEquals(setOf(ACOUSTIC, ALTERNATE, SESSION), MusicTitleVersions.find("Northbound (acoustic, alternate take, radio session)"))
        assertEquals(setOf(EDIT), MusicTitleVersions.find("Kite Weather - Radio Edit"))
        assertEquals(setOf(INSTRUMENTAL), MusicTitleVersions.find("Fog Bank [Karaoke]"))
        assertEquals(setOf(DEMO), MusicTitleVersions.find("Signal (demo)"))
        assertEquals(setOf(ACOUSTIC), MusicTitleVersions.find("Kvøld (akustisk)"))
        assertEquals(setOf(ACOUSTIC), MusicTitleVersions.find("Tide (Unplugged)"))
        assertEquals(setOf(REMIX), MusicTitleVersions.find("Harbour (Club Mix)"))
        assertEquals(setOf(ALTERNATE), MusicTitleVersions.find("Harbour (Alternative Version)"))
    }

    @Test
    fun the_title_finder_leaves_alone_what_is_not_a_version() {
        assertEquals(emptySet(), MusicTitleVersions.find("Live and Let Go"), "outside brackets a word is the song's name")
        assertEquals(emptySet(), MusicTitleVersions.find("Harbour (Original Mix)"))
        assertEquals(emptySet(), MusicTitleVersions.find("Harbour (Album Version)"))
        assertEquals(emptySet(), MusicTitleVersions.find("Harbour (2011 Remaster)"))
        assertEquals(emptySet(), MusicTitleVersions.find("Harbour - Remastered"))
        assertEquals(emptySet(), MusicTitleVersions.find("Harbour (Deluxe Edition)"))
        assertEquals(emptySet(), MusicTitleVersions.find("Harbour (Stereo Mix)"))
        assertEquals(emptySet(), MusicTitleVersions.find("Salt on the Window"))
        assertEquals(emptySet(), MusicTitleVersions.find("Rock-a-bye"), "a dash with no spaces is part of the name")
        assertEquals(setOf(INSTRUMENTAL, DEMO), MusicTitleVersions.findInNote("instrumental demo"))
    }

    // ── MusicVersions.of ──

    @Test
    fun a_normal_song_has_no_version() {
        val a = of(track("t", "Salt on the Window", "r1"))
        assertEquals(emptyList(), a.shown)
        assertTrue(a.matches(MusicVersions.NONE))
        assertFalse(a.blocksLyrics)
    }

    @Test
    fun musicbrainz_and_the_title_are_a_union_with_both_sources() {
        val t = track("t", "Northern Line (Lighthouse Keepers remix) (extended)", "r2")
        val a = of(t, MusicRecordingFacts("r2", remix = true))
        assertEquals(listOf(REMIX, EDIT), a.shown)
        assertEquals(listOf(MusicVersions.SRC_MUSICBRAINZ, MusicVersions.SRC_TITLE), a.sources[REMIX])
        assertEquals(listOf(MusicVersions.SRC_TITLE), a.sources[EDIT])
    }

    @Test
    fun session_brings_an_automatic_live_with_session() {
        val a = of(track("t", "Northbound (acoustic, alternate take, radio session)", "r3"))
        assertEquals(listOf(LIVE, ACOUSTIC, ALTERNATE, SESSION), a.shown, "the table's order")
        assertEquals(listOf(MusicVersions.SRC_SESSION), a.sources[LIVE])
    }

    @Test
    fun the_owners_removed_live_wins_and_session_stays() {
        val a = of(track("t", "Northbound (radio session)", "r3"), choices = mapOf(LIVE to false))
        assertEquals(listOf(SESSION), a.shown, "Session without Live is shown as it is")
        assertTrue(a.matches(LIVE), "a filter treats Session as Live")
        assertFalse(a.matches(MusicVersions.NONE))
        assertEquals(setOf(LIVE), a.removed)
        assertTrue(a.hasChoices)
    }

    @Test
    fun the_owners_ticks_add_and_remove() {
        val a = of(track("t", "Fog Bank", "r4"), MusicRecordingFacts("r4", cover = true, demo = true, instrumental = true), mapOf(ACOUSTIC to true, DEMO to false))
        assertEquals(listOf(INSTRUMENTAL, COVER, ACOUSTIC), a.shown)
        assertEquals(listOf(MusicVersions.SRC_USER), a.sources[ACOUSTIC])
        assertTrue(a.blocksLyrics, "Instrumental from any source blocks lyrics")
        assertTrue(of(track("u", "Plain", "r9"), choices = mapOf(INSTRUMENTAL to true)).blocksLyrics, "the owner's tick too")
    }

    @Test
    fun a_piece_never_sung_is_no_version_and_gets_no_lyrics() {
        val a = of(track("t", "Prelude (instrumental)", "r5"), MusicRecordingFacts("r5", noWords = true))
        assertEquals(emptyList(), a.shown, "not Instrumental, whatever the title says")
        assertTrue(a.noWords); assertTrue(a.blocksLyrics); assertTrue(a.matches(MusicVersions.NONE))
    }

    @Test
    fun one_answer_per_recording_and_never_across_a_disagreeing_copy() {
        val studio = track("s", "Salt on the Window", "r6")
        val live = track("l", "Salt on the Window (live at the harbour, 2011)", "r7")
        val liveBox = track("lb", "Salt on the Window - Live", "r7")
        assertEquals(listOf(LIVE), of(live, copies = listOf(live, liveBox)).shown)
        assertEquals(emptyList(), of(studio).shown)
        // A track whose length disagrees keeps its own key, and the recording's facts are not used.
        val off = track("d", "Salt on the Window", "r6", state = MusicRecording.DISAGREES)
        assertEquals("trk:d", MusicVersions.keyOf(off))
        assertEquals(emptyList(), of(off, MusicRecordingFacts("r6", live = true)).shown)
        assertEquals("rec:r7", MusicVersions.keyOf(live))
        assertEquals("rec:r6", MusicVersions.keyOf(track("m", "x", "r6", state = MusicRecording.MANUAL)))
        assertEquals("trk:u", MusicVersions.keyOf(track("u", "x")))
    }

    @Test
    fun the_disambiguation_is_read_as_musicbrainz() {
        val a = of(track("t", "Harbour", "r8"), MusicRecordingFacts("r8", disambiguation = "instrumental demo"))
        assertEquals(listOf(DEMO, INSTRUMENTAL), a.shown)
        assertEquals(listOf(MusicVersions.SRC_MUSICBRAINZ), a.sources[INSTRUMENTAL])
    }

    @Test
    fun the_album_summary_needs_half() {
        val live = of(track("a", "x (live)", "1")); val none = of(track("b", "y", "2"))
        assertEquals("Live · all 2 songs", MusicVersions.albumSummary(listOf(live, live)))
        assertEquals("Live · 2 of 4 songs", MusicVersions.albumSummary(listOf(live, live, none, none)))
        assertEquals(null, MusicVersions.albumSummary(listOf(live, none, none)))
        val sessionOnly = of(track("c", "z (session)", "3"), choices = mapOf(LIVE to false))
        assertEquals("Session · all 2 songs", MusicVersions.albumSummary(listOf(sessionOnly, sessionOnly)), "counts the shown set")
    }
}
