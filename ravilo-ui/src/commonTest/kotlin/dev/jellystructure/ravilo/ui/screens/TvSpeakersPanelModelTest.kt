package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.PlaybackTarget
import dev.jellystructure.shared.tv.SessionCommandRequest
import dev.jellystructure.shared.tv.SessionDetail
import dev.jellystructure.shared.tv.SessionOwner
import dev.jellystructure.shared.tv.SessionRoom
import dev.jellystructure.shared.tv.SessionTarget
import dev.jellystructure.shared.tv.SessionView
import dev.jellystructure.shared.tv.TargetCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R372 (FR-R372-6, owner decision 3) — the TV's *Speakers* panel: when it exists, its rows, and what the D-pad does. */
class TvSpeakersPanelModelTest {
    private val office = SessionRoom("c-office", "Office", volume = 40)
    private val guest = SessionRoom("c-guest", "Guest room", volume = 24)
    private fun group(mine: Boolean = true, rooms: List<SessionRoom> = listOf(office, guest), kind: String = "music", here: Boolean = false,
                      state: String = "playing", controllable: Boolean = true) =
        SessionView("s1", 3, SessionOwner("u", "Anna"), mine = mine, kind = kind, title = "Cannery Lights",
            target = SessionTarget(if (rooms.size > 1) "cast_group" else "cast", "rx-1", "Office", "group", castDeviceId = "c-office"),
            state = state, here = here, controllable = controllable, rooms = rooms)
    private fun detail(v: SessionView = group(), ops: List<String> = listOf("set_volume", "set_mute", "add_room", "remove_room", "room_volume")) =
        SessionDetail(session = v, volume = 32, ops = ops)

    @Test fun `the panel exists for a group session sent to the TV`() {
        assertEquals("s1", tvSpeakersSession(listOf(group()))?.id)
        assertNull(tvSpeakersSession(listOf(group(rooms = listOf(office)))), "no panel on a session that isn't a group")
        assertNull(tvSpeakersSession(listOf(group(mine = false))), "someone else's session")
        assertNull(tvSpeakersSession(listOf(group(state = "ended"))))
        assertNull(tvSpeakersSession(listOf(group(controllable = false))))
    }

    @Test fun `the master goes to the receiver and a room to R371's road`() {
        val rows = tvSpeakersPanel(detail())
        assertEquals(listOf(null, "c-office", "c-guest", null), rows.map { it.castDeviceId })
        assertTrue(rows[0].master && rows.last().add)
        // ▶ on the master: set_volume with no room (the receiver's own level).
        assertEquals(TvSpeakersAction.Send(SessionCommandRequest(op = "set_volume", level = 37, castDeviceId = null)), tvSpeakersKey(rows, 0, TvKey.RIGHT))
        // ◀ on a room: that room's level, through the server.
        assertEquals(TvSpeakersAction.Send(SessionCommandRequest(op = "set_volume", level = 35, castDeviceId = "c-office")), tvSpeakersKey(rows, 1, TvKey.LEFT))
        // OK mutes the focused row.
        assertEquals(TvSpeakersAction.Send(SessionCommandRequest(op = "set_mute", muted = true, castDeviceId = "c-guest")), tvSpeakersKey(rows, 2, TvKey.OK))
    }

    @Test fun `a room with no road is disabled with its reason`() {
        val noRoad = tvSpeakersPanel(detail(ops = listOf("set_volume", "set_mute")))
        assertTrue(noRoad[0].enabled, "the master still works")
        assertEquals(listOf(false, false), noRoad.filter { it.castDeviceId != null }.map { it.enabled })
        assertEquals("group.no_reach", noRoad[1].reason)
        assertEquals(TvSpeakersAction.Nothing, tvSpeakersKey(noRoad, 1, TvKey.RIGHT))
        val silent = tvSpeakersPanel(detail(v = group(rooms = listOf(office, guest.copy(volume = null)))))
        assertEquals("volume.no_report", silent[2].reason)
    }

    @Test fun `up and down move between rows and stop at the ends`() {
        val rows = tvSpeakersPanel(detail())
        assertEquals(TvSpeakersAction.Focus(1), tvSpeakersKey(rows, 0, TvKey.DOWN))
        assertEquals(TvSpeakersAction.Nothing, tvSpeakersKey(rows, 0, TvKey.UP))
        assertEquals(TvSpeakersAction.Nothing, tvSpeakersKey(rows, rows.lastIndex, TvKey.DOWN))
        assertEquals(TvSpeakersAction.Close, tvSpeakersKey(rows, 2, TvKey.BACK))
    }

    @Test fun `the TV remote's volume keys are not consumed`() =
        assertEquals(TvSpeakersAction.NotConsumed, tvSpeakersKey(tvSpeakersPanel(detail()), 1, TvKey.OTHER))

    @Test fun `levels stop at 0 and 100`() {
        val rows = tvSpeakersPanel(detail(v = group(rooms = listOf(office.copy(volume = 100), guest.copy(volume = 0)))))
        assertEquals(TvSpeakersAction.Nothing, tvSpeakersKey(rows, 1, TvKey.RIGHT))
        assertEquals(TvSpeakersAction.Nothing, tvSpeakersKey(rows, 2, TvKey.LEFT))
    }

    @Test fun `Add a speaker opens the free speakers and OK adds one`() {
        val kitchen = PlaybackTarget("cast:c-kitchen", "cast", "Kitchen", "speaker", TargetCapabilities(audio = true), castDeviceId = "c-kitchen")
        val closed = tvSpeakersPanel(detail(), addOpen = false, targets = listOf(kitchen))
        assertEquals(TvSpeakersAction.ToggleAdd, tvSpeakersKey(closed, closed.lastIndex, TvKey.OK))
        val open = tvSpeakersPanel(detail(), addOpen = true, targets = listOf(kitchen))
        assertTrue(open.last().candidate)
        assertEquals(TvSpeakersAction.Send(SessionCommandRequest(op = "add_room", castDeviceId = "c-kitchen")), tvSpeakersKey(open, open.lastIndex, TvKey.OK))
    }

    @Test fun `the Speakers button sits last in the transport row`() {
        assertEquals(PlFocus.SPEAKERS, transportOrder(hasNextEp = true, hasSpeakers = true).last())
        assertTrue(PlFocus.SPEAKERS !in transportOrder(hasNextEp = true))
    }
}
