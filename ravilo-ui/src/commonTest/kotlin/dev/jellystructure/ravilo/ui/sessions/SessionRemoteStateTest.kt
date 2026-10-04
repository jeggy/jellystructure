package dev.jellystructure.ravilo.ui.sessions

import dev.jellystructure.shared.tv.RemoteCommand
import dev.jellystructure.shared.tv.SessionCommandEnvelope
import dev.jellystructure.shared.tv.SessionCommandRequest
import dev.jellystructure.shared.tv.SessionOwner
import dev.jellystructure.shared.tv.SessionTarget
import dev.jellystructure.shared.tv.SessionView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R369 — the client's half: reading a session command, the stop that asks, the press in flight, the bar's controls. */
class SessionRemoteStateTest {
    private fun env(op: String, item: String? = null, index: Int? = null) =
        SessionCommandEnvelope(sessionId = "s", command = SessionCommandRequest(op = op, index = 2), expectItem = item, expectIndex = index)

    private fun view(mine: Boolean = true, controllable: Boolean = true, state: String = "playing") = SessionView(
        id = "s", revision = 1, owner = SessionOwner("u", "Anna"), mine = mine, kind = "music",
        target = SessionTarget("cast", "d", "Office", "speaker"), state = state, controllable = controllable,
    )

    @Test fun `a session command here is the players own press`() =
        assertEquals(SessionCommandAction.Local(RemoteCommand.Pause), sessionCommandAction(env("pause"), here = true, holdsLink = false, "a", 0, 0))

    @Test fun `a session command for a cast this app sent goes over the link`() =
        assertEquals(SessionCommandAction.ViaLink(RemoteCommand.Next), sessionCommandAction(env("next"), here = false, holdsLink = true, null, null, null))

    @Test fun `a session command with a stale expected item does nothing and reports`() =
        assertEquals(SessionCommandAction.Stale, sessionCommandAction(env("next", item = "song-1"), here = true, holdsLink = false, "song-2", 1, 0))

    @Test fun `an unknown op is ignored`() =
        assertEquals(SessionCommandAction.Ignore, sessionCommandAction(env("teleport"), here = true, holdsLink = false, "a", 0, 0))

    @Test fun `stopping someone elses playback asks every time and your own never`() {
        assertTrue(stopNeedsConfirm(view(mine = false)))
        assertFalse(stopNeedsConfirm(view(mine = true)))
    }

    @Test fun `a press dims for 400 ms then a spinner after 1 s and cant reach after 3 s`() {
        assertEquals(CommandFeedback.DIM, commandFeedback(1_000, 1_300, reflected = false))
        assertEquals(CommandFeedback.NONE, commandFeedback(1_000, 1_700, reflected = false))
        assertEquals(CommandFeedback.SPINNER, commandFeedback(1_000, 2_500, reflected = false))
        assertEquals(CommandFeedback.CANT_REACH, commandFeedback(1_000, 4_100, reflected = false))
        assertEquals(CommandFeedback.NONE, commandFeedback(1_000, 4_100, reflected = true))
        assertEquals(CommandFeedback.NONE, commandFeedback(null, 4_100, reflected = false))
    }

    @Test fun `the bars play-pause and next are present only when controllable`() {
        assertTrue(barControlsShown(view(controllable = true)))
        assertFalse(barControlsShown(view(controllable = false)))
        assertFalse(barControlsShown(view(controllable = true, state = "ended")))
    }

    @Test fun `attach and detach frames name the session`() {
        assertEquals("{\"type\":\"attach_session\",\"id\":\"ps-1\"}", attachFrame("ps-1"))
        assertEquals("{\"type\":\"detach_session\",\"id\":\"ps-1\"}", detachFrame("ps-1"))
    }

    @Test fun `the TV declares session control only and never asks for the lists`() =
        assertEquals(setOf("session_control"), eventsFeaturesFor(isTv = true, obeysSessionCommands = true))
}
