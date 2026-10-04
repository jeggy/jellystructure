package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.ravilo.ui.sessions.eventsFeaturesFor
import dev.jellystructure.shared.tv.SessionRoom
import kotlin.test.Test
import kotlin.test.assertEquals

/** R371 (review items 7, 8 and 10) — a room op on the app that holds the link, a relay that joins first, and removals. */
class RoomJoinPlanTest {
    @Test fun `the link holder acts at once`() =
        assertEquals(RoomJoin.ACT, roomOpJoinPlan(CastLinkState.CONNECTED, "c-office", "c-office", seesPlace = true))

    @Test fun `a relay with no link joins the session's Cast device first`() =
        assertEquals(RoomJoin.JOIN, roomOpJoinPlan(CastLinkState.NONE, null, "c-office", seesPlace = true))

    @Test fun `linked to another device or blind to this one it cannot`() {
        assertEquals(RoomJoin.CANNOT, roomOpJoinPlan(CastLinkState.CONNECTED, "c-den", "c-office", seesPlace = true))
        assertEquals(RoomJoin.CANNOT, roomOpJoinPlan(CastLinkState.NONE, null, "c-office", seesPlace = false))
        assertEquals(RoomJoin.CANNOT, roomOpJoinPlan(CastLinkState.CONNECTING, null, "c-office", seesPlace = true))
    }

    @Test fun `only an app that can group declares group control`() {
        assertEquals(setOf("sessions", "session_control", "group_control"), eventsFeaturesFor(isTv = false, obeysSessionCommands = true, groupControl = true))
        assertEquals(setOf("sessions", "session_control"), eventsFeaturesFor(isTv = false, obeysSessionCommands = true, groupControl = false))
        assertEquals(setOf("sessions", "session_control"), eventsFeaturesFor(isTv = true, obeysSessionCommands = true, groupControl = true))
    }

    @Test fun `removing the first room is a move to the next and the last room stops`() {
        val rooms = listOf(SessionRoom("a", "Office"), SessionRoom("b", "Guest room"), SessionRoom("c", "Kitchen"))
        assertEquals(RoomRemoval.MoveTo("b"), removeRoomPlan(rooms, "a"))
        assertEquals(RoomRemoval.Deselect("c"), removeRoomPlan(rooms, "c"))
        assertEquals(RoomRemoval.Stop, removeRoomPlan(rooms.take(1), "a"))
    }
}
