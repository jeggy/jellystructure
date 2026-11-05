package dev.jellystructure.ravilo.ui.desktop

import dev.jellystructure.ravilo.ui.seams.CastLinkState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-3670 (R330 FR-R330-3a–3d) — the rejoin ladder after a drop, and what holds through it. */
@OptIn(ExperimentalCoroutinesApi::class)
class CastRejoinLadderTest {
    @Test fun `a drop is rejoined on the second try after one with no connection`() = runTest {
        val tries = mutableListOf<RejoinTry>()
        val results = ArrayDeque(listOf(RejoinTry.UNREACHED, RejoinTry.JOINED))
        val joined = runRejoinLadder(STARTUP_REJOIN_GAP_MS) { results.removeFirst().also { tries += it } }
        assertTrue(joined, "true is the caller's cue to send `status` (FR-R330-3b)")
        assertEquals(listOf(RejoinTry.UNREACHED, RejoinTry.JOINED), tries)
        assertEquals(2 * STARTUP_REJOIN_GAP_MS, currentTime, "the tries are 2 s apart")
    }

    @Test fun `a try that connects to a device not running our app ends the ladder at once`() = runTest {
        var attempts = 0
        val joined = runRejoinLadder(STARTUP_REJOIN_GAP_MS) { attempts++; RejoinTry.NOT_THERE }
        assertFalse(joined)
        assertEquals(1, attempts)
    }

    @Test fun `a device that is never reached gets three tries and then the ladder ends`() = runTest {
        var attempts = 0
        val joined = runRejoinLadder(STARTUP_REJOIN_GAP_MS) { attempts++; RejoinTry.UNREACHED }
        assertFalse(joined)
        assertEquals(STARTUP_REJOIN_TRIES, attempts)
        assertEquals(3 * STARTUP_REJOIN_GAP_MS, currentTime)
    }

    @Test fun `no try is made before the gap has passed`() = runTest {
        var attempts = 0
        runRejoinLadder(STARTUP_REJOIN_GAP_MS) { attempts++; RejoinTry.JOINED }
        assertEquals(1, attempts)
        assertEquals(STARTUP_REJOIN_GAP_MS, currentTime)
    }

    @Test fun `the status and volume stand through a rejoin of the same device only`() {
        assertTrue(castKeepsStateThroughRejoin(hold = true, heldDeviceId = "stue", newDeviceId = "stue"))
        assertFalse(castKeepsStateThroughRejoin(hold = true, heldDeviceId = "stue", newDeviceId = "gaest"), "another device's state describes nothing here")
        assertFalse(castKeepsStateThroughRejoin(hold = true, heldDeviceId = null, newDeviceId = "stue"), "start-up has nothing to hold")
        assertFalse(castKeepsStateThroughRejoin(hold = false, heldDeviceId = "stue", newDeviceId = "stue"), "a chosen device starts clean")
    }

    @Test fun `the process activity is held while a session is joined or rejoining`() {
        assertTrue(castActivityWanted(CastLinkState.CONNECTED))
        assertTrue(castActivityWanted(CastLinkState.RECONNECTING))
        assertFalse(castActivityWanted(CastLinkState.NONE))
        assertFalse(castActivityWanted(CastLinkState.CONNECTING))
    }
}
