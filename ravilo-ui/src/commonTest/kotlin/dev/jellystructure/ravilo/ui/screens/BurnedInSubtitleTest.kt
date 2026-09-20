package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.seams.PlayerSubtitleTrack
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R282 — the two pure decisions behind "a burned-in subtitle is the only subtitle". Fixture is the
 * reported title, "Honeyman (2021)": three sideloaded text tracks (da/hr/sr → native flat 0..2) and
 * three PGS burn-in candidates at Jellyfin stream indices 6/7/8 (→ flat 3..5).
 */
class BurnedInSubtitleTest {
    private fun pgs(streamIndex: Int, lang: String) =
        PlayerSubtitleTrack(index = -1, label = lang, language = lang, deliveryMethod = "encode", jellyfinStreamIndex = streamIndex)
    private val encode = listOf(pgs(6, "eng"), pgs(7, "fra"), pgs(8, "spa"))

    // ── FR-R282-3: what the picker shows ──────────────────────────────────────────────────────────
    @Test fun no_burn_in_shows_the_text_selection() = assertEquals(0, shownSelectedSub(0, 3, encode, null))
    @Test fun no_burn_in_and_nothing_selected_shows_off() = assertEquals(-1, shownSelectedSub(-1, 3, encode, null))

    @Test fun a_burn_in_shows_the_burned_track_not_off() =
        // The reported state: English is in the picture, text is (now) off — the picker must say English.
        assertEquals(3, shownSelectedSub(-1, 3, encode, 6))

    @Test fun the_burned_tracks_flat_index_follows_the_native_count() =
        // An un-listed/late sideload changes nativeCount; the derivation must move with it.
        assertEquals(2 + 2, shownSelectedSub(-1, 2, encode, 8))

    @Test fun a_burn_in_the_list_does_not_contain_falls_back_to_the_text_selection() =
        assertEquals(-1, shownSelectedSub(-1, 3, encode, 99))

    // ── FR-R282-4: what a pick does ───────────────────────────────────────────────────────────────
    @Test fun a_text_pick_with_nothing_burned_just_selects() = assertEquals(SubPickAction.SELECT, subPickAction(false, -1, null))
    @Test fun off_with_nothing_burned_just_selects() = assertEquals(SubPickAction.SELECT, subPickAction(false, null, null))
    @Test fun a_pgs_pick_burns_in() = assertEquals(SubPickAction.BURN_IN, subPickAction(true, 6, null))
    @Test fun another_pgs_pick_replaces_the_burn_in() = assertEquals(SubPickAction.BURN_IN, subPickAction(true, 7, 6))
    @Test fun re_picking_the_burned_track_does_not_reload() = assertEquals(SubPickAction.NONE, subPickAction(true, 6, 6))

    @Test fun off_while_burned_in_must_restream_without_it() =
        // Before R282 this only disabled the text renderer and left English in the pixels for good.
        assertEquals(SubPickAction.UNBURN, subPickAction(false, null, 6))

    @Test fun a_text_pick_while_burned_in_must_restream_without_it() =
        // Before R282 this ADDED Danish on top of burned-in English — the report, from the other side.
        assertEquals(SubPickAction.UNBURN, subPickAction(false, -1, 6))
}

/** R284 — the single-audio session helpers. Fixture: Honeyman's two audio tracks (Jellyfin 4 and 5). */
class SingleAudioSessionTest {
    private val ticketAudio = listOf(
        dev.jellystructure.shared.tv.AudioTrack(index = 4, language = "eng", label = "TrueHD Atmos 7.1", codec = "truehd", channels = 8, isDefault = true),
        dev.jellystructure.shared.tv.AudioTrack(index = 5, language = "eng", label = "AC-3 5.1", codec = "ac3", channels = 6, isDefault = false),
    )
    private val oneHlsTrack = listOf(dev.jellystructure.ravilo.ui.seams.PlayerAudioTrack(0, "TrueHD Atmos 7.1", "eng", 2, true))

    @Test fun direct_play_keeps_the_players_own_list() =
        assertEquals(oneHlsTrack, sessionAudioTracks(ticketAudio, null, oneHlsTrack))

    @Test fun a_single_audio_session_lists_every_real_track() {
        val l = sessionAudioTracks(ticketAudio, 5, oneHlsTrack)
        assertEquals(listOf("TrueHD Atmos 7.1", "AC-3 5.1"), l.map { it.label })
        assertEquals(listOf(0, 1), l.map { it.index })
    }

    @Test fun it_waits_for_the_player_before_claiming_tracks_are_ready() =
        assertEquals(emptyList(), sessionAudioTracks(ticketAudio, 5, emptyList()))

    @Test fun the_carried_track_is_found_by_jellyfin_index_not_position() = assertEquals(1, carriedAudioPosition(ticketAudio, 5))
    @Test fun direct_play_carries_nothing() = assertEquals(null, carriedAudioPosition(ticketAudio, null))
    @Test fun an_index_the_list_lacks_is_not_a_single_audio_session() = assertEquals(null, carriedAudioPosition(ticketAudio, 9))
}
