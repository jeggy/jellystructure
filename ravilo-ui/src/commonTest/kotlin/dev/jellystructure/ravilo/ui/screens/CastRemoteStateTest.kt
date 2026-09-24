package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.seams.CastLinkState
import dev.jellystructure.ravilo.ui.seams.CastRemoteStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R299 — a receiver that said "failed" is reachable and idle; "Lost contact" is only for a lost link. */
class CastRemoteStateTest {
    private val failed = CastRemoteStatus(itemId = "e18", title = "Afsnit 18", failed = true, loaded = false)

    @Test fun a_failed_load_on_a_live_link_is_failed_not_lost() = assertEquals(RemoteState.FAILED, remoteState(failed, unreachable = false))
    @Test fun a_lost_link_is_lost_even_after_a_failure() = assertEquals(RemoteState.UNREACHABLE, remoteState(failed, unreachable = true))
    @Test fun a_lost_link_with_media_is_lost() = assertEquals(RemoteState.UNREACHABLE, remoteState(CastRemoteStatus(loaded = true, playing = true), unreachable = true))
    @Test fun playing_and_paused_read_as_before() {
        assertEquals(RemoteState.PLAYING, remoteState(CastRemoteStatus(loaded = true, playing = true), false))
        assertEquals(RemoteState.PAUSED, remoteState(CastRemoteStatus(loaded = true, playing = false), false))
    }
    @Test fun the_other_states_keep_their_order() {
        assertEquals(RemoteState.NO_SERVER, remoteState(CastRemoteStatus(noServer = true, busyRetryAfter = 5, ended = true), false))
        assertEquals(RemoteState.BUSY, remoteState(CastRemoteStatus(busyRetryAfter = 5, ended = true), false))
        assertEquals(RemoteState.ENDED, remoteState(CastRemoteStatus(ended = true, playing = true), false))
    }
    @Test fun transport_is_greyed_while_failed() = assertFalse(remoteTransportEnabled(failed.copy(loaded = true), false))
    @Test fun transport_is_live_while_playing() = assertTrue(remoteTransportEnabled(CastRemoteStatus(loaded = true, playing = true), false))
    @Test fun the_mini_bar_hides_after_a_failure() = assertFalse(castMiniBarVisible(CastLinkState.CONNECTED, failed.copy(loaded = true)))
    @Test fun the_mini_bar_shows_while_a_cast_runs() = assertTrue(castMiniBarVisible(CastLinkState.CONNECTED, CastRemoteStatus(loaded = true, playing = true)))
    @Test fun the_mini_bar_hides_after_an_end_and_without_a_link() {
        assertFalse(castMiniBarVisible(CastLinkState.CONNECTED, CastRemoteStatus(loaded = true, ended = true)))
        assertFalse(castMiniBarVisible(CastLinkState.RECONNECTING, CastRemoteStatus(loaded = true, playing = true)))
    }
    @Test fun the_failed_flag_is_set_by_failed_and_cleared_by_the_next_load() {
        assertTrue(failedAfter("failed", false))
        assertFalse(failedAfter("status", true))
        assertFalse(failedAfter("tracks", true))
        assertTrue(failedAfter("nextup", true))
        assertTrue(failedAfter("busy", true))
        assertFalse(failedAfter(null, false))
    }
}
