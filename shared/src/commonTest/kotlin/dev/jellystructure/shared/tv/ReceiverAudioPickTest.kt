package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * R291 (FR-R291-4) — a receiver's audio pick: a rendition switch where the stream's master lists renditions,
 * R285's restream where it does not. Lives in :shared with the subtitle pick for the same reason (the receiver
 * modules are browser-only JS with no test runner).
 */
class ReceiverAudioPickTest {
    // Five audio tracks, Jellyfin's own indices; the stream carries #5 (the second), as on the 2026-10-10 cast.
    private fun ticket(renditions: Boolean, carried: Int? = 5) = StreamTicket(
        jellyfinBaseUrl = "", accessToken = "", itemId = "i", container = "mkv", directPlay = false,
        hlsUrl = "/api/tv/stream/abc/master.m3u8", expiresAt = 1,
        audio = listOf(
            AudioTrack(4, "eng", "English TrueHD 7.1"),
            AudioTrack(5, "eng", "English AC-3 5.1"),
            AudioTrack(6, "dan", "Dansk"),
        ),
        audioStreamIndex = carried, audioRenditions = renditions,
    )

    @Test fun a_stream_with_renditions_switches_in_place() =
        assertEquals(ReceiverAudioPick.Rendition(2), receiverAudioPick(ticket(renditions = true), 2, selectedPosition = 1))

    @Test fun a_stream_without_renditions_restreams_with_jellyfins_index() =
        assertEquals(ReceiverAudioPick.Restream(6), receiverAudioPick(ticket(renditions = false), 2, selectedPosition = 1))

    @Test fun the_track_that_already_plays_is_left_alone() {
        assertEquals(ReceiverAudioPick.Nothing, receiverAudioPick(ticket(renditions = true), 1, selectedPosition = 1))
        assertEquals(ReceiverAudioPick.Nothing, receiverAudioPick(ticket(renditions = false), 1, selectedPosition = 1))
    }

    /** An in-place switch leaves `audio_stream_index` on the carried track, so a second pick must compare
     *  against the rendition made active — not against the ticket, which would read as "already playing". */
    @Test fun rendition_to_rendition_switches_again() {
        val t = ticket(renditions = true)
        assertEquals(ReceiverAudioPick.Rendition(0), receiverAudioPick(t, 0, selectedPosition = 2))
        // …and going back to the carried track is a switch like any other.
        assertEquals(ReceiverAudioPick.Rendition(1), receiverAudioPick(t, 1, selectedPosition = 2))
    }

    @Test fun a_position_the_ticket_does_not_have_does_nothing() {
        assertEquals(ReceiverAudioPick.Nothing, receiverAudioPick(ticket(renditions = true), 9, selectedPosition = 1))
        assertEquals(ReceiverAudioPick.Nothing, receiverAudioPick(ticket(renditions = true), -1, selectedPosition = 1))
        assertEquals(ReceiverAudioPick.Nothing, receiverAudioPick(null, 0, selectedPosition = 0))
    }

    /** With nothing active in place, what plays is the carried track — and 0 when the ticket cannot say. */
    @Test fun the_carried_track_is_the_selection_until_a_rendition_is_active() {
        assertEquals(1, receiverSelectedAudio(ticket(renditions = true)))
        assertEquals(0, receiverSelectedAudio(ticket(renditions = true, carried = null)))
    }

    // ── the rendition name the backend writes (`composeMaster`), as a player lists it ──────────────
    @Test fun a_renditions_position_is_read_from_its_name() {
        assertEquals(0, renditionPosition("a0 English TrueHD 7.1"))
        assertEquals(2, renditionPosition("a2 Dansk"))
        assertEquals(11, renditionPosition("a11"))
    }

    @Test fun nothing_else_is_a_rendition() {
        assertNull(renditionPosition(null))
        assertNull(renditionPosition(""))
        assertNull(renditionPosition("audio"))        // the word, not a0
        assertNull(renditionPosition("a1b"))          // a digit run must end the token
        assertNull(renditionPosition("English"))
        assertNull(renditionPosition("1 English"))
    }
}
