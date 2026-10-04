package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.ravilo.ui.sessions.addSpeakerRows
import dev.jellystructure.shared.tv.PlaybackTarget
import dev.jellystructure.shared.tv.SessionRoom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R371 — the place line, removing a room, who adds a speaker, the volume rows, the *Add a speaker…* list. */
class GroupRulesTest {
    private fun room(id: String, name: String, volume: Int? = 50) = SessionRoom(id, name, volume)

    @Test fun `one room two rooms then the first plus a count`() {
        assertEquals("Office", placeLine(listOf("Office"), null, null, 0).first)
        assertEquals("Office + Kitchen", placeLine(listOf("Office", "Kitchen"), null, null, 0).first)
        assertEquals("Office + 2", placeLine(listOf("Office", "Kitchen", "Attic"), null, null, 0).first)
    }

    @Test fun `a room that left is named for 5 s then dropped`() {
        assertEquals("Attic", placeLine(listOf("Office"), "Attic", 1_000, 5_999).second)
        assertNull(placeLine(listOf("Office"), "Attic", 1_000, 6_001).second)
    }

    @Test fun `removing a room that is not the first deselects it`() =
        assertEquals(RoomRemoval.Deselect("b"), removeRoomPlan(listOf(room("a", "A"), room("b", "B"), room("c", "C")), "b"))

    @Test fun `removing the first room is a move to the remaining one`() =
        assertEquals(RoomRemoval.MoveTo("b"), removeRoomPlan(listOf(room("a", "A"), room("b", "B")), "a"))

    @Test fun `removing the last stops it`() = assertEquals(RoomRemoval.Stop, removeRoomPlan(listOf(room("a", "A")), "a"))

    @Test fun `Android 11 with the link adds directly and everything else goes by the server`() {
        assertEquals(AddSpeakerRoad.DIRECT, addSpeakerRoad(hasController = true, holdsLink = true, serverReaches = false))
        assertEquals(AddSpeakerRoad.SERVER, addSpeakerRoad(hasController = false, holdsLink = false, serverReaches = true))
        assertEquals(AddSpeakerRoad.DISABLED, addSpeakerRoad(hasController = false, holdsLink = false, serverReaches = false))
    }

    @Test fun `one place has one slider`() = assertEquals(1, volumeRows(40, false, "Office", listOf(room("a", "Office")), true).size)

    @Test fun `several rooms have the master then one slider each`() {
        val rows = volumeRows(60, false, "Office + Kitchen", listOf(room("a", "Office", 70), room("b", "Kitchen", 50)), true)
        assertEquals(listOf(null, "a", "b"), rows.map { it.castDeviceId })
        assertTrue(rows.all { it.enabled })
    }

    @Test fun `a room with no reported level is disabled`() {
        val rows = volumeRows(60, false, "x", listOf(room("a", "Office", null), room("b", "Kitchen", 50)), true)
        assertFalse(rows[1].enabled)
        assertTrue(rows[2].enabled)
    }

    @Test fun `room sliders are dead when nothing can reach the speakers`() =
        assertFalse(volumeRows(60, false, "x", listOf(room("a", "A"), room("b", "B")), roomsReachable = false)[1].enabled)

    @Test fun `only free speakers and displays not already in the group are offered`() {
        val t = listOf(
            PlaybackTarget("cast:a", "cast", "Office", "speaker", castDeviceId = "a"),
            PlaybackTarget("cast:b", "cast", "Kitchen", "speaker", castDeviceId = "b"),
            PlaybackTarget("mac", "app", "Mac", "computer"),
            PlaybackTarget("cast:c", "cast", "Attic", "speaker", castDeviceId = "c", reachable = false),
        )
        assertEquals(listOf("b"), addSpeakerRows(t, setOf("a")).map { it.castDeviceId })
    }
}
