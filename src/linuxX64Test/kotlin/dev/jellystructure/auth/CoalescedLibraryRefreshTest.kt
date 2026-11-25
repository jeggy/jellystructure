package dev.jellystructure.auth

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * DEB-5540 (FR-3, Acceptance 1) — the coalesced library refresh, on virtual time: one `Library/Refresh` 60 s after the
 * last of three requests 10 s apart, none while a scan runs, and one — not one per request — after it ends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoalescedLibraryRefreshTest {
    private class Rig(val scope: TestScope) {
        var scanning = false
        val fired = ArrayList<Long>()
        val trigger = CoalescedLibraryRefresh(
            scope = scope.backgroundScope,
            scanRunning = { _, _ -> scanning },
            fire = { _, _ -> fired.add(scope.currentTime); true },
            debounce = 60.seconds, scanPoll = 15.seconds,
            nowMs = { scope.currentTime },
        )
    }

    @Test
    fun `three requests 10 s apart make one refresh 60 s after the last`() = runTest {
        val rig = Rig(this)
        rig.trigger.request("http://jf", "t")
        advanceTimeBy(10.seconds); rig.trigger.request("http://jf", "t")
        advanceTimeBy(10.seconds); rig.trigger.request("http://jf", "t")
        advanceTimeBy(59.seconds); runCurrent()
        assertEquals(emptyList(), rig.fired, "nothing fires before 60 s of quiet")
        advanceTimeBy(1.seconds); runCurrent()
        assertEquals(listOf(80_000L), rig.fired)
        advanceTimeBy(600.seconds); runCurrent()
        assertEquals(1, rig.fired.size, "a refresh nobody asked for again is not repeated")
    }

    @Test
    fun `nothing fires while a scan runs and it fires once after the scan ends`() = runTest {
        val rig = Rig(this)
        rig.scanning = true
        rig.trigger.request("http://jf", "t")
        advanceTimeBy(30.seconds); rig.trigger.request("http://jf", "t")
        advanceTimeBy(300.seconds); runCurrent()
        assertEquals(emptyList(), rig.fired, "a scan is running")
        rig.scanning = false
        advanceTimeBy(15.seconds); runCurrent()
        assertEquals(1, rig.fired.size)
        advanceTimeBy(600.seconds); runCurrent()
        assertEquals(1, rig.fired.size)
    }

    @Test
    fun `a request after a refresh starts a new debounce`() = runTest {
        val rig = Rig(this)
        rig.trigger.request("http://jf", "t")
        advanceTimeBy(61.seconds); runCurrent()
        rig.trigger.request("http://jf", "t")
        advanceTimeBy(61.seconds); runCurrent()
        assertEquals(2, rig.fired.size)
    }
}
