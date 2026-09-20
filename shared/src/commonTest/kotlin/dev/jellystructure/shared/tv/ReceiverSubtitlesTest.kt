package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReceiverSubtitlesTest {
    // ── the VTT parser ────────────────────────────────────────────────────────────────────────────
    private val vtt = "﻿WEBVTT\r\n\r\nNOTE made by jellyfin\r\n\r\n1\r\n00:00:01.000 --> 00:00:03.500 line:85% align:center\r\n<i>Say his name.</i>\r\n\r\n" +
        "00:05.000 --> 00:07.000\n{\\an8}Honeyman &amp; me\nsecond line\n\n" +
        "garbage block with no timing\n\n" +
        "00:00:09,000 --> 00:00:08,000\nends before it starts\n\n" +
        "00:00:10.000 --> 00:00:11.000\n<b></b>\n\n" +
        "01:00:00.250 --> 01:00:02.000\n<v Anthony>An hour in</v>"
    private val cues = parseVtt(vtt)

    @Test fun it_keeps_only_real_cues() = assertEquals(3, cues.size)
    @Test fun tags_settings_and_identifiers_are_gone() = assertEquals(VttCue(1_000, 3_500, "Say his name."), cues[0])
    @Test fun hours_are_optional_and_ass_overrides_and_entities_are_cleaned() = assertEquals(VttCue(5_000, 7_000, "Honeyman & me\nsecond line"), cues[1])
    @Test fun hours_are_read_when_present() = assertEquals(3_600_250L, cues[2].startMs)

    @Test fun the_active_cue_is_found_by_position() {
        assertEquals("Say his name.", activeCueText(cues, 1_000))
        assertNull(activeCueText(cues, 3_500))   // end is exclusive
        assertNull(activeCueText(cues, 4_000))
    }
    @Test fun overlapping_cues_stack() =
        assertEquals("a\nb", activeCueText(listOf(VttCue(0, 10, "a"), VttCue(5, 15, "b")), 7))
    @Test fun an_empty_or_non_vtt_body_is_no_cues() { assertEquals(emptyList(), parseVtt("")); assertEquals(emptyList(), parseVtt("<html>503</html>")) }

    // ── the list and the pick — fixture: Honeyman's ticket on an HLS receiver ─────────────────────
    private fun text(i: Int, l: String) = SubTrack(index = i, language = l, label = l, url = "http://jf/$i.vtt", deliveryMethod = "external")
    private fun pgs(i: Int, l: String) = SubTrack(index = i, language = l, label = l, url = null, deliveryMethod = "encode")
    private fun ticket(burned: Int? = null, audio: Int? = 4) = StreamTicket(
        jellyfinBaseUrl = "", accessToken = "", itemId = "i", container = "mkv", directPlay = false, hlsUrl = "u", expiresAt = 1,
        // deliberately interleaved: the list must not depend on the ticket's order
        subtitles = listOf(pgs(6, "eng"), text(0, "dan"), pgs(7, "fra"), text(1, "hrv")),
        audio = listOf(AudioTrack(4, "eng", "TrueHD"), AudioTrack(5, "eng", "AC-3")),
        burnedSubtitleIndex = burned, audioStreamIndex = audio,
    )

    @Test fun text_tracks_keep_their_old_positions_and_pgs_is_appended() =
        assertEquals(listOf(0, 1, 6, 7), receiverSubtitles(ticket()).map { it.index })

    @Test fun picking_text_just_shows_it() = assertEquals(ReceiverSubPick.Text("http://jf/1.vtt", unburnFirst = false), receiverSubPick(ticket(), 1))
    @Test fun picking_pgs_burns_it_in() = assertEquals(ReceiverSubPick.Burn(6), receiverSubPick(ticket(), 2))
    @Test fun re_picking_the_burned_track_does_nothing() = assertEquals(ReceiverSubPick.Nothing, receiverSubPick(ticket(burned = 6), 2))
    @Test fun another_pgs_replaces_the_burn_in() = assertEquals(ReceiverSubPick.Burn(7), receiverSubPick(ticket(burned = 6), 3))
    @Test fun text_while_burned_in_must_unburn_first() = assertEquals(ReceiverSubPick.Text("http://jf/0.vtt", unburnFirst = true), receiverSubPick(ticket(burned = 6), 0))
    @Test fun off_while_burned_in_must_unburn_too() = assertEquals(ReceiverSubPick.Text(null, unburnFirst = true), receiverSubPick(ticket(burned = 6), -1))
    @Test fun off_otherwise_is_just_off() = assertEquals(ReceiverSubPick.Text(null, unburnFirst = false), receiverSubPick(ticket(), -1))

    @Test fun the_reported_selection_is_the_burned_track() = assertEquals(2, receiverSelectedSub(ticket(burned = 6), -1))
    @Test fun the_reported_selection_is_otherwise_the_drawn_one() = assertEquals(1, receiverSelectedSub(ticket(), 1))
    @Test fun the_reported_audio_is_the_carried_one() { assertEquals(1, receiverSelectedAudio(ticket(audio = 5))); assertEquals(0, receiverSelectedAudio(ticket(audio = null))) }
}
